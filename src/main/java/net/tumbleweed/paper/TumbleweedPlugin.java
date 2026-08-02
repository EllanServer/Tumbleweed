package net.tumbleweed.paper;

import net.tumbleweed.paper.config.PluginConfig;
import net.tumbleweed.paper.listener.InteractionListener;
import net.tumbleweed.paper.listener.MythicListener;
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
 * 架构分工:
 *  - MythicMobs  : 定义风滚草怪物实体 (Tumbleweed.yml),处理实体生命周期事件
 *  - ModelEngine : 提供 3D 模型渲染 (tumbleweed.bbmodel),插件驱动骨骼旋转/缩放
 *  - 本插件      : 风力物理、生成器、骷髅射击 AI、农田践踏、战利品、淡出消失
 */
public class TumbleweedPlugin extends JavaPlugin {

    private static TumbleweedPlugin instance;

    private PluginConfig pluginConfig;
    private TumbleweedManager tumbleweedManager;
    private Spawner spawner;
    private SkeletonShooter skeletonShooter;
    private ModelController modelController;

    public static TumbleweedPlugin getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        pluginConfig = new PluginConfig(getConfig());
        pluginConfig.reload();

        // 前置插件检查
        if (getServer().getPluginManager().getPlugin("MythicMobs") == null) {
            getLogger().severe("未找到 MythicMobs!风滚草需要 MythicMobs 5.x 才能运行。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (getServer().getPluginManager().getPlugin("ModelEngine") == null) {
            getLogger().severe("未找到 ModelEngine!风滚草需要 ModelEngine 4 (R4) 才能运行。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 导出附加资源 (MythicMobs 怪物定义 / ModelEngine 蓝图)
        if (pluginConfig.isAutoExportResources()) {
            exportResources();
        }

        modelController = new ModelController(this);
        modelController.init();

        tumbleweedManager = new TumbleweedManager(this, modelController);
        tumbleweedManager.start();

        spawner = new Spawner(this, tumbleweedManager);
        spawner.start();

        skeletonShooter = new SkeletonShooter(this, tumbleweedManager);
        skeletonShooter.start();

        getServer().getPluginManager().registerEvents(new MythicListener(tumbleweedManager, pluginConfig), this);
        getServer().getPluginManager().registerEvents(new InteractionListener(tumbleweedManager), this);

        getLogger().info("风滚草已启用 (MythicMobs 5.9.2 / ModelEngine R4 集成)。");
    }

    @Override
    public void onDisable() {
        if (tumbleweedManager != null) {
            tumbleweedManager.stop();
        }
        if (spawner != null) {
            spawner.stop();
        }
        if (skeletonShooter != null) {
            skeletonShooter.stop();
        }
        if (modelController != null) {
            modelController.shutdown();
        }
        instance = null;
    }

    /**
     * 将 jar 内嵌的 MythicMobs 配置与 ModelEngine 蓝图导出到服务器插件目录。
     */
    private void exportResources() {
        export("/mythicmobs/Mobs/Tumbleweed.yml", "plugins/MythicMobs/Mobs/Tumbleweed.yml");
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
