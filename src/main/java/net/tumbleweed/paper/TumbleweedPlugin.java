package net.tumbleweed.paper;

import net.tumbleweed.paper.config.PluginConfig;
import net.tumbleweed.paper.listener.InteractionListener;
import net.tumbleweed.paper.listener.MythicListener;
import net.tumbleweed.paper.model.CEFurnitureController;
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
 *  - MythicMobs    : 怪物定义 (Tumbleweed.yml)、践踏农田技能、掉落表、
 *                    骷髅射击 AI (Skeleton.yml)、自然生成器 (TumbleweedSpawner.yml)
 *  - CraftEngine   : 家具托管渲染 (tumbleweed 家具,ItemDisplay 元素 + interaction hitbox)
 *                    与玩家交互事件 (FurnitureHitEvent / FurnitureInteractEvent)
 *  - 本插件        : 风力物理、旋转/压扁、碰撞、寿命与淡出消失 (MM 无法配置的部分)
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
        if (getServer().getPluginManager().getPlugin("CraftEngine") == null) {
            getLogger().severe("未找到 CraftEngine!风滚草需要 CraftEngine 26.7 才能运行。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        CEFurnitureController.init(getLogger());

        // 导出附加资源 (MythicMobs 怪物/生成器配置 / CraftEngine 家具包)
        if (pluginConfig.isAutoExportResources()) {
            exportResources();
        }

        tumbleweedManager = new TumbleweedManager(this);
        tumbleweedManager.start();

        getServer().getPluginManager().registerEvents(new MythicListener(tumbleweedManager), this);
        getServer().getPluginManager().registerEvents(new InteractionListener(tumbleweedManager), this);

        getLogger().info("风滚草已启用 (MythicMobs 5.13 / CraftEngine 26.7 家具托管)。");
    }

    @Override
    public void onDisable() {
        if (tumbleweedManager != null) {
            tumbleweedManager.stop();
        }
        instance = null;
    }

    /**
     * 将 jar 内嵌的 MythicMobs 配置与 CraftEngine 家具资源导出到服务器插件目录。
     * (CE 资源包需 /ce reload all 生效)
     */
    private void exportResources() {
        export("/mythicmobs/Mobs/Tumbleweed.yml", "plugins/MythicMobs/Mobs/Tumbleweed.yml");
        export("/mythicmobs/Mobs/Skeleton.yml", "plugins/MythicMobs/Mobs/Skeleton.yml");
        export("/mythicmobs/Spawners/TumbleweedSpawner.yml", "plugins/MythicMobs/Spawners/TumbleweedSpawner.yml");
        export("/craftengine/pack.yml", "plugins/CraftEngine/resources/tumbleweed/pack.yml");
        export("/craftengine/configuration/items.yml",
                "plugins/CraftEngine/resources/tumbleweed/configuration/items.yml");
        export("/craftengine/configuration/furniture/tumbleweed.yml",
                "plugins/CraftEngine/resources/tumbleweed/configuration/furniture/tumbleweed.yml");
        export("/craftengine/resourcepack/assets/tumbleweed/models/item/tumbleweed.json",
                "plugins/CraftEngine/resources/tumbleweed/resourcepack/assets/tumbleweed/models/item/tumbleweed.json");
        export("/craftengine/resourcepack/assets/tumbleweed/textures/item/tumbleweed.png",
                "plugins/CraftEngine/resources/tumbleweed/resourcepack/assets/tumbleweed/textures/item/tumbleweed.png");
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
