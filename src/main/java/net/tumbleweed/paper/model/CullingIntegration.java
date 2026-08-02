package net.tumbleweed.paper.model;

import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.core.entity.culling.CullingData;
import net.momirealms.craftengine.core.entity.culling.EntityCulling;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.collision.AABB;
import net.tumbleweed.paper.Tumbleweed;
import net.tumbleweed.paper.TumbleweedManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CraftEngine 可见性判定 (性能优化,可选依赖)。
 *
 * 原理:借用 CraftEngine 的 {@link EntityCulling} 判定"实体对玩家是否可见"
 * (视锥 + 距离 + 方块遮挡采样,14 点 DDA),把判定结果用于风滚草的
 * 物理/渲染同步降频 —— 玩家视锥外或被遮挡的风滚草同样无需每 tick 同步。
 *
 * 快筛:近处 (≤ culling-near-distance) 直接判可见 (玩家近处不允许卡顿),
 * 远处 (> distant-physics-distance) 直接判不可见 (既有球半径降频覆盖),
 * 仅中间区间才走 CE 判定,控制调用量。
 *
 * 降级:服务器未安装 CraftEngine 时本类构造失败,{@link #isVisibleToAnyone}
 * 恒返回 null,由调用方回退到原 96 格球半径降频,行为与不引入 CE 完全一致。
 */
public class CullingIntegration {

    /** CE 判定结果刷新间隔 (tick,与玩家距离缓存同周期) */
    private static final int REFRESH_INTERVAL = 10;

    private final boolean available;
    private final Map<UUID, EntityCulling> cullings = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> visibleCache = new HashMap<>(); // 风滚草 UUID -> 对任何玩家可见
    private int refreshTicks;

    public CullingIntegration() {
        boolean ok = false;
        try {
            // 触发 CE 类加载;未安装 CraftEngine 时抛 NoClassDefFoundError -> 整体禁用
            Class.forName("net.momirealms.craftengine.bukkit.api.BukkitAdaptor");
            Class.forName("net.momirealms.craftengine.core.entity.culling.EntityCulling");
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        this.available = ok;
    }

    public boolean available() {
        return available;
    }

    /** 每 tick 调用;按 REFRESH_INTERVAL 周期刷新可见性缓存。 */
    public void tick(TumbleweedManager manager, int nearDistance, int farDistance) {
        if (!available) {
            return;
        }
        if (++refreshTicks < REFRESH_INTERVAL) {
            return;
        }
        refreshTicks = 0;
        refresh(manager, nearDistance, farDistance);
    }

    /** 判定结果:null = CE 不可用,true/false = 对任何玩家可见/不可见。 */
    public Boolean isVisibleToAnyone(UUID tumbleweedId) {
        if (!available) {
            return null;
        }
        synchronized (visibleCache) {
            return visibleCache.get(tumbleweedId);
        }
    }

    public void remove(UUID tumbleweedId) {
        synchronized (visibleCache) {
            visibleCache.remove(tumbleweedId);
        }
    }

    public void shutdown() {
        synchronized (visibleCache) {
            visibleCache.clear();
        }
        cullings.clear();
    }

    /** 全量刷新:对每个风滚草判定是否对任何玩家可见。 */
    private void refresh(TumbleweedManager manager, int nearDistance, int farDistance) {
        double nearSq = (double) nearDistance * nearDistance;
        double farSq = (double) farDistance * farDistance;

        // 清理已下线玩家的剔除实例
        cullings.keySet().removeIf(id -> Bukkit.getPlayer(id) == null || !Bukkit.getPlayer(id).isOnline());

        synchronized (visibleCache) {
            visibleCache.clear();
            for (Tumbleweed tw : manager.all()) {
                Entity e = tw.entity();
                if (e.isDead() || !e.isValid()) {
                    continue;
                }
                Location el = e.getLocation();
                double w = tw.mcSize();
                double h = tw.mcSize();
                // 风滚草 AABB:底 y,高 h (与碰撞一致);CE 判定按包围盒采样
                AABB aabb = new AABB(el.getX() - w / 2, el.getY(), el.getZ() - w / 2,
                        el.getX() + w / 2, el.getY() + h, el.getZ() + w / 2);
                boolean visible = false;
                for (Player p : e.getWorld().getPlayers()) {
                    if (!p.isOnline()) {
                        continue;
                    }
                    Location pl = p.getLocation();
                    double dx = pl.getX() - el.getX();
                    double dy = pl.getY() - el.getY();
                    double dz = pl.getZ() - el.getZ();
                    double dSq = dx * dx + dy * dy + dz * dz;
                    // 近处:始终全速,不查 CE
                    if (dSq <= nearSq) {
                        visible = true;
                        break;
                    }
                    // 远处:既有球半径降频覆盖,不查 CE
                    if (dSq > farSq) {
                        continue;
                    }
                    // 中间区间:CE 视锥 + 遮挡判定
                    EntityCulling culling = cullings.computeIfAbsent(p.getUniqueId(), uid -> {
                        net.momirealms.craftengine.core.entity.player.Player cePlayer = BukkitAdaptor.adapt(p);
                        return new EntityCulling(cePlayer);
                    });
                    Location eye = p.getEyeLocation();
                    Vec3d camera = new Vec3d(eye.getX(), eye.getY(), eye.getZ());
                    // maxDistance=0 表示不做距离限制 (距离快筛已在外层完成)
                    CullingData data = new CullingData(aabb, 0, 0.2, true);
                    if (culling.isVisible(data, camera, true)) {
                        visible = true;
                        break;
                    }
                }
                visibleCache.put(e.getUniqueId(), visible);
            }
        }
    }
}
