package net.tumbleweed.paper.model;

import org.joml.Quaternionf;

/**
 * 旋转状态,复刻原版客户端字段 (quat / stretch / angular)。
 * 由 Tumbleweed.tickRotation 每 tick 更新,ModelController 同步给 ModelEngine。
 */
public class RotationState {

    /** 当前旋转四元数 (含原版 rotOffsetX/Y/Z 的初始随机偏移,每实体固定)。 */
    public final Quaternionf quat = new Quaternionf();

    /** 角速度 (弧度/tick,原版 1.20.1 tickClient: 有记忆 + 阻力衰减)。 */
    public float angularX;
    public float angularZ;

    /** 当前压扁系数 (1.0 正常,落地瞬间 < 1 压扁后恢复)。 */
    public float stretch = 1f;

    /**
     * 原版 preTickClient:stretch 向 1 恢复 (stretch *= 1.2 后封顶)。
     */
    public void tickPre() {
        stretch *= 1.2f;
        if (stretch > 1f) {
            stretch = 1f;
        }
    }
}
