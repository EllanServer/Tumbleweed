package net.tumbleweed.paper.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 插件配置 (config.yml)。
 *
 * 设计原则:能用 MythicMobs 配置表达的功能一律交给 MM 配置
 * (怪物属性 / 践踏农田 / 掉落 / 骷髅射击 / 自然生成),本配置
 * 只保留插件自身物理相关的少量参数。
 */
public class PluginConfig {

    private final FileConfiguration cfg;

    /** 风力倍率 (原版 mod 的 windMultiplier)。 */
    private double windMultiplier;

    /** 渲染后端:auto / craftengine / modelengine。 */
    private String renderer;

    /** 是否把 jar 内嵌的 MythicMobs 配置与 CraftEngine/ModelEngine 资源导出到插件目录。 */
    private boolean autoExportResources;

    public PluginConfig(FileConfiguration cfg) {
        this.cfg = cfg;
    }

    public void reload() {
        windMultiplier = cfg.getDouble("wind-multiplier", 1.0);
        if (windMultiplier <= 0 || windMultiplier > 10) {
            windMultiplier = 1.0;
        }
        renderer = cfg.getString("renderer", "auto");
        if (!"auto".equalsIgnoreCase(renderer) && !"craftengine".equalsIgnoreCase(renderer)
                && !"modelengine".equalsIgnoreCase(renderer)) {
            renderer = "auto";
        }
        autoExportResources = cfg.getBoolean("auto-export-resources", true);
    }

    public double windMultiplier() {
        return windMultiplier;
    }

    public String renderer() {
        return renderer;
    }

    public boolean isAutoExportResources() {
        return autoExportResources;
    }
}
