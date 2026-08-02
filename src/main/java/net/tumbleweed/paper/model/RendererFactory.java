package net.tumbleweed.paper.model;

import org.bukkit.Bukkit;

import java.util.logging.Logger;

/**
 * 渲染后端选择:
 *  - craftengine : 有 CraftEngine 插件 -> ItemDisplay + craft-engine 物品模型
 *  - modelengine : 有 ModelEngine 插件 -> 骨骼模型 (兼容回退)
 *  - auto        : CraftEngine 优先,其次 ModelEngine
 *
 * 返回 null 表示没有任何可用的渲染后端。
 */
public final class RendererFactory {

    private RendererFactory() {
    }

    public static TumbleweedRenderer create(String mode, Logger logger) {
        boolean hasCraftEngine = Bukkit.getPluginManager().getPlugin("CraftEngine") != null;
        boolean hasModelEngine = Bukkit.getPluginManager().getPlugin("ModelEngine") != null;

        switch (mode == null ? "auto" : mode.toLowerCase()) {
            case "craftengine":
                if (hasCraftEngine) {
                    return new ItemDisplayRenderer(logger);
                }
                logger.warning("配置要求 craftengine 渲染器,但服务器未安装 CraftEngine。");
                return null;
            case "modelengine":
                if (hasModelEngine) {
                    return new ModelEngineRenderer();
                }
                logger.warning("配置要求 modelengine 渲染器,但服务器未安装 ModelEngine。");
                return null;
            case "auto":
            default:
                if (hasCraftEngine) {
                    return new ItemDisplayRenderer(logger);
                }
                if (hasModelEngine) {
                    return new ModelEngineRenderer();
                }
                logger.severe("未找到 CraftEngine 或 ModelEngine,风滚草无法渲染。");
                return null;
        }
    }
}
