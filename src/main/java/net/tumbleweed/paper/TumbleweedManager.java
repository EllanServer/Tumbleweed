package net.tumbleweed.paper;

import net.tumbleweed.paper.config.PluginConfig;
import net.tumbleweed.paper.model.CullingIntegration;
import net.tumbleweed.paper.model.ModelController;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
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
 *  - 脱管检查 (原版 160 格) 仍每 tick 执行,基于缓存的玩家距离 (最多延迟 10 tick 消失)。
 */
public class TumbleweedManager {

    private static final int PLAYER_CHECK_INTERVAL = 10;  // 玩家距离缓存刷新间隔 (tick)
    private static final double DESPAWN_RANGE_SQ = 160 * 160; // 原版 1.20.1 脱管距离 (最近玩家 160 格)

    private final TumbleweedPlugin plugin;
    // 注册表:UUID -> Tumbleweed,供按实体查询 (get/isTumbleweed)
    private final Map<UUID, Tumbleweed> tumbleweeds = new ConcurrentHashMap<>();
    // 每 tick 遍历列表:TickerList 数组遍历 + 标记删除 (借鉴 SparklyPaper/CE 的 BlockEntityTickersList),
    // 避免 CHM 迭代器分配与遍历中反复写,删除集中在 tick 末尾一次 System.arraycopy 批量搬移
    private final TickerList<Tumbleweed> ticking = new TickerList<>();
    private final Map<UUID, Double> playerDistSq = new HashMap<>(); // 每风滚草 -> 最近玩家距离平方
    private final Map<UUID, Integer> worldCounts = new HashMap<>(); // 世界 UUID -> 该世界活跃风滚草数
    private CullingIntegration culling; // 可选:CE 可见性判定 (未安装 CraftEngine 时为 null)
    private BukkitTask task;
    private final Random random = new Random();
    private int playerCheckTicks; // 玩家距离缓存刷新计数

    public TumbleweedManager(TumbleweedPlugin plugin) {
        this.plugin = plugin;
    }

    /** 启用 CraftEngine 可见性判定 (服务器安装 CraftEngine 时调用)。 */
    public void enableCulling(CullingIntegration culling) {
        this.culling = culling;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (int i = 0; i < ticking.size(); i++) {
            ModelController.detach(ticking.get(i).entity());
        }
        ticking.clear();
        tumbleweeds.clear();
        playerDistSq.clear();
        worldCounts.clear();
        if (culling != null) {
            culling.shutdown();
        }
    }

    private void tick() {
        // 原版 1.20.1: 风力恒定, 无风向翻转

        // 每 10 tick 刷新一次各风滚草的最近玩家距离 (玩家位置误差 ≤10 tick,远小于 160/96 阈值)
        if (++playerCheckTicks >= PLAYER_CHECK_INTERVAL) {
            playerCheckTicks = 0;
            refreshPlayerDistances();
        }

        int distantDistance = plugin.pluginConfig().distantPhysicsDistance();
        int distantInterval = plugin.pluginConfig().distantPhysicsInterval();

        // 数组遍历 (TickerList 思路):删除只标记索引,结束后一次性批量搬移
        for (int i = 0; i < ticking.size(); i++) {
            Tumbleweed tw = ticking.get(i);
            if (tw.entity().isDead() || !tw.entity().isValid()) {
                if (culling != null) {
                    culling.unregisterTumbleweed(tw);
                }
                ModelController.detach(tw.entity());
                ticking.markRemoved(i);
                tumbleweeds.remove(tw.entity().getUniqueId());
                playerDistSq.remove(tw.entity().getUniqueId());
                worldCounts.merge(tw.entity().getWorld().getUID(), -1, Integer::sum);
                continue;
            }

            Double dSq = playerDistSq.get(tw.entity().getUniqueId());

            // 脱管检查 (原版 1.20.1:最近玩家三维距离 > 160 → 消失; 骑乘矿车或命名后不消失)
            if (!tw.isPersistent() && tw.entity().getVehicle() == null
                    && dSq != null && dSq > DESPAWN_RANGE_SQ) {
                if (culling != null) {
                    culling.unregisterTumbleweed(tw);
                }
                playerDistSq.remove(tw.entity().getUniqueId());
                ticking.markRemoved(i);
                tumbleweeds.remove(tw.entity().getUniqueId());
                worldCounts.merge(tw.entity().getWorld().getUID(), -1, Integer::sum);
                ModelController.detach(tw.entity());
                tw.entity().remove();
                continue;
            }

            // 远处降频:玩家视距外且未淡出 → 每 N tick 才跑物理与渲染同步。
            // 判定来源:CE 可见性 (视锥 + 遮挡,由 CE 异步线程池 50ms 级判定) 或 96 格球半径兜底;
            // CE 未安装时 culling 为 null,回退为原球半径降频,行为完全一致。
            boolean distant;
            if (culling != null && plugin.pluginConfig().cullingEnabled()) {
                Boolean visible = culling.isVisibleToAnyone(tw);
                // CE 判定未命中(刚生成/刷新间隙)时回退 96 格球半径兜底
                distant = visible != null ? !visible
                        : dSq != null && dSq > (double) distantDistance * distantDistance;
            } else {
                distant = dSq != null && dSq > (double) distantDistance * distantDistance;
            }
            if (distant && !tw.isFading() && !tw.shouldRunPhysics(distantInterval)) {
                // 寿命按真实时间补偿,远处风滚草不会因降频而活得变久
                tw.ageTick(distantInterval);
                continue;
            }

            tw.tick(this);
            // 旋转/压扁/淡出已由 Tumbleweed 在物理 tick 末尾写入 volatile 渲染快照,
            // ModelController 注册的 ME tick 任务 (PRE_MODEL_TICK,异步线程) 惰性同步到 root 骨骼,
            // 主线程不再参与渲染同步 (消除 SafeTransform 跨线程竞争,且省去每 tick 链式查找)
        }
        // 批量搬移被标记删除的元素 (每 tick 末尾一次,而不是遍历中反复删除)
        ticking.compact();
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
        for (int i = 0; i < ticking.size(); i++) {
            Tumbleweed tw = ticking.get(i);
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
            ModelController.attach(tw);
            playerDistSq.put(tw.entity().getUniqueId(), Double.MAX_VALUE);
            worldCounts.merge(tw.entity().getWorld().getUID(), 1, Integer::sum);
            ticking.add(tw);
            if (culling != null) {
                culling.registerTumbleweed(tw);
            }
        }
    }

    /** 移除并清理 (死亡 / 淡出结束 / 超出范围)。 */
    public void remove(Tumbleweed tw) {
        if (tumbleweeds.remove(tw.entity().getUniqueId()) != null) {
            playerDistSq.remove(tw.entity().getUniqueId());
            worldCounts.merge(tw.entity().getWorld().getUID(), -1, Integer::sum);
            ticking.markRemoved(tw.tickIndex());
            ModelController.detach(tw.entity());
            if (culling != null) {
                culling.unregisterTumbleweed(tw);
            }
            if (tw.entity().isValid() && !tw.entity().isDead()) {
                tw.entity().remove();
            }
        }
    }

    /** 玩家上线:把活跃风滚草注册进该玩家的 CE 可见性判定 (事件驱动,无周期遍历)。 */
    public void onPlayerJoin(org.bukkit.entity.Player player) {
        if (culling != null) {
            culling.onPlayerJoin(player, this);
        }
    }

    /** 玩家退服:丢弃该玩家的 CE 引用。 */
    public void onPlayerQuit(UUID playerId) {
        if (culling != null) {
            culling.onPlayerQuit(playerId);
        }
    }

    /** 按实体查找。 */
    public Tumbleweed get(Entity entity) {
        return tumbleweeds.get(entity.getUniqueId());
    }

    /**
     * 生成一只风滚草 (由 TumbleweedSpawner 调用)。
     * 经 MythicMobs API 创建 MM 实体 (怪物属性/践踏/掉落由 Tumbleweed.yml 配置驱动),
     * 创建成功后立即注册物理, 与 MythicListener 的事件注册幂等。
     * 生成前检查原版 isNotColliding (生成点无方块碰撞), 失败则销毁实体返回 false。
     */
    public boolean spawn(org.bukkit.World world, double x, double y, double z) {
        io.lumine.mythic.core.mobs.ActiveMob mob;
        try {
            mob = io.lumine.mythic.bukkit.MythicBukkit.inst().getMobManager()
                    .spawnMob("Tumbleweed", new Location(world, x, y, z));
        } catch (Throwable t) {
            plugin.getLogger().warning("MythicMobs 生成风滚草失败: " + t.getMessage());
            return false;
        }
        if (mob == null || mob.getEntity() == null) {
            return false;
        }
        Entity entity = mob.getEntity().getBukkitEntity();
        if (entity == null || entity.isDead() || !entity.isValid()) {
            return false;
        }
        // 原版 isNotColliding: 生成点两格 (实体占位) 必须无碰撞方块
        if (!isSpawnClear(world, x, y, z)) {
            entity.remove();
            return false;
        }
        if (entity instanceof org.bukkit.entity.LivingEntity living) {
            living.setAI(false);
            living.setCollidable(false);
        }
        Tumbleweed tw = new Tumbleweed(entity, random.nextInt(5) - 2); // 原版: size ∈ [-2, 2]
        tw.setPersistent(false);
        register(tw);
        return true;
    }

    /** 原版 isNotColliding 的方块部分: 生成点与上方一格均无碰撞方块且无液体 (生成频率低, 直接查 Block)。 */
    private boolean isSpawnClear(org.bukkit.World world, double x, double y, double z) {
        int bx = org.bukkit.util.NumberConversions.floor(x);
        int by = org.bukkit.util.NumberConversions.floor(y);
        int bz = org.bukkit.util.NumberConversions.floor(z);
        for (int dy = 0; dy <= 1; dy++) {
            if (by + dy < world.getMinHeight() || by + dy >= world.getMaxHeight()) {
                continue;
            }
            Material type = world.getBlockAt(bx, by + dy, bz).getType();            // 原版: 无方块碰撞 (干灌木等无碰撞盒方块放行) 且无液体 (containsAnyLiquid)
            if ((type.isCollidable() && !type.isAir())
                    || type == Material.WATER || type == Material.BUBBLE_COLUMN) {
                return false;
            }
        }
        return true;
    }

    /** 指定世界内的活跃风滚草数量 (生成上限判定用,O(1) 查表)。 */
    public int countInWorld(org.bukkit.World world) {
        return worldCounts.getOrDefault(world.getUID(), 0);
    }

    /** 判断实体是否为风滚草 (用于避免互推)。 */
    public boolean isTumbleweed(Entity entity) {
        return tumbleweeds.containsKey(entity.getUniqueId());
    }

    public int count() {
        return ticking.size();
    }

    public Iterable<Tumbleweed> all() {
        return tumbleweeds.values();
    }

    /**
     * 数组 + 标记删除列表。思路借鉴 SparklyPaper 的 BlockEntityTickersList (CE 的 TickersList 同源):
     * 每 tick 遍历直接走底层数组 (无迭代器分配),删除只标记索引,统一在 tick 末尾
     * 用 System.arraycopy 一次性批量搬移 —— 比迭代器 remove 快 (无每次删除的数组移位)。
     */
    private static final class TickerList<T> {
        private Object[] elements = new Object[16];
        private int size;
        private int[] marked = new int[8];
        private int markedCount;
        private int startSearchFromIndex = -1;

        T get(int index) {
            @SuppressWarnings("unchecked")
            T e = (T) elements[index];
            return e;
        }

        int size() {
            return size;
        }

        void add(T e) {
            if (size == elements.length) {
                elements = java.util.Arrays.copyOf(elements, size * 2);
            }
            elements[size] = e;
            if (e instanceof Tumbleweed tw) {
                tw.setTickIndex(size);
            }
            size++;
        }

        /** 标记待删除索引 (同一索引重复标记无害,compact 按计数处理)。 */
        void markRemoved(int index) {
            if (startSearchFromIndex == -1 || index < startSearchFromIndex) {
                startSearchFromIndex = index;
            }
            if (markedCount == marked.length) {
                marked = java.util.Arrays.copyOf(marked, marked.length * 2);
            }
            marked[markedCount++] = index;
        }

        void clear() {
            java.util.Arrays.fill(elements, 0, size, null);
            size = 0;
            markedCount = 0;
            startSearchFromIndex = -1;
        }

        /** 批量搬移被标记删除的元素,并同步剩余元素的 tickIndex。 */
        void compact() {
            if (startSearchFromIndex == -1) {
                return;
            }
            final int requiredMatches = markedCount;
            if (requiredMatches == 0) {
                startSearchFromIndex = -1;
                return;
            }

            final Object[] a = elements;
            int writeIndex = startSearchFromIndex;
            int lastCopyIndex = startSearchFromIndex;
            int matches = 0;

            for (int readIndex = startSearchFromIndex; readIndex < size; readIndex++) {
                if (isMarked(readIndex)) {
                    matches++;
                    final int blockLength = readIndex - lastCopyIndex;
                    if (blockLength > 0) {
                        System.arraycopy(a, lastCopyIndex, a, writeIndex, blockLength);
                        writeIndex += blockLength;
                    }
                    lastCopyIndex = readIndex + 1;
                    if (matches == requiredMatches) {
                        break;
                    }
                }
            }

            final int finalBlockLength = size - lastCopyIndex;
            if (finalBlockLength > 0) {
                System.arraycopy(a, lastCopyIndex, a, writeIndex, finalBlockLength);
                writeIndex += finalBlockLength;
            }

            if (writeIndex < size) {
                java.util.Arrays.fill(a, writeIndex, size, null);
            }
            size = writeIndex;
            // 被搬移元素索引变化,同步更新 (remove() 依赖 tickIndex 正确)
            for (int i = startSearchFromIndex; i < size; i++) {
                ((Tumbleweed) a[i]).setTickIndex(i);
            }
            markedCount = 0;
            startSearchFromIndex = -1;
        }

        private boolean isMarked(int index) {
            for (int i = 0; i < markedCount; i++) {
                if (marked[i] == index) {
                    return true;
                }
            }
            return false;
        }
    }
}
