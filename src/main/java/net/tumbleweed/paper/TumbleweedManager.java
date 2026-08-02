package net.tumbleweed.paper;

import net.tumbleweed.paper.model.ModelController;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理所有活跃风滚草:注册表 + 每 tick 物理调度 + 模型同步。
 *
 * 性能设计 (对应原版 1.14 ServerEntityMixin 的同步精简思路):
 *  - 最近玩家距离每 10 tick 全量刷新一次,供脱管检查与远处降频判断复用,
 *    避免每个风滚草每 tick 遍历全部玩家 (原版为每实体每 tick 查询)。
 *  - 距最近玩家超过 performance.distant-physics-distance 格 (默认 96) 的风滚草
 *    处于所有玩家视距外,物理与渲染同步降频为每 distant-physics-interval tick 一次,
 *    寿命按真实时间补偿,玩家靠近后自动恢复全速。
 *  - 脱管检查 (原版 110 格) 仍每 tick 执行,基于缓存的玩家距离 (最多延迟 10 tick 消失)。
 */
public class TumbleweedManager {

    private static final int PLAYER_CHECK_INTERVAL = 10;  // 玩家距离缓存刷新间隔 (tick)
    private static final double DESPAWN_RANGE_SQ = 110 * 110; // 原版脱管距离

    private final TumbleweedPlugin plugin;
    private final Map<UUID, Tumbleweed> tumbleweeds = new ConcurrentHashMap<>();
    private final Map<UUID, Double> playerDistSq = new HashMap<>(); // 每风滚草 -> 最近玩家距离平方
    private BukkitTask task;
    private int windTicks;        // 原版:每 2 分钟翻转一次风向
    private int playerCheckTicks; // 玩家距离缓存刷新计数

    public TumbleweedManager(TumbleweedPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Tumbleweed t : tumbleweeds.values()) {
            ModelController.detach(t.entity());
        }
        tumbleweeds.clear();
        playerDistSq.clear();
    }

    private void tick() {
        // 原版 CommonEventHandler:每 2*60*20 tick 翻转一次风向
        if (++windTicks >= 2 * 60 * 20) {
            windTicks = 0;
            plugin.rollWind();
        }

        // 每 10 tick 刷新一次各风滚草的最近玩家距离 (玩家位置误差 ≤10 tick,远小于 110/96 阈值)
        if (++playerCheckTicks >= PLAYER_CHECK_INTERVAL) {
            playerCheckTicks = 0;
            refreshPlayerDistances();
        }

        int distantDistance = plugin.pluginConfig().distantPhysicsDistance();
        int distantInterval = plugin.pluginConfig().distantPhysicsInterval();

        Iterator<Map.Entry<UUID, Tumbleweed>> it = tumbleweeds.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Tumbleweed> entry = it.next();
            Tumbleweed tw = entry.getValue();
            if (tw.entity().isDead() || !tw.entity().isValid()) {
                ModelController.detach(tw.entity());
                it.remove();
                playerDistSq.remove(entry.getKey());
                continue;
            }

            Double dSq = playerDistSq.get(entry.getKey());

            // 脱管检查 (原版:最近玩家三维距离 > 110 → 消失)
            if (!tw.isPersistent() && dSq != null && dSq > DESPAWN_RANGE_SQ) {
                playerDistSq.remove(entry.getKey());
                it.remove();
                ModelController.detach(tw.entity());
                tw.entity().remove();
                continue;
            }

            // 远处降频:玩家视距外且未淡出 → 每 N tick 才跑物理与渲染同步
            if (dSq != null && dSq > (double) distantDistance * distantDistance
                    && !tw.isFading() && !tw.shouldRunPhysics(distantInterval)) {
                // 寿命按真实时间补偿,远处风滚草不会因降频而活得变久
                tw.ageTick(distantInterval);
                continue;
            }

            tw.tick(this);
            // 旋转 + 压扁同步到 ModelEngine root 骨骼;淡出 alpha 乘入 scale 模拟渐隐
            // (ModelEngine 无透明度 API,原版 80 tick 透明度渐变以尺寸渐变近似)
            float fade = tw.alpha();
            ModelController.sync(tw.entity(), tw.rotation().quat,
                    tw.renderScaleX() * fade, tw.renderScaleY() * fade, tw.renderScaleZ() * fade);
        }
    }

    /** 刷新每个风滚草到最近玩家的距离平方 (同世界在线玩家,位置一次取)。 */
    private void refreshPlayerDistances() {
        if (tumbleweeds.isEmpty()) {
            return;
        }
        // 按世界分组收集玩家位置 (只取一次,避免每风滚草重复取)
        Map<org.bukkit.World, java.util.List<Location>> playersByWorld = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOnline()) {
                continue;
            }
            playersByWorld.computeIfAbsent(p.getWorld(), w -> new java.util.ArrayList<>()).add(p.getLocation());
        }
        for (Tumbleweed tw : tumbleweeds.values()) {
            Entity e = tw.entity();
            Location el = e.getLocation();
            double best = Double.MAX_VALUE;
            for (Location pl : playersByWorld.getOrDefault(e.getWorld(), java.util.Collections.emptyList())) {
                double dx = pl.getX() - el.getX();
                double dy = pl.getY() - el.getY();
                double dz = pl.getZ() - el.getZ();
                double d = dx * dx + dy * dy + dz * dz;
                if (d < best) {
                    best = d;
                }
            }
            playerDistSq.put(e.getUniqueId(), best);
        }
    }

    /** 注册新的风滚草 (由 MythicListener 在 MM 实体生成后调用)。 */
    public void register(Tumbleweed tw) {
        if (tumbleweeds.putIfAbsent(tw.entity().getUniqueId(), tw) == null) {
            ModelController.attach(tw.entity());
            playerDistSq.put(tw.entity().getUniqueId(), Double.MAX_VALUE);
        }
    }

    /** 移除并清理 (死亡 / 淡出结束 / 超出范围)。 */
    public void remove(Tumbleweed tw) {
        Tumbleweed removed = tumbleweeds.remove(tw.entity().getUniqueId());
        if (removed != null) {
            playerDistSq.remove(tw.entity().getUniqueId());
            ModelController.detach(tw.entity());
            if (tw.entity().isValid() && !tw.entity().isDead()) {
                tw.entity().remove();
            }
        }
    }

    /** 按实体查找。 */
    public Tumbleweed get(Entity entity) {
        return tumbleweeds.get(entity.getUniqueId());
    }

    /** 判断实体是否为风滚草 (用于避免互推)。 */
    public boolean isTumbleweed(Entity entity) {
        return tumbleweeds.containsKey(entity.getUniqueId());
    }

    public int count() {
        return tumbleweeds.size();
    }

    public Iterable<Tumbleweed> all() {
        return tumbleweeds.values();
    }
}
