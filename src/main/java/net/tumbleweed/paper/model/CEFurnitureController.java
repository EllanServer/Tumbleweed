package net.tumbleweed.paper.model;

import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.tumbleweed.paper.Tumbleweed;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * craft-engine 家具渲染/交互托管 (默认且唯一后端)。
 *
 * 每个风滚草对应一个 CE 家具 (tumbleweed:tumbleweed,ItemDisplay 元素 + interaction hitbox):
 *  - 显示:CE 托管 ItemDisplay 的追踪/剔除/网络同步 (EntityCulling 体系)
 *  - 交互:玩家打击/右击家具由 CE 拦截并派发 FurnitureHitEvent / FurnitureInteractEvent
 *  - 本类只负责:创建家具 (place)、跟随物理移动 (moveTo,带动 hitbox 同步)、
 *    每 tick 变换矩阵 (围绕球心旋转/压扁/淡出缩放,与原版渲染一致)
 *
 * 变换矩阵 (与原版 GlStateManager 顺序一致):
 *   M = T(0, h, 0) * T(0.5,0.5,0.5) * R * S * T(-0.5,-0.5,-0.5)
 *   模型 16 格空间球心在 (8,8,8) -> 0.5 格:先把球心移到原点,缩放 + 旋转
 *   围绕球心,再抬到脚底上方 h = mcSize/2 (与碰撞箱同高)。
 */
public final class CEFurnitureController {

    /** craft-engine 家具 ID (由插件导出的 configuration/furniture/tumbleweed.yml 定义)。 */
    public static final String FURNITURE_ID = "tumbleweed:tumbleweed";

    /** 模型球心在 16 格空间中的位置 (8/16)。 */
    private static final float MODEL_CENTER = 0.5f;

    /** 每实体当前变换缓存 (仅用于判断是否需重发,变换变化时才同步)。 */
    private static final Map<UUID, float[]> LAST_TRANSFORM = new ConcurrentHashMap<>();

    private static Logger logger;

    private CEFurnitureController() {
    }

    public static void init(Logger pluginLogger) {
        logger = pluginLogger;
    }

    /** 创建家具并挂到风滚草上 (Pig 位置)。未 /ce reload 时返回 false,下次 attach 重试。 */
    public static boolean attach(Tumbleweed tw) {
        if (tw.furniture() != null) {
            return true;
        }
        Entity entity = tw.entity();
        if (entity == null || !entity.isValid()) {
            return false;
        }
        try {
            BukkitFurniture furniture = CraftEngineFurniture.place(
                    entity.getLocation(), Key.from(FURNITURE_ID));
            if (furniture == null) {
                logger.warning("craft-engine 家具未找到: " + FURNITURE_ID
                        + " (首次安装请执行 /ce reload all 后重试)");
                return false;
            }
            tw.setFurniture(furniture);
            return true;
        } catch (Throwable t) {
            logger.log(Level.WARNING, "创建 craft-engine 家具失败: " + FURNITURE_ID, t);
            return false;
        }
    }

    /**
     * 同步家具位置与变换。
     *
     * @param moving   本 tick 物理是否发生移动 (移动时 moveTo 带动 hitbox,静止时仅变换)
     */
    public static void sync(Tumbleweed tw, boolean moving) {
        BukkitFurniture furniture = tw.furniture();
        if (furniture == null) {
            return;
        }
        Entity entity = tw.entity();
        if (entity == null || !entity.isValid()) {
            return;
        }

        if (moving) {
            WorldPosition pos = new WorldPosition(
                    BukkitAdaptor.adapt(entity.getWorld()), entity.getLocation().getX(),
                    entity.getLocation().getY(), entity.getLocation().getZ());
            // force=true:跳过 CE 碰撞检查 (风滚草自有方块碰撞);isMoving 锁竞态时静默跳过,
            // 风滚草速度慢,丢失 1 tick 无感知
            furniture.moveTo(pos, true);
        }

        // 变换矩阵:压扁 + 淡出缩放,围绕球心旋转
        float fade = Math.max(tw.alpha(), 0.02f);
        float scaleX = tw.renderScaleX() * fade;
        float scaleY = tw.renderScaleY() * fade;
        float scaleZ = tw.renderScaleZ() * fade;
        float h = (float) (tw.mcSize() / 2.0);

        UUID id = entity.getUniqueId();
        float[] last = LAST_TRANSFORM.get(id);
        // 旋转每 tick 都在变,不做缓存;scale 与 h 变化时才同步
        if (last != null && Math.abs(last[0] - scaleX) < 1e-4f
                && Math.abs(last[1] - scaleY) < 1e-4f
                && Math.abs(last[2] - scaleZ) < 1e-4f
                && Math.abs(last[3] - h) < 1e-4f) {
            return;
        }

        Entity base = furniture.baseEntity();
        if (!(base instanceof ItemDisplay display) || !display.isValid()) {
            return;
        }
        Matrix4f m = new Matrix4f()
                .translation(0f, h, 0f)
                .translate(MODEL_CENTER, MODEL_CENTER, MODEL_CENTER)
                .rotate(tw.rotation().quat)
                .scale(scaleX, scaleY, scaleZ)
                .translate(-MODEL_CENTER, -MODEL_CENTER, -MODEL_CENTER);
        display.setTransformationMatrix(m);
        LAST_TRANSFORM.put(id, new float[]{scaleX, scaleY, scaleZ, h});
    }

    /** 移除家具 (管理器注销风滚草时调用)。 */
    public static void detach(Tumbleweed tw) {
        Furniture furniture = tw.furniture();
        if (furniture != null) {
            try {
                CraftEngineFurniture.remove(furniture, false, false);
            } catch (Throwable t) {
                logger.log(Level.WARNING, "移除 craft-engine 家具失败", t);
            }
            tw.setFurniture(null);
        }
        LAST_TRANSFORM.remove(tw.entity().getUniqueId());
    }
}
