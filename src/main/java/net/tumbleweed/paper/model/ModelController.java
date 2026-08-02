package net.tumbleweed.paper.model;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.bone.Bone;
import net.tumbleweed.paper.Tumbleweed;
import net.tumbleweed.paper.TumbleweedPlugin;
import org.bukkit.entity.Entity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * ModelEngine 集成:把风滚草的旋转/缩放状态同步到 tumbleweed.bbmodel 的 root 骨骼。
 *
 * ModelEngine 只做渲染 (bbmodel 为 4 个互插的薄板),碰撞与物理完全由插件驱动,
 * 与原版 "实体 + 客户端模型" 的架构一致。
 */
public class ModelController {

    private static final String MODEL_ID = "tumbleweed";
    private static final String ROOT_BONE = "root";

    @SuppressWarnings("unused")
    private final TumbleweedPlugin plugin;

    public ModelController(TumbleweedPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        // 预留:注册模型加载完毕回调等
    }

    public void shutdown() {
        // 模型随实体清理
    }

    /** 为风滚草附加模型。 */
    public void attach(Tumbleweed tw) {
        Entity entity = tw.entity();
        try {
            ActiveModel model = ModelEngineAPI.createModel(entity, MODEL_ID);
            tw.setModel(model);
        } catch (Exception e) {
            // 模型缺失时降级:实体仍可正常物理滚动,只是没有 3D 外观
            TumbleweedPlugin.getInstance().getLogger().warning(
                    "无法为风滚草附加 ModelEngine 模型 " + MODEL_ID + ": " + e.getMessage());
        }
    }

    /** 每 tick 同步旋转与缩放。 */
    public void sync(Tumbleweed tw) {
        ActiveModel model = tw.getModel();
        if (model == null) {
            return;
        }
        Bone root = model.getBone(ROOT_BONE);
        if (root == null) {
            return;
        }

        // 旋转:复刻原版 quat * rotOffset (随机初始朝向)
        Quaternionf q = new Quaternionf(tw.rotation().quat);
        q.rotateX((float) Math.toRadians(tw.rotOffsetX()));
        q.rotateY((float) Math.toRadians(tw.rotOffsetY()));
        q.rotateZ((float) Math.toRadians(tw.rotOffsetZ()));
        root.setRotation(q);

        // 缩放:整体 size + 落地压扁 (stretch 作用于 Y)
        float size = tw.modelScale();
        float stretch = tw.rotation().stretch;
        root.setScale(new Vector3f(size * (2f - stretch), size * stretch, size * (2f - stretch)));
    }

    /** 卸载模型。 */
    public void detach(Entity entity) {
        try {
            ModelEngineAPI.getModelManager().removeModel(entity);
        } catch (Exception ignored) {
            // 实体可能已卸载
        }
    }
}
