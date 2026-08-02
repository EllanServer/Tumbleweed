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

    /** 是否把 jar 内嵌的 MythicMobs 配置与 craft-engine 家具资源导出到插件目录。 */
    private boolean autoExportResources;

    /** 远处降频:距最近玩家超过该距离 (格) 的风滚草物理降频;0 = 关闭。 */
    private int distantPhysicsDistance;

    /** 远处降频:每 N tick 才运行一次物理与渲染同步。 */
    private int distantPhysicsInterval;

    public PluginConfig(FileConfiguration cfg) {
        this.cfg = cfg;
    }

    public void reload() {
        windMultiplier = cfg.getDouble("wind-multiplier", 1.0);
        if (windMultiplier <= 0 || windMultiplier > 10) {
            windMultiplier = 1.0;
        }
        autoExportResources = cfg.getBoolean("auto-export-resources", true);
        distantPhysicsDistance = Math.max(0, cfg.getInt("performance.distant-physics-distance", 96));
        distantPhysicsInterval = Math.max(1, cfg.getInt("performance.distant-physics-interval", 4));
    }

    public double windMultiplier() {
        return windMultiplier;
    }

    public boolean isAutoExportResources() {
        return autoExportResources;
    }

    public int distantPhysicsDistance() {
        return distantPhysicsDistance;
    }

    public int distantPhysicsInterval() {
        return distantPhysicsInterval;
    }
}
