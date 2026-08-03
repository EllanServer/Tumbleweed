package net.tumbleweed.paper.model;

import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.core.entity.culling.Cullable;
import net.momirealms.craftengine.core.entity.culling.CullingData;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.world.collision.AABB;
import net.tumbleweed.paper.Tumbleweed;
import net.tumbleweed.paper.TumbleweedManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CraftEngine 可见性判定 (性能优化,可选依赖)。
 *
 * 直接使用 CE 自带的 culling 体系,不重复造轮子:
 * CE 的 EntityCullingThread (异步线程池 + CAS 防重入 + 玩家 hash 分片 + 50ms 周期)
 * 会对每个玩家的 trackedEntities 逐实体判定视锥 + 遮挡,并回调 Cullable.show/hide。
 * 我们只需把风滚草实现为 {@link Cullable} 注册进每个在线玩家的 trackedEntities
 * (Player#addTrackedEntity),判定完全在 CE 自己的异步线程池执行,主线程零开销。
 *
 * 注册/注销为事件驱动 (主线程无周期遍历):
 *  - 玩家上线 PlayerJoinEvent -> 把当前活跃风滚草注册到该玩家
 *  - 玩家退服 PlayerQuitEvent -> 丢弃该玩家的 CE 引用
 *  - 风滚草生成 (MythicMobSpawnEvent) -> 注册到所有在线玩家
 *  - 风滚草消失 (死亡/脱管/淡出) -> 从所有在线玩家注销
 *  - AABB 由 Tumbleweed 每 tick 物理后写入 volatile 字段,CE 判定线程直接读取
 *    (≤1 tick 旧,无需主线程快照)
 *
 * 线程安全:
 *  - show/hide 回调发生在 CE 异步线程 -> 只写 ConcurrentHashMap + volatile 标志
 *  - cullingData() 同样在 CE 线程被调用 -> 只读 Tumbleweed 的 volatile AABB 快照,
 *    绝不触碰 Bukkit API
 *  - 玩家位置/相机/遮挡区块数据全部由 CE 内部维护,我们只提供实体 AABB
 *
 * 降级:
 *  - 服务器未安装 CraftEngine -> 本类构造失败,{@link #isVisibleToAnyone} 恒返回 null,
 *    由调用方回退到原 96 格球半径降频,行为与不引入 CE 完全一致
 *  - CE 配置关闭实体剔除 / 玩家未判定 (shownBy 为空) -> 视为可见 (全速),同样安全回退
 */
public class CullingIntegration {

    private final boolean available;
    // 玩家 UUID -> CE 玩家实例 (主线程维护;addTrackedEntity/removeTrackedEntity 由主线程调用)
    private final Map<UUID, Player> cePlayers = new ConcurrentHashMap<>();
    // 风滚草 UUID -> Cullable 适配 (registerTumbleweed/unregisterTumbleweed 成对维护)
    private final Map<UUID, CullableTumbleweed> cullables = new ConcurrentHashMap<>();

    public CullingIntegration() {
        boolean ok = false;
        try {
            // 触发 CE 类加载;未安装 CraftEngine 时抛 NoClassDefFoundError -> 整体禁用
            Class.forName("net.momirealms.craftengine.bukkit.api.BukkitAdaptor");
            Class.forName("net.momirealms.craftengine.core.entity.culling.Cullable");
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        this.available = ok;
    }

    public boolean available() {
        return available;
    }

    /** 玩家上线:注册当前所有活跃风滚草到该玩家的 trackedEntities。 */
    public void onPlayerJoin(org.bukkit.entity.Player p, TumbleweedManager manager) {
        if (!available) {
            return;
        }
        Player ce = cePlayers.computeIfAbsent(p.getUniqueId(), uid -> BukkitAdaptor.adapt(p));
        if (ce == null) {
            return;
        }
        for (CullableTumbleweed ct : cullables.values()) {
            ce.addTrackedEntity(ct.entityId, ct);
        }
    }

    /** 玩家退服:CE 玩家实例在退服后失效,重进时需重新 adapt。 */
    public void onPlayerQuit(UUID playerId) {
        if (!available) {
            return;
        }
        cePlayers.remove(playerId);
    }

    /** 风滚草生成 (MythicMobSpawnEvent):注册到所有在线玩家的 trackedEntities。 */
    public void registerTumbleweed(Tumbleweed tw) {
        if (!available) {
            return;
        }
        Entity e = tw.entity();
        CullableTumbleweed ct = cullables.computeIfAbsent(e.getUniqueId(), uid -> new CullableTumbleweed(tw));
        for (Player ce : cePlayers.values()) {
            ce.addTrackedEntity(ct.entityId, ct);
        }
    }

    /** 风滚草消失 (死亡/脱管/淡出):从所有在线玩家的 trackedEntities 注销。 */
    public void unregisterTumbleweed(Tumbleweed tw) {
        if (!available) {
            return;
        }
        CullableTumbleweed ct = cullables.remove(tw.entity().getUniqueId());
        if (ct == null) {
            return;
        }
        for (Player ce : cePlayers.values()) {
            ce.removeTrackedEntity(ct.entityId);
        }
    }

    /**
     * 判定结果:null = CE 不可用或未注册 (调用方回退球半径),
     * true/false = 对任何玩家可见/不可见 (CE 线程 50ms 级判定)。
     */
    public Boolean isVisibleToAnyone(Tumbleweed tw) {
        if (!available) {
            return null;
        }
        CullableTumbleweed ct = cullables.get(tw.entity().getUniqueId());
        if (ct == null) {
            return null;
        }
        return ct.isVisibleToAnyone();
    }

    public void shutdown() {
        cullables.clear();
        cePlayers.clear();
    }

    /**
     * 风滚草的 Cullable 适配 (位于 CE 依赖隔离层,避免 Tumbleweed 核心类依赖 CE)。
     * show/hide 由 CE 异步线程在状态翻转时回调 (每玩家一次),只更新并发状态;
     * cullingData() 读 Tumbleweed 主线程写入的 volatile AABB 快照 (≤1 tick 旧)。
     */
    static final class CullableTumbleweed implements Cullable {

        final int entityId;
        private final Tumbleweed owner;
        // 每玩家可见状态:CE 线程写,主线程聚合读
        private final Map<UUID, Boolean> shownBy = new ConcurrentHashMap<>();
        private volatile boolean anyVisible = false;

        CullableTumbleweed(Tumbleweed owner) {
            this.owner = owner;
            this.entityId = owner.entity().getEntityId();
        }

        /** 主线程调用:未判定过 (空) 视为可见,保证全速安全回退。 */
        boolean isVisibleToAnyone() {
            return shownBy.isEmpty() || anyVisible;
        }

        @Override
        public void show(Player player) {
            shownBy.put(player.uuid(), true);
            anyVisible = true;
        }

        @Override
        public void hide(Player player) {
            shownBy.put(player.uuid(), false);
            // 状态翻转频率低 (每玩家每次切换才回调),玩家数少,重算成本可忽略
            boolean any = false;
            for (Boolean v : shownBy.values()) {
                if (v) {
                    any = true;
                    break;
                }
            }
            anyVisible = any;
        }

        @Override
        public CullingData cullingData() {
            // maxDistance=0 表示不做距离限制;AABB 为底 y 高 h,与碰撞一致
            return new CullingData(
                    new AABB(owner.cullMinX, owner.cullMinY, owner.cullMinZ,
                            owner.cullMaxX, owner.cullMaxY, owner.cullMaxZ),
                    0, 0.2, true);
        }
    }
}
