package net.tumbleweed.paper.listener;

import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import io.lumine.mythic.bukkit.events.MythicMobSpawnEvent;
import net.tumbleweed.paper.Tumbleweed;
import net.tumbleweed.paper.TumbleweedManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * 接入 MythicMobs 生命周期:
 *  - 实体生成 -> 注册为风滚草 (物理接管)
 *  - 实体死亡 -> 注销 (掉落由 Tumbleweed.yml 的 Drops 配置负责,不写代码)
 */
public class MythicListener implements Listener {

    private static final String MOB_ID = "Tumbleweed";

    private final TumbleweedManager manager;
    private final java.util.Random random = new java.util.Random();

    public MythicListener(TumbleweedManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSpawn(MythicMobSpawnEvent event) {
        if (!MOB_ID.equals(event.getMobType().getInternalName())) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity == null || entity.isDead()) {
            return;
        }

        // 随机尺寸 1~4 (原版 entityInit 随机;mcSize = 0.75 + size/8)
        Tumbleweed tw = new Tumbleweed(entity, 1 + random.nextInt(4));
        tw.setPersistent(false);

        // NoAI,物理完全由插件接管;关闭实体碰撞 (原版 getCollisionBox=null,不阻挡实体)
        if (entity instanceof org.bukkit.entity.LivingEntity living) {
            living.setAI(false);
            living.setCollidable(false);
        }

        manager.register(tw);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(MythicMobDeathEvent event) {
        if (!MOB_ID.equals(event.getMobType().getInternalName())) {
            return;
        }
        Tumbleweed tw = manager.get(event.getEntity());
        if (tw != null) {
            manager.remove(tw);
        }
        // 打碎战利品由 Tumbleweed.yml 的 Drops 配置负责 (MM 死亡结算),插件不写代码
    }

    /** Bukkit 兜底:MM 事件未触发时(如插件卸载/异常路径)也能清理模型与实体。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBukkitDeath(EntityDeathEvent event) {
        Entity e = event.getEntity();
        if (!(e instanceof LivingEntity)) {
            return;
        }
        Tumbleweed tw = manager.get(e);
        if (tw != null) {
            manager.remove(tw);
        }
    }
}
