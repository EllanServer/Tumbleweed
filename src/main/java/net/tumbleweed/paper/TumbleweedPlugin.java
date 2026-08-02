package net.tumbleweed.paper;

import net.tumbleweed.paper.config.PluginConfig;
import net.tumbleweed.paper.listener.InteractionListener;
import net.tumbleweed.paper.listener.MythicListener;
import net.tumbleweed.paper.model.CullingIntegration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

/**
 * 风滚草 - 原版 Tumbleweed mod 的 Paper 插件重写版。
 *
 * 架构分工 (能用配置表达的绝不用代码):
 *  - MythicMobs  : 怪物定义 (Tumbleweed.yml)、践踏农田技能、掉落表、
 *                  骷髅射击 AI (Skeleton.yml)
 *  - ModelEngine : 3D 模型渲染 (tumbleweed.bbmodel),插件驱动骨骼旋转/缩放
 *  - 本插件      : 风力物理、旋转/压扁、碰撞、寿命与淡出消失、自然生成
 *                  (原版 Spawner 逻辑,经 MM API 生成实体,保留 MM 配置生效)
 */
public class TumbleweedPlugin extends JavaPlugin {

    /** 原版全局风力 (Tumbleweed.windX/windZ):每 2 分钟各 50% 概率翻转符号。 */
    private static final float DEFAULT_WIND_X = 0.08f;
    private static final float DEFAULT_WIND_Z = -0.08f;

    private static TumbleweedPlugin instance;
    private static float windX = DEFAULT_WIND_X;
    private static float windZ = DEFAULT_WIND_Z;
    private static final java.util.Random windRandom = new java.util.Random();

    private PluginConfig pluginConfig;
    private TumbleweedManager tumbleweedManager;
    private TumbleweedSpawner spawner;

    public static TumbleweedPlugin getInstance() {
        return instance;
    }

    /** 当前全局风力 X 分量 (原版 Tumbleweed.windX)。 */
    public static float windX() {
        return windX;
    }

    /** 当前全局风力 Z 分量 (原版 Tumbleweed.windZ)。 */
    public static float windZ() {
        return windZ;
    }

    /** 原版:每 2 分钟对 X/Z 各做一次 50% 概率的符号翻转。 */
    public void rollWind() {
        if (windRandom.nextBoolean()) {
            windX = -windX;
        }
        if (windRandom.nextBoolean()) {
            windZ = -windZ;
        }
    }

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        pluginConfig = new PluginConfig(getConfig());
        pluginConfig.reload();

        // 前置插件检查
        if (getServer().getPluginManager().getPlugin("MythicMobs") == null) {
            getLogger().severe("未找到 MythicMobs!风滚草需要 MythicMobs 5.13 才能运行。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (getServer().getPluginManager().getPlugin("ModelEngine") == null) {
            getLogger().severe("未找到 ModelEngine!风滚草需要 ModelEngine 4 (R4) 才能运行。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 导出附加资源 (MythicMobs 怪物/生成器配置 / ModelEngine 蓝图)
        if (pluginConfig.isAutoExportResources()) {
            exportResources();
        }

        tumbleweedManager = new TumbleweedManager(this);
        // 自然生成:由插件按原版 Spawner 逻辑实现 (经 MythicMobs API 生成 MM 实体),
        // MM 的 RandomSpawner 表达不了干灌木限定/±5 偏移/20% 成双/动态上限等细节
        spawner = new TumbleweedSpawner(this);
        if (pluginConfig.spawnerEnabled()) {
            spawner.start();
        }
        // 可选:CraftEngine 可见性判定 (视锥+遮挡)。未安装 CE 时优雅降级为 96 格球半径降频。
        if (pluginConfig.cullingEnabled() && getServer().getPluginManager().getPlugin("CraftEngine") != null) {
            try {
                CullingIntegration culling = new CullingIntegration();
                if (culling.available()) {
                    tumbleweedManager.enableCulling(culling);
                    getLogger().info("已启用 CraftEngine 可见性判定 (性能优化)。");
                } else {
                    getLogger().warning("CraftEngine 已安装但可见性判定初始化失败,回退为距离降频。");
                }
            } catch (Throwable t) {
                getLogger().warning("CraftEngine 可见性判定不可用 (" + t.getMessage() + "),回退为距离降频。");
            }
        }
        tumbleweedManager.start();

        getServer().getPluginManager().registerEvents(new MythicListener(tumbleweedManager), this);
        getServer().getPluginManager().registerEvents(new InteractionListener(tumbleweedManager), this);

        getLogger().info("风滚草已启用 (MythicMobs 5.13 / ModelEngine R4 集成)。");
    }

    @Override
    public void onDisable() {
        if (spawner != null) {
            spawner.stop();
        }
        if (tumbleweedManager != null) {
            tumbleweedManager.stop();
        }
        instance = null;
    }

    /**
     * 将 jar 内嵌的 MythicMobs 配置与 ModelEngine 蓝图导出到服务器插件目录。
     * 注:生成器已由本插件按原版逻辑接管,不再导出 MM 的 TumbleweedSpawner.yml
     * (服务器上残留的旧文件需手动删除,否则会与插件生成器双重生成)。
     */
    private void exportResources() {
        export("/mythicmobs/Mobs/Tumbleweed.yml", "plugins/MythicMobs/Mobs/Tumbleweed.yml");
        export("/mythicmobs/Mobs/Skeleton.yml", "plugins/MythicMobs/Mobs/Skeleton.yml");
        export("/modelengine/blueprints/tumbleweed.bbmodel", "plugins/ModelEngine/blueprints/tumbleweed.bbmodel");
        export("/modelengine/textures/tumbleweed.png", "plugins/ModelEngine/textures/tumbleweed.png");
    }

    private void export(String resourcePath, String targetRelative) {
        Path target = new File(targetRelative).toPath();
        if (Files.exists(target)) {
            getLogger().info("资源已存在,跳过: " + target);
            return;
        }
        try (InputStream in = getResource(resourcePath)) {
            if (in == null) {
                getLogger().warning("资源缺失: " + resourcePath);
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            getLogger().info("已导出资源: " + target);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "导出资源失败: " + resourcePath, e);
        }
    }

    public PluginConfig pluginConfig() {
        return pluginConfig;
    }

    public TumbleweedManager tumbleweedManager() {
        return tumbleweedManager;
    }
}
