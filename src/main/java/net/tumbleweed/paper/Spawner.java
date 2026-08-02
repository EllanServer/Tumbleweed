package net.tumbleweed.paper;

import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 风滚草生成器,忠实移植原版 Spawner:
 *  - 每 10 秒 (200 ticks) 尝试一次
 *  - 玩家周围 17x17 区块中挑选符合条件的区块
 *  - 在干灌木等 spawner 方块上生成,20% 概率成双
 *  - 受 spawnChance / maxPerPlayer / 生物群系配置控制
 */
public class Spawner {

    private static final int TRY_SPAWN_TICKS = 10 * 20;
    private static final int MOB_COUNT_DIV = 17 * 17;
    private static final int SEARCH_RADIUS = 2;
    private static final int SPAWN_ATTEMPTS = 10;

    private final TumbleweedPlugin plugin;
    private final TumbleweedManager manager;
    private final Random random = new Random();
    private BukkitTask task;

    public Spawner(TumbleweedPlugin plugin, TumbleweedManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 7L, TRY_SPAWN_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        for (World world : Bukkit.getWorlds()) {
            trySpawn(world);
        }
    }

    private void trySpawn(World world) {
        if (!world.getGameRuleValue(GameRule.DO_MOB_SPAWNING)) {
            return;
        }

        // 收集玩家周围的合格区块 (原版 17x17,去边缘)
        List<Location> eligibleChunks = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            if (player.isSpectator()) {
                continue;
            }
            int playerX = player.getLocation().getBlockX() >> 4;
            int playerZ = player.getLocation().getBlockZ() >> 4;

            for (int x = 8; x >= -8; x--) {
                for (int z = 8; z >= -8; z--) {
                    boolean corner = x == -8 || x == 8 || z == -8 || z == 8;
                    if (corner) {
                        continue;
                    }
                    Location chunkCenter = new Location(world,
                            (x + playerX) * 16 + 8, 0, (z + playerZ) * 16 + 8);
                    if (!world.getWorldBorder().isInside(chunkCenter)) {
                        continue;
                    }
                    if (!world.isChunkLoaded((x + playerX), (z + playerZ))) {
                        continue;
                    }
                    if (!plugin.pluginConfig().isBiomeAllowed(world.getBiome(chunkCenter.getBlockX(), chunkCenter.getBlockZ()))) {
                        continue;
                    }
                    eligibleChunks.add(chunkCenter);
                }
            }
        }

        Collections.shuffle(eligibleChunks);

        int current = manager.count();
        int max = (int) Math.ceil(plugin.pluginConfig().getMaxPerPlayer()
                * eligibleChunks.size() / (double) MOB_COUNT_DIV);

        for (Location chunk : eligibleChunks) {
            if (current > max) {
                break;
            }
            if (random.nextDouble() > plugin.pluginConfig().getSpawnChance()) {
                continue;
            }

            // 找 spawner 方块 (默认干灌木)
            Location surface = getRandomSurfacePosition(chunk);
            Block spawner = null;
            outer:
            for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    Block check = world.getHighestBlockAt(surface.getBlockX() + x, surface.getBlockZ() + z);
                    if (plugin.pluginConfig().isSpawnerBlock(check.getType()) && canSeeSky(check)) {
                        spawner = check;
                        break outer;
                    }
                }
            }
            if (spawner == null) {
                continue;
            }

            int packSize = 1 + (random.nextFloat() < 0.2f ? 1 : 0);
            int packSpawned = 0;

            for (int i = 0; i < SPAWN_ATTEMPTS; i++) {
                int x = spawner.getX() + random.nextInt(5) - random.nextInt(5);
                int y = spawner.getY() + random.nextInt(2) - random.nextInt(2);
                int z = spawner.getZ() + random.nextInt(5) - random.nextInt(5);

                Block below = world.getBlockAt(x, y - 1, z);
                if (!below.getBlockData().getMaterial().isOccluding()) {
                    continue;
                }
                Location loc = new Location(world, x + 0.5, y + 0.5 + 0.5 * random.nextDouble(), z + 0.5);

                // 附近 32 格内不能有玩家,且距世界出生点 24 格外
                if (hasNearbyPlayer(world, loc, 32)) {
                    continue;
                }
                if (world.getSpawnLocation().distanceSquared(loc) < 24.0 * 24.0) {
                    continue;
                }
                if (!isUnobstructed(world, loc)) {
                    continue;
                }

                int size = random.nextInt(5) - 2; // -2 ~ 2 (原版)
                if (spawnTumbleweed(world, loc, size)) {
                    current++;
                    packSpawned++;
                    if (packSpawned == packSize) {
                        break;
                    }
                }
            }
        }
    }

    /** 通过 MythicMobs 生成风滚草怪物。 */
    private boolean spawnTumbleweed(World world, Location loc, int size) {
        try {
            io.lumine.mythic.bukkit.MythicBukkit.inst().getMobManager().spawnMob(
                    "Tumbleweed", BukkitAdapter.adapt(loc));
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("MythicMobs 生成风滚草失败: " + e.getMessage());
            return false;
        }
    }

    private Location getRandomSurfacePosition(Location chunkCenter) {
        int x = chunkCenter.getBlockX() + random.nextInt(16 - SEARCH_RADIUS * 2) + SEARCH_RADIUS;
        int z = chunkCenter.getBlockZ() + random.nextInt(16 - SEARCH_RADIUS * 2) + SEARCH_RADIUS;
        int y = chunkCenter.getWorld().getHighestBlockYAt(x, z);
        return new Location(chunkCenter.getWorld(), x, y, z);
    }

    private boolean canSeeSky(Block block) {
        World world = block.getWorld();
        int y = block.getY();
        for (int i = y + 1; i < world.getMaxHeight(); i++) {
            Block above = world.getBlockAt(block.getX(), i, block.getZ());
            if (above.getType().isAir()) {
                continue;
            }
            if (above.getBlockData().getMaterial().isOccluding()) {
                return false;
            }
        }
        return true;
    }

    private boolean hasNearbyPlayer(World world, Location loc, int range) {
        double rangeSq = (double) range * range;
        for (Player p : world.getPlayers()) {
            if (p.isOnline() && !p.isSpectator()
                    && p.getLocation().distanceSquared(loc) < rangeSq) {
                return true;
            }
        }
        return false;
    }

    /** 原版 isNotColliding:方块无碰撞且不淹水。 */
    private boolean isUnobstructed(World world, Location loc) {
        org.bukkit.util.BoundingBox bb = new org.bukkit.util.BoundingBox(
                loc.getX() - 0.5, loc.getY(), loc.getZ() - 0.5,
                loc.getX() + 0.5, loc.getY() + 1, loc.getZ() + 0.5);
        int minX = (int) Math.floor(bb.getMinX());
        int maxX = (int) Math.floor(bb.getMaxX());
        int minY = (int) Math.floor(bb.getMinY());
        int maxY = (int) Math.floor(bb.getMaxY());
        int minZ = (int) Math.floor(bb.getMinZ());
        int maxZ = (int) Math.floor(bb.getMaxZ());
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (type.isAir() || type == Material.WATER) {
                        continue;
                    }
                    if (world.getBlockAt(x, y, z).getBlockData().getMaterial().isCollidable()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
