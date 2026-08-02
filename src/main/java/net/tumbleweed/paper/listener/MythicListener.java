package net.tumbleweed.paper.listener;

import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import io.lumine.mythic.bukkit.events.MythicMobSpawnEvent;
import io.lumine.mythic.core.mobs.ActiveMob;
import net.tumbleweed.paper.Tumbleweed;
import net.tumbleweed.paper.TumbleweedManager;
import net.tumbleweed.paper.config.PluginConfig;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.Optional;

/**
 * 接入 MythicMobs 生命周期:
 *  - 实体生成 -> 注册为风滚草 (物理接管)
 *  - 实体死亡 -> 按原版战利品表掉落 (骷髅击杀附带唱片权重)
 */
public class MythicListener implements Listener {

    private static final String MOB_ID = "Tumbleweed";

    private final TumbleweedManager manager;
    private final PluginConfig config;

    public MythicListener(TumbleweedManager manager, PluginConfig config) {
        this.manager = manager;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSpawn(MythicMobSpawnEvent event) {
        if (!isTumbleweedMob(event.getMobType().getInternalName())) {
            return;
        }
        Optional<Entity> opt = event.getMob().getEntity();
        if (opt.isEmpty()) {
            return;
        }
        Entity entity = opt.get();
        if (entity.isDead()) {
            return;
        }

        // 默认尺寸 2 (0.75 + 2/8 = 1.0 格);自定义 spawner 生成的尺寸由 Spawner 决定
        int size = 2;
        Tumbleweed tw = new Tumbleweed(entity, size);
        tw.setPersistent(false);

        // NoAI + 关闭推搡,物理完全由插件接管
        if (entity instanceof org.bukkit.entity.LivingEntity living) {
            living.setAI(false);
        }
        entity.setCollidable(false);

        manager.register(tw);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(MythicMobDeathEvent event) {
        if (!isTumbleweedMob(event.getMobType().getInternalName())) {
            return;
        }
        Tumbleweed tw = manager.get(event.getMob().getEntity().orElse(null));
        if (tw != null) {
            manager.remove(tw);
        }

        // 掉落条件:原版 enableDrops && (玩家击杀 || !dropOnlyByPlayer)
        Entity killer = event.getKiller();
        if (!config.isEnableDrops()) {
            return;
        }
        if (config.isDropOnlyByPlayer() && !(killer instanceof Player)) {
            return;
        }

        Entity entity = event.getMob().getEntity().orElse(null);
        if (entity == null) {
            return;
        }

        // 骷髅击杀:音乐唱片进入战利品池 (原版 creeper_drop_music_discs 标签)
        boolean skeletonKilled = killer instanceof Skeleton;
        Material drop = config.rollLoot(skeletonKilled);
        if (drop == null) {
            return;
        }

        Item item = entity.getWorld().dropItemNaturally(entity.getLocation(), new ItemStack(drop));
        if (item != null) {
            item.setVelocity(new Vector(0, 0.2, 0)); // 原版 spawnAtLocation 速度
        }
    }

    private boolean isTumbleweedMob(String internalName) {
        return MOB_ID.equals(internalName);
    }
}
