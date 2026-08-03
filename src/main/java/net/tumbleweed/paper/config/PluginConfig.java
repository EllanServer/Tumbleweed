package net.tumbleweed.paper.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 插件配置 (config.yml)。
 *
 * 设计原则:能用 MythicMobs 配置表达的功能一律交给 MM 配置
 * (怪物属性 / 践踏农田 / 掉落 / 骷髅射击),本配置只保留插件自身
 * 物理相关的少量参数,以及自然生成的概率/上限 (生成逻辑在插件内
 * 按原版 Spawner 实现,因 MM 的 RandomSpawner 表达不了干灌木限定等细节)。
 */
public class PluginConfig {

    private final FileConfiguration cfg;

    /** 风力倍率 (原版 mod 的 windMultiplier)。 */
    private double windMultiplier;

    /** 是否把 jar 内嵌的 MythicMobs 配置与 ModelEngine 蓝图导出到插件目录。 */
    private boolean autoExportResources;

    /** 远处降频:距最近玩家超过该距离 (格) 的风滚草物理降频;0 = 关闭。 */
    private int distantPhysicsDistance;

    /** 远处降频:每 N tick 才运行一次物理与渲染同步。 */
    private int distantPhysicsInterval;

    /** 是否使用 CraftEngine 的视锥+遮挡判定优化同步 (需安装 CraftEngine)。 */
    private boolean cullingEnabled;

    /** 自然生成 (原版 Spawner 逻辑,插件实现):是否启用。 */
    private boolean spawnerEnabled;

    /** 自然生成:每个候选区块的生成概率 (原版 spawnChance: 0.5)。 */
    private double spawnerChance;

    /** 自然生成:每玩家数量上限 (原版 maxPerPlayer: 8,按候选区比例)。 */
    private int spawnerMaxPerPlayer;

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
        cullingEnabled = cfg.getBoolean("performance.culling-enabled", true);
        spawnerEnabled = cfg.getBoolean("spawner.enabled", true);
        spawnerChance = cfg.getDouble("spawner.chance", 0.5);
        spawnerMaxPerPlayer = Math.max(1, cfg.getInt("spawner.max-per-player", 8));
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

    public boolean cullingEnabled() {
        return cullingEnabled;
    }

    public boolean spawnerEnabled() {
        return spawnerEnabled;
    }

    public double spawnerChance() {
        return spawnerChance;
    }

    public int spawnerMaxPerPlayer() {
        return spawnerMaxPerPlayer;
    }
}
