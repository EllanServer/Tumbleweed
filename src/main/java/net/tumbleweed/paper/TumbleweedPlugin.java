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
 * 架构分工 (能用配置表达的绝不用代码):
 *  - MythicMobs  : 怪物定义 (Tumbleweed.yml)、践踏农田技能、掉落表、
 *                  骷髅射击 AI (Skeleton.yml)、自然生成器 (TumbleweedSpawner.yml)
 *  - ModelEngine : 3D 模型渲染 (tumbleweed.bbmodel),插件驱动骨骼旋转/缩放
 *  - 本插件      : 风力物理、旋转/压扁、碰撞、寿命与淡出消失 (MM 无法配置的部分)
 */
public class TumbleweedPlugin extends JavaPlugin {

    private static TumbleweedPlugin instance;

    private PluginConfig pluginConfig;
    private TumbleweedManager tumbleweedManager;

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
        tumbleweedManager.start();

        getServer().getPluginManager().registerEvents(new MythicListener(tumbleweedManager), this);
        getServer().getPluginManager().registerEvents(new InteractionListener(tumbleweedManager), this);

        getLogger().info("风滚草已启用 (MythicMobs 5.13 / ModelEngine R4 集成)。");
    }

    @Override
    public void onDisable() {
        if (tumbleweedManager != null) {
            tumbleweedManager.stop();
        }
        instance = null;
    }

    /**
     * 将 jar 内嵌的 MythicMobs 配置与 ModelEngine 蓝图导出到服务器插件目录。
     */
    private void exportResources() {
        export("/mythicmobs/Mobs/Tumbleweed.yml", "plugins/MythicMobs/Mobs/Tumbleweed.yml");
        export("/mythicmobs/Mobs/Skeleton.yml", "plugins/MythicMobs/Mobs/Skeleton.yml");
        export("/mythicmobs/Spawners/TumbleweedSpawner.yml", "plugins/MythicMobs/Spawners/TumbleweedSpawner.yml");
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
