package net.tumbleweed.paper;

import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 风滚草自然生成器 —— 忠实移植原版 mod 1.14 分支的 {@code Spawner} 逻辑。
 *
 * 原版机制 (Common/src/main/java/net/konwboy/tumbleweed/common/Spawner.java):
 *  - 每 10 秒 (200 tick, gameTime % 200 == 7) 对每个世界检查一次
 *  - 对每个非旁观玩家:其所在区块周围 ±8 区块 (排除角落),要求在世界边界内、
 *    区块中心群系在白名单 (forge is_dry / is_sandy 近似为干燥群系集合)
 *  - 候选区块打乱后逐个处理:随机数 > spawnChance (0.5) 跳过;
 *    当前世界风滚草数超过 maxPerPlayer(8) * 候选数 / 289 则停止
 *  - 区块内随机地表位置 ±2 格寻找干灌木 (dead_bush, 原版 spawners tag) 且见天空
 *  - 每群 1 只, 20% 概率 2 只 (packSize);最多 10 次找位尝试:干灌木 ±5 格、
 *    高度 ±2 格, 下方必须为不透明方块, 生成点 32 格内无玩家、世界出生点 24 格外
 *  - 实体经 MythicMobs API 生成, 怪物属性/践踏/掉落仍由 MM 配置驱动
 *    (MM 的 RandomSpawner 表达不了: 干灌木限定 / ±5 偏移 / 20% 成双 / 动态上限)
 */
public class TumbleweedSpawner implements Runnable {

    private static final int TRY_SPAWN_TICKS = 10 * 20;   // 原版: 每 10 秒检查一次
    private static final int MOB_COUNT_DIV = 17 * 17;     // 289 (17×17 区块)
    private static final int SEARCH_RADIUS = 2;           // 干灌木搜索半径
    private static final int SPAWN_ATTEMPTS = 10;         // 找位尝试次数
    private static final int PLAYER_EXCLUSION = 32;       // 生成点 32 格内无玩家
    private static final int SPAWN_PROTECTION = 24;       // 世界出生点 24 格保护
    private static final double PACK_DOUBLE_CHANCE = 0.2; // 20% 概率成双

    /** 原版 biome 白名单 (forge:is_dry + forge:is_sandy 的 1.21 近似集合)。 */
    private static final Set<String> DRY_BIOMES = Set.of(
            "minecraft:desert", "minecraft:badlands", "minecraft:wooded_badlands",
            "minecraft:eroded_badlands", "minecraft:savanna", "minecraft:savanna_plateau",
            "minecraft:windswept_savanna");

    private final TumbleweedPlugin plugin;
    private final Random random = new Random();
    private BukkitTask task;

    public TumbleweedSpawner(TumbleweedPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        // 原版 gameTime % 200 == 7 时触发:启动 7 tick 后首查,之后每 200 tick
        task = Bukkit.getScheduler().runTaskTimer(plugin, this, 7L, TRY_SPAWN_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @Override
    public void run() {
        for (World world : Bukkit.getWorlds()) {
            Boolean doMobSpawning = world.getGameRuleValue(GameRule.DO_MOB_SPAWNING);
            if (doMobSpawning != null && !doMobSpawning) {
                continue; // 原版 RULE_DOMOBSPAWNING
            }
            trySpawn(world);
        }
    }

    /** 原版 trySpawn:收集合格候选区块 -> 打乱 -> 按上限与概率逐个生成。 */
    private void trySpawn(World world) {
        Set<Long> candidates = new HashSet<>();
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                continue;
            }
            int playerChunkX = player.getLocation().getBlockX() >> 4;
            int playerChunkZ = player.getLocation().getBlockZ() >> 4;
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    // 原版: 排除 17×17 的角落区块
                    if (x == -8 || x == 8 || z == -8 || z == 8) {
                        continue;
                    }
                    int chunkX = x + playerChunkX;
                    int chunkZ = z + playerChunkZ;
                    if (candidates.contains(key(chunkX, chunkZ))) {
                        continue;
                    }
                    if (!world.getWorldBorder().isInside(new Location(world, chunkX * 16 + 8, 0, chunkZ * 16 + 8))) {
                        continue;
                    }
                    // 区块中心群系白名单 (原版 getBiome(chunk 中心, y=0))
                    if (!isDryBiome(world.getBiome(chunkX * 16 + 8, 0, chunkZ * 16 + 8))) {
                        continue;
                    }
                    candidates.add(key(chunkX, chunkZ));
                }
            }
        }
        if (candidates.isEmpty()) {
            return;
        }

        List<Long> chunkList = new ArrayList<>(candidates);
        Collections.shuffle(chunkList);

        int current = plugin.tumbleweedManager().countInWorld(world);
        int max = (int) Math.ceil(plugin.pluginConfig().spawnerMaxPerPlayer() * chunkList.size()
                / (double) MOB_COUNT_DIV);

        Location worldSpawn = world.getSpawnLocation();
        double spawnChance = plugin.pluginConfig().spawnerChance();

        for (long key : chunkList) {
            if (current > max) {
                break;
            }
            if (random.nextDouble() > spawnChance) {
                continue;
            }

            int chunkX = (int) (key >> 32);
            int chunkZ = (int) (key & 0xFFFFFFFFL);
            Block spawner = findSpawnerBlock(world, chunkX, chunkZ);
            if (spawner == null) {
                continue;
            }

            // 原版 packSize = 1 + (20% ? 1 : 0)
            int packSize = 1 + (random.nextFloat() < PACK_DOUBLE_CHANCE ? 1 : 0);
            int packSpawned = 0;

            for (int i = 0; i < SPAWN_ATTEMPTS; i++) {
                int x = spawner.getX() + random.nextInt(5) - random.nextInt(5);
                int y = spawner.getY() + random.nextInt(2) - random.nextInt(2);
                int z = spawner.getZ() + random.nextInt(5) - random.nextInt(5);

                // 下方必须为不透明方块 (原版 canOcclude)
                if (!world.getBlockAt(x, y - 1, z).getType().isOccluding()) {
                    continue;
                }
                // 生成点 32 格内无存活玩家 (原版 hasNearbyAlivePlayer 32)
                if (hasPlayerNearby(world, x, y, z, PLAYER_EXCLUSION)) {
                    continue;
                }
                // 世界出生点 24 格外 (原版 worldSpawn.distSqr < 24*24)
                if (worldSpawn.toVector().distanceSquared(new Vector(x, y, z)) < SPAWN_PROTECTION * SPAWN_PROTECTION) {
                    continue;
                }

                // 原版 y + 0.5 + 0.5*random (实体中心在地表上方 0.5~1.0 格)
                if (plugin.tumbleweedManager().spawn(world,
                        x + 0.5, y + 0.5 + 0.5 * random.nextDouble(), z + 0.5)) {
                    current++;
                    packSpawned++;
                    if (packSpawned == packSize) {
                        break;
                    }
                }
            }
        }
    }

    /** 原版 getRandomSurfacePosition + ±2 干灌木搜索 (spawners tag = dead_bush)。 */
    private Block findSpawnerBlock(World world, int chunkX, int chunkZ) {
        int startX = chunkX * 16 + random.nextInt(16 - SEARCH_RADIUS) + SEARCH_RADIUS;
        int startZ = chunkZ * 16 + random.nextInt(16 - SEARCH_RADIUS) + SEARCH_RADIUS;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                int sx = startX + dx;
                int sz = startZ + dz;
                // 高度图: 干灌木无碰撞盒, 高度图位置即干灌木所在格 (原版取 check 处方块)
                int y = world.getHighestBlockYAt(sx, sz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                Block check = world.getBlockAt(sx, y, sz);
                if (check.getType() == Material.DEAD_BUSH) {
                    // 原版 canSeeSkyFromBelowWater: 上方可见天空 (上方一格为空)
                    Block above = world.getBlockAt(sx, y + 1, sz);
                    if (above.getType().isAir() || !above.getType().isOccluding()) {
                        return check;
                    }
                }
            }
        }
        return null;
    }

    /** 原版 hasNearbyAlivePlayer (球形距离, 含 y)。 */
    private boolean hasPlayerNearby(World world, int x, int y, int z, double radius) {
        Location center = new Location(world, x, y, z);
        for (Entity e : world.getNearbyEntities(center, radius, radius, radius)) {
            if (e instanceof Player p && !p.isDead()) {
                return true;
            }
        }
        return false;
    }

    private boolean isDryBiome(org.bukkit.block.Biome biome) {
        try {
            return DRY_BIOMES.contains(biome.getKey().toString());
        } catch (Throwable t) {
            return false;
        }
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
