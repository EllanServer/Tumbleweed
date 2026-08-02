package net.tumbleweed.paper.model;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import com.ticxo.modelengine.api.model.bone.ModelBone;
import net.tumbleweed.paper.Tumbleweed;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Optional;

/**
 * ModelEngine (R4.1.1) 渲染器 (兼容回退)。
 *
 * 把风滚草的旋转/缩放状态同步到 tumbleweed.bbmodel 的 root 骨骼:
 *  - ModelEngineAPI.createModeledEntity(Entity)  -> ModeledEntity
 *  - ModelEngineAPI.createActiveModel(String id) -> ActiveModel
 *  - ModeledEntity.addModel(ActiveModel, boolean) -> Optional&lt;ActiveModel&gt;
 *  - ModelBone.getLocalTransform().setLeftQuaternion(Quaternionf)
 *
 * ModelEngine 只做渲染 (bbmodel 为互插薄板),碰撞与物理完全由插件驱动,
 * 因此 base 实体隐藏,模型根骨骼每 tick 由插件旋转/缩放。
 */
public final class ModelEngineRenderer implements TumbleweedRenderer {

    public static final String MODEL_ID = "tumbleweed";
    public static final String ROOT_BONE = "root";

    @Override
    public String name() {
        return "modelengine(R4)";
    }

    @Override
    public void attach(Tumbleweed tw) {
        if (tw.entity() == null || !tw.entity().isValid()) {
            return;
        }
        try {
            // 幂等:已附加过则跳过
            if (ModelEngineAPI.getModeledEntity(tw.entity()) != null) {
                return;
            }
            ModeledEntity modeled = ModelEngineAPI.createModeledEntity(tw.entity());
            if (modeled == null) {
                return;
            }
            ActiveModel model = ModelEngineAPI.createActiveModel(MODEL_ID);
            if (model == null) {
                return;
            }
            modeled.addModel(model, true);
            modeled.setBaseEntityVisible(false);
        } catch (Exception e) {
            // 模型加载失败不应中断风滚草物理
        }
    }

    @Override
    public void sync(Tumbleweed tw) {
        if (tw.entity() == null || !tw.entity().isValid()) {
            return;
        }
        ModeledEntity modeled = ModelEngineAPI.getModeledEntity(tw.entity());
        if (modeled == null) {
            return;
        }
        Optional<ActiveModel> opt = modeled.getModel(MODEL_ID);
        if (opt.isEmpty()) {
            return;
        }
        ActiveModel model = opt.get();
        try {
            model.setScale(new Vector3f(tw.renderScaleX(), tw.renderScaleY(), tw.renderScaleZ()));
            Map<String, ModelBone> bones = model.getBones();
            if (bones != null) {
                ModelBone root = bones.get(ROOT_BONE);
                if (root != null) {
                    root.getLocalTransform().setLeftQuaternion(new Quaternionf(tw.rotation().quat));
                }
            }
        } catch (Exception e) {
            // 渲染同步失败不影响物理
        }
    }

    @Override
    public void detach(Tumbleweed tw) {
        if (tw.entity() == null) {
            return;
        }
        try {
            ModeledEntity modeled = ModelEngineAPI.getModeledEntity(tw.entity());
            if (modeled != null) {
                modeled.removeModel(MODEL_ID);
                modeled.destroy();
            }
        } catch (Exception e) {
            // 忽略清理异常
        }
    }
}
