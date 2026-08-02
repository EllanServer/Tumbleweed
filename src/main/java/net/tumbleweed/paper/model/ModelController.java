package net.tumbleweed.paper.model;

import net.tumbleweed.paper.Tumbleweed;

/**
 * 渲染门面:持有当前渲染后端 (ItemDisplay / ModelEngine),插件其余部分无感知。
 */
public final class ModelController {

    private static volatile TumbleweedRenderer renderer;

    private ModelController() {
    }

    public static TumbleweedRenderer renderer() {
        return renderer;
    }

    public static void setRenderer(TumbleweedRenderer renderer) {
        ModelController.renderer = renderer;
    }

    public static void attach(Tumbleweed tw) {
        if (renderer != null) {
            renderer.attach(tw);
        }
    }

    public static void sync(Tumbleweed tw) {
        if (renderer != null) {
            renderer.sync(tw);
        }
    }

    public static void detach(Tumbleweed tw) {
        if (renderer != null) {
            renderer.detach(tw);
        }
    }
}
