package net.tumbleweed.paper.model;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import com.ticxo.modelengine.api.model.bone.ModelBone;
import org.bukkit.entity.Entity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Optional;

/**
 * ModelEngine (R4.1.1) 集成:把风滚草的旋转/缩放状态同步到 tumbleweed.bbmodel 的 root 骨骼。
 *
 * R4 API:
 *  - ModelEngineAPI.createModeledEntity(Entity)     -> ModeledEntity
 *  - ModelEngineAPI.createActiveModel(String id)    -> ActiveModel (按 blueprint 实例化)
 *  - ModeledEntity.addModel(ActiveModel, boolean)   -> Optional&lt;ActiveModel&gt;
 *  - ActiveModel.setScale(Vector3fc) / getBones()
 *  - ModelBone.getLocalTransform().setLeftQuaternion(Quaternionf)
 *
 * ModelEngine 只做渲染 (bbmodel 为互插薄板),碰撞与物理完全由插件驱动,
 * 因此 base 实体隐藏,模型根骨骼每 tick 由插件旋转/缩放。
 */
public final class ModelController {

    public static final String MODEL_ID = "tumbleweed";
    public static final String ROOT_BONE = "root";

    private ModelController() {
    }

    /** 为实体附加风滚草模型 (幂等:已附加则跳过)。 */
    public static void attach(Entity entity) {
        if (entity == null || !entity.isValid() || ModelEngineAPI.getModeledEntity(entity) != null) {
            return;
        }
        try {
            ModeledEntity modeled = ModelEngineAPI.createModeledEntity(entity);
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

    /** 每 tick 同步旋转与缩放。 */
    public static void sync(Entity entity, Quaternionf rotation, float scaleX, float scaleY, float scaleZ) {
        if (entity == null || !entity.isValid()) {
            return;
        }
        ModeledEntity modeled = ModelEngineAPI.getModeledEntity(entity);
        if (modeled == null) {
            return;
        }
        Optional<ActiveModel> opt = modeled.getModel(MODEL_ID);
        if (opt.isEmpty()) {
            return;
        }
        ActiveModel model = opt.get();
        try {
            model.setScale(new Vector3f(scaleX, scaleY, scaleZ));
            Map<String, ModelBone> bones = model.getBones();
            if (bones != null) {
                ModelBone root = bones.get(ROOT_BONE);
                if (root != null && rotation != null) {
                    root.getLocalTransform().setLeftQuaternion(rotation);
                }
            }
        } catch (Exception e) {
            // 渲染同步失败不影响物理
        }
    }

    /** 移除实体上的模型。 */
    public static void detach(Entity entity) {
        if (entity == null) {
            return;
        }
        try {
            ModeledEntity modeled = ModelEngineAPI.getModeledEntity(entity);
            if (modeled != null) {
                modeled.removeModel(MODEL_ID);
                modeled.destroy();
            }
        } catch (Exception e) {
            // 忽略清理异常
        }
    }
}
