package net.tumbleweed.paper.model;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.tumbleweed.paper.Tumbleweed;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * craft-engine 渲染器 (默认)。
 *
 * 每个风滚草挂一个 ItemDisplay 实体,物品为 craft-engine 自定义物品
 * (tumbleweed:tumbleweed,模型由资源包替换),每 tick 只更新变换矩阵:
 *
 *   M = T(0, h, 0) * T(0.5,0.5,0.5) * R * S * T(-0.5,-0.5,-0.5)
 *
 * 模型 16 格空间球心在 (8,8,8) -> 0.5 格:先把球心移到原点,缩放 + 旋转
 * 围绕球心,再抬到脚底上方 h = mcSize/2 (与碰撞箱同高)。
 *
 * 淡出:Display 实体没有透明度 API,用整体 scale 线性缩小模拟原版 80 tick 渐隐。
 * 性能:ItemDisplay 变换走独立数据包,不重发物品,比 ModelEngine 骨骼同步更轻。
 */
public final class ItemDisplayRenderer implements TumbleweedRenderer {

    /** craft-engine 物品 ID (由插件导出的 items.yml 定义)。 */
    public static final String ITEM_ID = "tumbleweed:tumbleweed";

    /** 模型球心在 16 格空间中的位置 (8/16)。 */
    private static final float MODEL_CENTER = 0.5f;

    private final Logger logger;
    private final Map<UUID, ItemDisplay> displays = new ConcurrentHashMap<>();
    private ItemStack cachedItem;

    public ItemDisplayRenderer(Logger logger) {
        this.logger = logger;
    }

    /** 懒加载 craft-engine 物品;首次导出后未 /ce reload 时返回 null,后续 attach 自动重试。 */
    private ItemStack item() {
        if (cachedItem == null) {
            try {
                BukkitItemDefinition def = CraftEngineItems.byId(ITEM_ID);
                if (def != null) {
                    cachedItem = def.buildBukkitItem();
                    logger.info("craft-engine 物品已加载: " + ITEM_ID);
                } else {
                    logger.warning("craft-engine 物品未找到: " + ITEM_ID
                            + " (首次安装请执行 /ce reload all 后重启风滚草)");
                }
            } catch (Throwable t) {
                logger.log(Level.WARNING, "加载 craft-engine 物品失败: " + ITEM_ID, t);
            }
        }
        return cachedItem;
    }

    @Override
    public String name() {
        return "craftengine(ItemDisplay)";
    }

    @Override
    public void attach(Tumbleweed tw) {
        Entity entity = tw.entity();
        if (entity == null || !entity.isValid() || displays.containsKey(entity.getUniqueId())) {
            return;
        }
        ItemStack stack = item();
        if (stack == null) {
            return; // 物品未加载,下次 attach 重试
        }
        Location loc = entity.getLocation().clone();
        ItemDisplay display = entity.getWorld().spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(stack);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setInvulnerable(true);
            d.setSilent(true);
            d.setPersistent(false);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(0);
            d.setTransformationMatrix(new Matrix4f());
        });
        displays.put(entity.getUniqueId(), display);
        // 载体实体不可见 (渲染完全由 ItemDisplay 承担)
        entity.setInvisible(true);
    }

    @Override
    public void sync(Tumbleweed tw) {
        ItemDisplay display = displays.get(tw.entity().getUniqueId());
        if (display == null || !display.isValid()) {
            return;
        }
        Location entityLoc = tw.entity().getLocation();
        if (display.getLocation().distanceSquared(entityLoc) > 0.0001) {
            display.teleport(entityLoc);
        }

        // 淡出:整体 scale 缩小模拟透明度渐隐
        float fade = Math.max(tw.alpha(), 0.02f);
        float scaleX = tw.renderScaleX() * fade;
        float scaleY = tw.renderScaleY() * fade;
        float scaleZ = tw.renderScaleZ() * fade;
        float h = (float) (tw.mcSize() / 2.0);

        Matrix4f m = new Matrix4f()
                .translation(0f, h, 0f)
                .translate(MODEL_CENTER, MODEL_CENTER, MODEL_CENTER)
                .rotate(tw.rotation().quat)
                .scale(scaleX, scaleY, scaleZ)
                .translate(-MODEL_CENTER, -MODEL_CENTER, -MODEL_CENTER);
        display.setTransformationMatrix(m);
    }

    @Override
    public void detach(Tumbleweed tw) {
        ItemDisplay display = displays.remove(tw.entity().getUniqueId());
        if (display != null && display.isValid()) {
            display.remove();
        }
    }
}
