package net.tumbleweed.paper;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Skeleton;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 骷髅射击风滚草 AI,忠实移植原版 ShootTumbleweed 目标:
 *  - 每 tick 0.3% 概率尝试
 *  - 目标:9~18 格内、视线内最近的风滚草
 *  - 70 tick 流程:前 30 tick 瞄准,第 40 tick 射出箭
 *  - 箭速 1.6 (原版 shoot(1.6F, 1)),少量散布
 */
public class SkeletonShooter {

    private static final float MAX_DISTANCE = 18;
    private static final float MIN_DISTANCE = 9;
    private static final float TRIGGER_CHANCE = 0.003F;
    private static final int LOOK_TIME = 70;
    private static final int SHOOT_AT = 40;

    private final TumbleweedPlugin plugin;
    private final TumbleweedManager manager;
    private final Random random = new Random();

    /** 正在进行射击流程的骷髅 UUID -> 剩余 lookTime。 */
    private final Map<UUID, Integer> active = new ConcurrentHashMap<>();

    private BukkitTask task;

    public SkeletonShooter(TumbleweedPlugin plugin, TumbleweedManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        active.clear();
    }

    private void tick() {
        for (World world : Bukkit.getWorlds()) {
            for (Skeleton skeleton : world.getEntitiesByClass(Skeleton.class)) {
                if (!skeleton.isValid() || skeleton.isDead()) {
                    active.remove(skeleton.getUniqueId());
                    continue;
                }
                UUID id = skeleton.getUniqueId();
                if (active.containsKey(id)) {
                    continue;
                }
                if (random.nextFloat() > TRIGGER_CHANCE) {
                    continue;
                }
                Tumbleweed target = findTarget(skeleton);
                if (target != null) {
                    active.put(id, LOOK_TIME);
                }
            }
        }

        // 推进射击流程
        active.entrySet().removeIf(entry -> {
            Skeleton skeleton = findSkeleton(entry.getKey());
            if (skeleton == null || !skeleton.isValid() || skeleton.isDead()) {
                return true;
            }
            int lookTime = entry.getValue() - 1;
            if (lookTime == SHOOT_AT) {
                Tumbleweed target = findTarget(skeleton);
                if (target != null) {
                    shoot(skeleton, target);
                }
                return true; // 单次射击,原版一箭即止
            }
            entry.setValue(lookTime);
            return false;
        });
    }

    private Skeleton findSkeleton(UUID id) {
        for (World world : Bukkit.getWorlds()) {
            for (Skeleton skeleton : world.getEntitiesByClass(Skeleton.class)) {
                if (skeleton.getUniqueId().equals(id)) {
                    return skeleton;
                }
            }
        }
        return null;
    }

    private Tumbleweed findTarget(Skeleton skeleton) {
        Location from = skeleton.getEyeLocation();
        Tumbleweed best = null;
        double bestDist = -1;
        for (Tumbleweed tw : manager.all()) {
            Entity e = tw.entity();
            if (!e.getWorld().equals(skeleton.getWorld())) {
                continue;
            }
            double distSq = from.distanceSquared(e.getLocation());
            if (distSq < MIN_DISTANCE * MIN_DISTANCE || distSq > MAX_DISTANCE * MAX_DISTANCE) {
                continue;
            }
            if (!skeleton.hasLineOfSight(e)) {
                continue;
            }
            if (bestDist == -1 || distSq < bestDist) {
                best = tw;
                bestDist = distSq;
            }
        }
        return best;
    }

    /** 原版 performRangedAttack:箭速 1.6,预判 0.2 高度补偿。 */
    private void shoot(Skeleton skeleton, Tumbleweed target) {
        World world = skeleton.getWorld();
        Location from = skeleton.getEyeLocation();
        Location to = target.entity().getLocation().add(0, 0.5, 0);

        double dX = to.getX() - from.getX();
        double dY = to.getY() - from.getY();
        double dZ = to.getZ() - from.getZ();
        double dist = Math.sqrt(dX * dX + dZ * dZ);

        Arrow arrow = world.spawn(from, Arrow.class, a -> a.setShooter(skeleton));
        Vector dir = new Vector(dX, dY + dist * 0.2, dZ).normalize();
        arrow.setVelocity(dir.multiply(1.6));

        // 原版拉弓音效
        world.playSound(skeleton.getLocation(), Sound.ENTITY_SKELETON_SHOOT, 1.0f,
                1.0f / (random.nextFloat() * 0.4f + 0.8f));
    }
}
