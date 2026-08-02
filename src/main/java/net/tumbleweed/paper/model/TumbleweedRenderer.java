package net.tumbleweed.paper.model;

import net.tumbleweed.paper.Tumbleweed;

/**
 * 渲染后端抽象,插件其余部分无感知。
 *
 * 两种实现,由 RendererFactory 按配置与已装插件选择:
 *  - ItemDisplayRenderer : craft-engine 物品模型 + ItemDisplay 实体 (默认,性能更好)
 *  - ModelEngineRenderer : ModelEngine R4 骨骼模型 (兼容回退)
 */
public interface TumbleweedRenderer {

    /** 渲染器名称 (日志用)。 */
    String name();

    /** 为风滚草附加渲染体 (幂等)。 */
    void attach(Tumbleweed tw);

    /** 每 tick 同步旋转 / 缩放 / 淡出状态。 */
    void sync(Tumbleweed tw);

    /** 移除渲染体。 */
    void detach(Tumbleweed tw);
}
