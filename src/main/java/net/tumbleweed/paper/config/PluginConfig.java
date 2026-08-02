package net.tumbleweed.paper.config;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 插件配置 (config.yml),复刻原版 mod 的配置项并扩展战利品表。
 */
public class PluginConfig {

    private final FileConfiguration cfg;

    private boolean enableDrops;
    private double spawnChance;
    private int maxPerPlayer;
    private boolean damageCrops;
    private boolean dropOnlyByPlayer;
    private boolean autoExportResources;

    /** 生物群系过滤器:空 = 使用默认列表;条目支持 minecraft:desert 精确名或 #tag 引用 */
    private final List<String> biomeFilters = new ArrayList<>();
    private final Set<Material> spawnerBlocks = new LinkedHashSet<>();

    // 战利品权重
    private int weightCommon = 17;
    private int weightUncommon = 7;
    private int weightRare = 4;
    private int weightEpic = 1;
    private int weightMusicDisc = 1;

    private final List<Material> lootCommon = new ArrayList<>();
    private final List<Material> lootUncommon = new ArrayList<>();
    private final List<Material> lootRare = new ArrayList<>();
    private final List<Material> lootEpic = new ArrayList<>();
    private final List<Material> musicDiscs = new ArrayList<>();

    public PluginConfig(FileConfiguration cfg) {
        this.cfg = cfg;
    }

    public void reload() {
        enableDrops = cfg.getBoolean("enableDrops", true);
        spawnChance = cfg.getDouble("spawnChance", 0.5);
        maxPerPlayer = cfg.getInt("maxPerPlayer", 8);
        damageCrops = cfg.getBoolean("damageCrops", true);
        dropOnlyByPlayer = cfg.getBoolean("dropOnlyByPlayer", false);
        autoExportResources = cfg.getBoolean("autoExportResources", true);

        biomeFilters.clear();
        biomeFilters.addAll(cfg.getStringList("biomes"));

        spawnerBlocks.clear();
        for (String s : cfg.getStringList("spawnerBlocks")) {
            Material m = Material.matchMaterial(s);
            if (m != null && m.isBlock()) {
                spawnerBlocks.add(m);
            }
        }
        if (spawnerBlocks.isEmpty()) {
            spawnerBlocks.add(Material.DEAD_BUSH);
        }

        ConfigurationSection loot = cfg.getConfigurationSection("loot");
        if (loot != null) {
            ConfigurationSection weights = loot.getConfigurationSection("weights");
            if (weights != null) {
                weightCommon = weights.getInt("common", weightCommon);
                weightUncommon = weights.getInt("uncommon", weightUncommon);
                weightRare = weights.getInt("rare", weightRare);
                weightEpic = weights.getInt("epic", weightEpic);
                weightMusicDisc = weights.getInt("musicDisc", weightMusicDisc);
            }
            loadMaterials(loot.getStringList("common"), lootCommon);
            loadMaterials(loot.getStringList("uncommon"), lootUncommon);
            loadMaterials(loot.getStringList("rare"), lootRare);
            loadMaterials(loot.getStringList("epic"), lootEpic);
            loadMaterials(loot.getStringList("musicDiscs"), musicDiscs);
        }
    }

    private void loadMaterials(List<String> names, List<Material> out) {
        out.clear();
        for (String s : names) {
            Material m = Material.matchMaterial(s);
            if (m != null) {
                out.add(m);
            }
        }
    }

    /**
     * 判断目标生物群系是否允许生成。
     * 未配置过滤器时使用原版默认白名单 (沙漠/恶地/热带草原系)。
     */
    public boolean isBiomeAllowed(org.bukkit.block.Biome biome) {
        if (biomeFilters.isEmpty()) {
            return isDefaultBiome(biome);
        }
        NamespacedKey key = Registry.BIOME.getKey(biome);
        if (key == null) {
            return false;
        }
        for (String filter : biomeFilters) {
            if (filter.startsWith("#")) {
                Tag<org.bukkit.block.Biome> tag = Bukkit.getTag(Tag.REGISTRY_BIOMES,
                        NamespacedKey.fromString(filter.substring(1)), org.bukkit.block.Biome.class);
                if (tag != null && tag.isTagged(biome)) {
                    return true;
                }
            } else {
                NamespacedKey filterKey = NamespacedKey.fromString(filter.toLowerCase(Locale.ROOT));
                if (filterKey != null && filterKey.equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isDefaultBiome(org.bukkit.block.Biome biome) {
        return switch (biome) {
            case DESERT, BADLANDS, WOODED_BADLANDS, ERODED_BADLANDS,
                 SAVANNA, SAVANNA_PLATEAU, WINDSWEPT_SAVANNA -> true;
            default -> false;
        };
    }

    public boolean isSpawnerBlock(Material material) {
        return spawnerBlocks.contains(material);
    }

    /** 按权重抽取一件掉落物;骷髅击杀时额外计入音乐唱片权重。 */
    public Material rollLoot(boolean skeletonKilled) {
        int total = weightCommon + weightUncommon + weightRare + weightEpic
                + (skeletonKilled ? weightMusicDisc : 0);
        if (total <= 0) {
            return null;
        }
        int roll = (int) (Math.random() * total);
        if (roll < weightCommon) {
            return pick(lootCommon);
        }
        roll -= weightCommon;
        if (roll < weightUncommon) {
            return pick(lootUncommon);
        }
        roll -= weightUncommon;
        if (roll < weightRare) {
            return pick(lootRare);
        }
        roll -= weightRare;
        if (roll < weightEpic) {
            return pick(lootEpic);
        }
        return pick(musicDiscs);
    }

    private Material pick(List<Material> list) {
        return list.isEmpty() ? null : list.get((int) (Math.random() * list.size()));
    }

    public boolean isEnableDrops() {
        return enableDrops;
    }

    public double getSpawnChance() {
        return spawnChance;
    }

    public int getMaxPerPlayer() {
        return maxPerPlayer;
    }

    public boolean isDamageCrops() {
        return damageCrops;
    }

    public boolean isDropOnlyByPlayer() {
        return dropOnlyByPlayer;
    }

    public boolean isAutoExportResources() {
        return autoExportResources;
    }

    public List<Material> getMusicDiscs() {
        return Collections.unmodifiableList(musicDiscs);
    }
}
