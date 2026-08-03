package net.tumbleweed.paper.model;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import com.ticxo.modelengine.api.model.bone.ManualAnimator;
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
 *    blueprint root origin 保持 [0,0,0] (BB group origin 的 ME 解释有歧义,可能把
 *    模型整体平移导致悬浮), 改为运行时 pivot: 主线程物理 tick 调用
 *    {@link #updatePivot(Tumbleweed)} 设置 root.setPivotLocation(实体位置 + 0.3*mcSize),
 *    每次写入全新 Location 后不再改动, ME 异步线程只读该引用 —— 无跨线程竞争。
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
 *    这里 attach 时注册 {@link ModeledEntity.Phase#PRE_MODEL_RENDER} tick 任务,
 *    仅做 destroy 兜底 + root 骨骼懒加载 + 一次性挂载 {@link ManualAnimator};
 *    变换写入由 {@link RenderSync#animate} 在骨骼 tick 内完成 (identity 重置之后,
 *    see RenderSync 注释),回调在 ME 异步线程执行,读取 Tumbleweed 主线程物理后写入的
 *    volatile 渲染快照 (零分配中转),惰性同步到 root 骨骼。主线程零渲染开销,
 *    且与 ME 更新同线程无竞争。
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

    /**
     * 每实体渲染同步闭包注册表 (entityId -> RenderSync):
     * 主线程 attach 注册、updatePivot/detach 读取与移除, 异步线程不触碰 —— 普通 HashMap 即可。
     */
    private static final java.util.Map<java.util.UUID, RenderSync> SYNC_BY_ENTITY = new java.util.HashMap<>();

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
                // 蓝图缺失/未加载: 此时 base 实体隐身 (ApplyInvisibility), 玩家完全看不见 ——
                // 曾是无日志静默盲区, 特此记录, 便于排查"生成了但看不见"
                java.util.logging.Logger.getLogger("Tumbleweed-ME")
                        .warning("模型蓝图未加载: " + MODEL_ID
                                + " (检查 plugins/ModelEngine/blueprints/" + MODEL_ID + ".bbmodel 是否存在,"
                                + " 并确认 /me reload 完成)");
                return;
            }
            modeled.addModel(model, true);
            modeled.setBaseEntityVisible(false);
            // 渲染同步挂到 ME 异步线程的 PRE_MODEL_RENDER 阶段 (骨骼 tick 之后、打包发送之前):
            // 该任务只负责 destroy 兜底 + root 骨骼懒加载 + 一次性挂载 ManualAnimator,
            // 实际变换写入由 ManualAnimator.animate() 在骨骼 tick 内完成 (见 RenderSync 注释)
            RenderSync sync = new RenderSync(tw, model, modeled);
            SYNC_BY_ENTITY.put(entity.getUniqueId(), sync);
            modeled.registerTickTask(ModeledEntity.Phase.PRE_MODEL_RENDER, (Consumer<ModeledEntity>) ignored -> sync.lazyInit());
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
        SYNC_BY_ENTITY.remove(entity.getUniqueId());
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

    /** 原版轴心比例: 模型旋转/缩放围绕实体 0.3 倍高度处 (RenderTumbleweed.translate(0, h*0.3, 0))。 */
    public static float pivotHeight(Tumbleweed tw) {
        return 0.3f * (float) tw.mcSize();
    }

    /**
     * 主线程 (物理 tick) 更新 root 骨骼的旋转/缩放轴心到实体上方 0.3 倍高处。
     * 每次写入全新 Location 后不再改动, ME 异步线程只读引用, 无跨线程竞争。
     * 性能: 每个物理 tick 一次 Location 分配 (降频后 ~5 次/秒/实体), 可忽略。
     */
    public static void updatePivot(Tumbleweed tw) {
        Entity entity = tw.entity();
        if (entity == null || !entity.isValid()) {
            return;
        }
        // 通过 attach 时注册的 RenderSync 获取 root 引用 (懒加载后持有), 避免重复链式查找
        RenderSync sync = RenderSync.of(tw);
        if (sync != null) {
            sync.setPivot(entity.getLocation().add(0, pivotHeight(tw), 0));
        }
    }

    /**
     * 每实体渲染同步闭包:通过 ModelBone.setManualAnimator 挂到骨骼 tick 管线,
     * 由 ME 在每 tick 骨骼更新时调用 {@link #animate(ModelBone)}。
     *
     * 为什么不用 tick 任务直接写骨骼 (原实现 PRE_MODEL_TICK):
     *  反编译 ModelBoneImpl.tick() 确认 —— 每 tick 开头执行 localTransform.identity()
     *  重置,再从 blueprint + 动画系统重建本地变换; PRE_MODEL_TICK 在骨骼 tick 之前,
     *  写入的 setLeftQuaternion/setScale 被 identity() 逐 tick 清空 → 模型永不旋转。
     *  ManualAnimator.animate() 是官方"手动动画"钩子: identity() 重置之后、
     *  动画系统 (updateBone) 之后、渲染打包之前调用, 写入必然生效;
     *  挂载后该骨骼由我们完全接管 (跳过动画系统)。
     *
     * 所有状态 (lastRot/lastScale/hasLast/root) 仅 ME 异步线程访问,无需同步;
     * 主线程只写 volatile 快照 (renderRotX..renderRotW / renderScaleX..Z) 与 pivotLocation 新引用。
     */
    static final class RenderSync implements ManualAnimator {

        private final Tumbleweed tw;
        private final ActiveModel model;
        private final ModeledEntity modeled; // destroy 兜底用
        private ModelBone root; // 懒加载:addModel 后骨骼可能延迟生成,首次 tick 时重试获取
        // 惰性检测:旋转/缩放未变化 (静止/降频) 时跳过 set,省模型同步包
        private final Quaternionf lastRot = new Quaternionf();
        private final float[] lastScale = new float[]{1f, 1f, 1f};
        private boolean hasLast;
        // 主线程每物理 tick 写入的轴心位置 (全新建对象,写后不改), 异步线程惰性应用
        private volatile org.bukkit.Location pivotLocation;
        private org.bukkit.Location lastAppliedPivot;
        private boolean loggedRootMissing;

        RenderSync(Tumbleweed tw, ActiveModel model, ModeledEntity modeled) {
            this.tw = tw;
            this.model = model;
            this.modeled = modeled;
        }

        static RenderSync of(Tumbleweed tw) {
            return SYNC_BY_ENTITY.get(tw.entity().getUniqueId());
        }

        static void unregister(Tumbleweed tw) {
            SYNC_BY_ENTITY.remove(tw.entity().getUniqueId());
        }

        /** 主线程: 更新旋转/缩放轴心 (实体上方 0.3 倍高处, 对齐原版 translate)。 */
        void setPivot(org.bukkit.Location location) {
            this.pivotLocation = location;
        }

        /**
         * PRE_MODEL_RENDER tick 任务 (每 tick, ME 异步线程):
         * destroy 兜底 + root 骨骼懒加载 + 一次性挂载 ManualAnimator。
         * 挂载后实际写入全部走 {@link #animate(ModelBone)}。
         */
        void lazyInit() {
            // 实体死亡但清理未及时执行时,立即销毁模型避免残留
            if (tw.entity() == null || !tw.entity().isValid() || tw.entity().isDead()) {
                if (!modeled.isDestroyed()) {
                    modeled.destroy();
                }
                return;
            }
            if (root == null) {
                Map<String, ModelBone> bones = model.getBones();
                if (bones != null) {
                    root = bones.get(ROOT_BONE);
                }
                if (root == null) {
                    // 骨骼名为 "root" 的 group 缺失 (bbmodel outliner 顶层名必须是 root) ——
                    // 静默重试会让"模型不旋转"无法排查, 特此记录一次
                    if (!loggedRootMissing) {
                        loggedRootMissing = true;
                        java.util.logging.Logger.getLogger("Tumbleweed-ME")
                                .warning("blueprint 缺少名为 \"" + ROOT_BONE + "\" 的根骨骼, 模型旋转/缩放将不生效");
                    }
                    return; // 骨骼尚未生成,下个 tick 重试
                }
                // ME 默认 hasGlobalRotation=false,需开启才能让 leftQuaternion 作为全局(世界空间)旋转生效
                root.setHasGlobalRotation(true);
                // 挂载手动动画: 之后每 tick 由 ME 在骨骼 tick 内调用 animate(this),
                // 该骨骼跳过动画系统, 变换完全由我们写入 (identity 重置之后, 写入必生效)
                root.setManualAnimator(this);
            }
        }

        /** 骨骼 tick 内 (identity 重置之后) 由 ME 调用:读取 volatile 快照惰性写入旋转/缩放/轴心。 */
        @Override
        public boolean applyBoneDefaultLocal() {
            // 应用 blueprint 默认位置/旋转后再由 animate 覆盖 (root 默认即 identity, 无实际影响)
            return true;
        }

        @Override
        public void animate(ModelBone bone) {
            try {
                org.bukkit.Location pivot = pivotLocation;
                if (pivot != null && pivot != lastAppliedPivot) {
                    // 轴心随实体移动每物理 tick 更新, 惰性应用
                    bone.setPivotLocation(pivot);
                    lastAppliedPivot = pivot;
                }
                float rx = tw.renderRotX, ry = tw.renderRotY, rz = tw.renderRotZ, rw = tw.renderRotW;
                float sx = tw.renderScaleX, sy = tw.renderScaleY, sz = tw.renderScaleZ;
                if (!hasLast || lastRot.x != rx || lastRot.y != ry || lastRot.z != rz || lastRot.w != rw) {
                    lastRot.set(rx, ry, rz, rw);
                    // Transform.setLeftQuaternion 内部复制四元数,复用 lastRot 安全
                    bone.getLocalTransform().setLeftQuaternion(lastRot);
                }
                if (!hasLast || lastScale[0] != sx || lastScale[1] != sy || lastScale[2] != sz) {
                    lastScale[0] = sx;
                    lastScale[1] = sy;
                    lastScale[2] = sz;
                    // 骨骼本地缩放 (围绕 root pivot = 原版 0.3h 轴心), 替代 ActiveModel.setScale
                    bone.getLocalTransform().setScale(new Vector3f(sx, sy, sz));
                }
                hasLast = true;
            } catch (Exception e) {
                // ME 异步回调上下文,避免异常逃逸导致 ME 线程池中断
            }
        }
    }
}
