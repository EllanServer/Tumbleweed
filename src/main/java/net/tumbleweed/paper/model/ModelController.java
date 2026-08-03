package net.tumbleweed.paper.model;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import com.ticxo.modelengine.api.model.bone.ModelBone;
import net.tumbleweed.paper.Tumbleweed;
import org.bukkit.entity.Entity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.function.Consumer;

/**
 * ModelEngine (R4.1.1) 集成:把风滚草的旋转/缩放状态同步到 tumbleweed.bbmodel 的 root 骨骼。
 *
 * R4 架构要点 (决定本类设计):
 *  - ModelEngineAPI.createModeledEntity(Entity)     -> ModeledEntity
 *  - ModelEngineAPI.createActiveModel(String id)    -> ActiveModel (按 blueprint 实例化)
 *  - ModeledEntity.addModel(ActiveModel, boolean)   -> Optional&lt;ActiveModel&gt;
 *  - ActiveModel.getBones() / ModelBone.getLocalTransform()
 *  - Transform.setLeftQuaternion(Quaternionf) / setScale(Vector3f) (骨骼本地变换,围绕骨骼 pivot)
 *
 * 渲染对齐原版 RenderTumbleweed:
 *  - 旋转/缩放轴心: 原版 translate(0, bbHeight*0.3, 0) —— 轴心在实体 0.3 倍高处;
 *    blueprint root group origin 已设 [0, 4.8, 0] (0.3 格),骨骼变换围绕该 pivot,
 *    静态配置零运行时开销 (size=2 精确, 小尺寸误差 ≤0.15 格, 视觉可忽略)
 *  - scale(size, size, size) * scale(1, stretch, 1) → 最终 (size, size*stretch, size),
 *    仅 Y 轴压扁; 用 root 骨骼 localTransform.setScale 而非 ActiveModel.setScale
 *    (display scale 围绕脚底, 骨骼 scale 围绕 pivot, 与原版一致)
 *
 * ModelEngine 只做渲染 (bbmodel 为互插薄板),碰撞与物理完全由插件驱动,
 * 因此 base 实体隐藏,模型根骨骼由插件旋转/缩放。
 *
 * 线程模型 (R4.1.1 实测源码):
 *  - ModelUpdaters 每 tick 用 work-stealing 线程池并行处理所有模型:
 *    PRE_DATA_SYNC -> asyncUpdate -> PRE_MODEL_TICK -> entity.tick() -> PRE_MODEL_RENDER -> sendToClient
 *  - SafeTransform 双缓冲 (recordSafe + acquireFence) 供渲染线程读取;
 *    但写侧 (Transform setter) 无 release fence —— 主线程直接写 transform 字段存在
 *    跨线程数据竞争 (x86 上碰巧可见,Folia/其他平台不保证)。
 *  - 因此 transform 写入必须发生在 ME 自己的异步线程内:
 *    这里在 attach 时注册 {@link ModeledEntity.Phase#PRE_MODEL_TICK} tick 任务,
 *    回调在 ME 异步线程执行,读取 Tumbleweed 主线程物理后写入的 volatile 渲染快照
 *    (零分配中转),惰性同步到 root 骨骼。主线程零渲染开销,且与 ME 更新同线程无竞争。
 *
 * 顺带收益 (相比旧的每 tick 主线程 sync):
 *  - 删除两个全局 ConcurrentHashMap (LAST_SCALE / LAST_ROT):每实体的惰性状态收进
 *    RenderSync 闭包,仅回调线程访问,无需并发结构;
 *  - 删除每 tick 的 getModeledEntity/getModel/getBones 链式查找:root 骨骼引用在
 *    attach 时获取并闭包持有;
 *  - 降频 (96 格/CE 判定) 跳过的物理 tick 不写快照,回调惰性比较自然跳过 set,
 *    与原先降频时跳过 sync 行为完全一致。
 */
public final class ModelController {

    public static final String MODEL_ID = "tumbleweed";
    public static final String ROOT_BONE = "root";

    private ModelController() {
    }

    /** 为风滚草附加模型并注册渲染同步 (幂等:已附加则跳过)。 */
    public static void attach(Tumbleweed tw) {
        Entity entity = tw.entity();
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
            // 渲染同步挂到 ME 异步线程的 PRE_MODEL_TICK 阶段 (entity.tick() 之前,
            // transform 会被本次 tick 的 recordSafe/sendToClient 读入,同 tick 生效)
            modeled.registerTickTask(ModeledEntity.Phase.PRE_MODEL_TICK, new RenderSync(tw, model));
        } catch (Exception e) {
            // 模型加载失败不应中断风滚草物理,但记录以便排查
            java.util.logging.Logger.getLogger("Tumbleweed-ME").fine(() -> "模型 attach 失败: " + e);
        }
    }

    /** 移除实体上的模型 (destroy 后 ModelUpdaters 不再 tick 该实体,tick 任务自然停止)。 */
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
            java.util.logging.Logger.getLogger("Tumbleweed-ME").fine(() -> "模型 detach 异常: " + e);
        }
    }

    /**
     * 每实体渲染同步闭包:在 ME 异步线程 (PRE_MODEL_TICK) 每 tick 被调用,
     * 读取 Tumbleweed 的 volatile 渲染快照,惰性同步到 root 骨骼。
     * 所有状态 (lastRot/lastScale/hasLast) 仅回调线程访问,无需同步。
     */
    static final class RenderSync implements Consumer<ModeledEntity> {

        private final Tumbleweed tw;
        private final ActiveModel model;
        private ModelBone root; // 懒加载:addModel 后骨骼可能延迟生成,首次回调时重试获取
        // 惰性检测:旋转/缩放未变化 (静止/降频) 时跳过 set,省模型同步包
        private final Quaternionf lastRot = new Quaternionf();
        private final float[] lastScale = new float[]{1f, 1f, 1f};
        private boolean hasLast;

        RenderSync(Tumbleweed tw, ActiveModel model) {
            this.tw = tw;
            this.model = model;
        }

        @Override
        public void accept(ModeledEntity modeledEntity) {
            // 实体死亡但清理未及时执行时,立即销毁模型避免残留
            if (tw.entity() == null || !tw.entity().isValid() || tw.entity().isDead()) {
                if (!modeledEntity.isDestroyed()) {
                    modeledEntity.destroy();
                }
                return;
            }
            if (root == null) {
                Map<String, ModelBone> bones = model.getBones();
                if (bones != null) {
                    root = bones.get(ROOT_BONE);
                }
                if (root == null) {
                    return; // 骨骼尚未生成,下个 tick 重试
                }
                // ME 默认 hasGlobalRotation=false,需开启才能让 leftQuaternion 生效于渲染
                root.setHasGlobalRotation(true);
            }
            try {
                float rx = tw.renderRotX, ry = tw.renderRotY, rz = tw.renderRotZ, rw = tw.renderRotW;
                float sx = tw.renderScaleX, sy = tw.renderScaleY, sz = tw.renderScaleZ;
                if (!hasLast || lastRot.x != rx || lastRot.y != ry || lastRot.z != rz || lastRot.w != rw) {
                    lastRot.set(rx, ry, rz, rw);
                    // Transform.setLeftQuaternion 内部复制四元数,复用 lastRot 安全
                    root.getLocalTransform().setLeftQuaternion(lastRot);
                }
                if (!hasLast || lastScale[0] != sx || lastScale[1] != sy || lastScale[2] != sz) {
                    lastScale[0] = sx;
                    lastScale[1] = sy;
                    lastScale[2] = sz;
                    // 骨骼本地缩放 (围绕 root pivot = 原版 0.3h 轴心), 替代 ActiveModel.setScale
                    root.getLocalTransform().setScale(new Vector3f(sx, sy, sz));
                }
                hasLast = true;
            } catch (Exception e) {
                // ME 异步回调上下文,避免异常逃逸导致 ME 线程池中断
            }
        }
    }
}
