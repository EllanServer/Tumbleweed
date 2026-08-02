package net.tumbleweed.paper.model;

import org.joml.Quaternionf;

/**
 * 旋转状态,复刻原版客户端字段 (quat / prevQuat / stretch / prevStretch)。
 * 由 Tumbleweed.tickRotation 每 tick 更新,CEFurnitureController 同步给 CE 家具。
 */
public class RotationState {

    /** 当前旋转四元数。 */
    public Quaternionf quat = new Quaternionf();

    /** 上一 tick 旋转四元数 (原版用于插值,CE 家具下直接使用当前值)。 */
    public Quaternionf prevQuat = new Quaternionf();

    /** 当前压扁系数 (1.0 正常)。 */
    public float stretch = 1f;

    /** 上一 tick 压扁系数。 */
    public float prevStretch = 1f;

    /**
     * 原版 preTickClient:
     * prevStretch = stretch;stretch 向 1 恢复 (stretch *= 1.2 后封顶)。
     */
    public void tickPre() {
        prevStretch = stretch;
        stretch *= 1.2f;
        if (stretch > 1f) {
            stretch = 1f;
        }
        prevQuat = new Quaternionf(quat);
    }
}
