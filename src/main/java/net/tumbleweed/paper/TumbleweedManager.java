package net.tumbleweed.paper;

import net.tumbleweed.paper.model.ModelController;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理所有活跃风滚草:注册表 + 每 tick 物理调度 + 模型同步。
 */
public class TumbleweedManager {

    private final TumbleweedPlugin plugin;
    private final Map<UUID, Tumbleweed> tumbleweeds = new ConcurrentHashMap<>();
    private BukkitTask task;
    private int windTicks;   // 原版:每 2 分钟翻转一次风向

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
    }

    private void tick() {
        // 原版 CommonEventHandler:每 2*60*20 tick 翻转一次风向
        if (++windTicks >= 2 * 60 * 20) {
            windTicks = 0;
            plugin.rollWind();
        }

        Iterator<Map.Entry<UUID, Tumbleweed>> it = tumbleweeds.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Tumbleweed> entry = it.next();
            Tumbleweed tw = entry.getValue();
            if (tw.entity().isDead() || !tw.entity().isValid()) {
                ModelController.detach(tw.entity());
                it.remove();
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

    /** 注册新的风滚草 (由 MythicListener 在 MM 实体生成后调用)。 */
    public void register(Tumbleweed tw) {
        if (tumbleweeds.putIfAbsent(tw.entity().getUniqueId(), tw) == null) {
            ModelController.attach(tw.entity());
        }
    }

    /** 移除并清理 (死亡 / 淡出结束 / 超出范围)。 */
    public void remove(Tumbleweed tw) {
        Tumbleweed removed = tumbleweeds.remove(tw.entity().getUniqueId());
        if (removed != null) {
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
