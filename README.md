# 风滚草 (Tumbleweed) — Paper 插件版

将原版 [Tumbleweed (Forge mod)](https://github.com/konwboj/Tumbleweed) 移植为 Paper 插件,
忠实还原风力物理、旋转滚动、落地压扁、农田践踏、骷髅射击、寿命淡出与战利品表。

- 物理、旋转、压扁、寿命/淡出由本插件实现(MM 无法配置的部分)
- 怪物属性、践踏、掉落、骷髅 AI、自然生成全部由 MythicMobs 配置驱动
- 渲染默认走 **craft-engine 物品模型 + ItemDisplay 实体**(无客户端模组、性能更好),
  未装 CraftEngine 时自动回退 **ModelEngine** 骨骼模型

## 前置依赖

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| Paper | 26.2+ | 服务端(1.21.9+ 协议) |
| MythicMobs | 5.13+ | 怪物框架(本文档所有行为配置均基于 5.13 语法) |
| CraftEngine | 26.7.4 | 渲染:物品模型 + 资源包(默认渲染后端,二选一) |
| ModelEngine | R4.1.1 | 渲染:3D 骨骼模型(CraftEngine 缺失时的回退,二选一) |

> CraftEngine 与 ModelEngine 至少装一个;`config.yml` 的 `renderer` 可强制指定。

## 安装

1. 将 `Tumbleweed-1.0.0.jar` 放入 `plugins/` 目录,重启服务器。
2. 首次启动时插件自动导出(不会覆盖已存在的文件):

| 导出路径 | 用途 |
| --- | --- |
| `plugins/MythicMobs/Mobs/Tumbleweed.yml` | 风滚草怪物定义 |
| `plugins/MythicMobs/Mobs/Skeleton.yml` | 骷髅射击 AI(覆盖原版骷髅) |
| `plugins/MythicMobs/Spawners/TumbleweedSpawner.yml` | 自然生成器 (RandomSpawner) |
| `plugins/CraftEngine/resources/tumbleweed/pack.yml` | craft-engine 包定义(命名空间 `tumbleweed`) |
| `plugins/CraftEngine/resources/tumbleweed/configuration/items.yml` | craft-engine 物品定义 |
| `plugins/CraftEngine/resources/tumbleweed/resourcepack/.../tumbleweed.json` | 风滚草物品模型(圆柱球近似) |
| `plugins/CraftEngine/resources/tumbleweed/resourcepack/.../tumbleweed.png` | 模型纹理 |
| `plugins/ModelEngine/blueprints/tumbleweed.bbmodel` | ModelEngine 蓝图(回退用) |
| `plugins/ModelEngine/textures/tumbleweed.png` | ModelEngine 纹理(回退用) |

3. 加载配置:
   - 控制台执行 `/mm reload` 加载怪物配置;
   - 若使用 craft-engine 渲染:执行 **`/ce reload all`**(首次安装必须,把导出的模型/贴图打包进资源包,并让玩家自动接收);
   - 若使用 ModelEngine 渲染:执行 `/me reload` 加载模型蓝图。
4. 若导出被关闭(`config.yml` 中 `auto-export-resources: false`),可手动从 jar 内提取
   `mythicmobs/`、`craftengine/`、`modelengine/` 目录按上述路径放置。

## 配置说明

### `plugins/Tumbleweed/config.yml`

```yaml
wind-multiplier: 1.0        # 风力倍率(原版强度为 1.0,调大滚得更快)
renderer: auto              # auto(默认,优先 CraftEngine)/ craftengine / modelengine
auto-export-resources: true # 首次启动自动导出 MM/CE/ME 资源
```

### `plugins/MythicMobs/Mobs/Tumbleweed.yml` — 怪物

- `Drops`:战利品表(原版概率:小麦种子 20% / 木棍 25% / 干灌木 10% / 泥土 10%,
  音乐唱片 5% 且仅非玩家击杀掉落)。按需增删条目即可。
- `Skills` → `TumbleweedTrample`:践踏农田(落地时把脚下 `FARMLAND` 变 `DIRT`),
  通过 `~onTimer:10` 每 10 tick 检查一次;改 `chance` 可调节概率。
- 血量 1、无 AI、免疫摔落/火焰/溺水/爆炸;原猪实体隐形,渲染由渲染后端承担。
- `Despawn: {F: 0, D: 0}` 已禁用 MM 消失计时,寿命与脱管消失由插件接管
  (2 分钟 + 0~200 tick 随机,80 tick 淡出,玩家离开 160 格消失)。

### `plugins/MythicMobs/Mobs/Skeleton.yml` — 骷髅射击 AI

- `~onTimer:20` + `chance=0.06` ≈ 原版每 tick 0.3% 概率;
- 目标:9~18 格内、视线内最近的风滚草;箭速 32 blocks/s(原版 1.6);
- 覆盖 vanilla 骷髅,全服生效。若只想部分骷髅参与,将该技能移至自定义怪物类型。

### `plugins/MythicMobs/Spawners/TumbleweedSpawner.yml` — 自然生成

- `Chance: 0.1` 每次尝试生成概率;`MobAmount: 1-2` 20% 概率成双;
- `MinDistance/MaxDistance` 20~48 格(玩家周围);
- `Conditions` 限定干燥生物群系(沙漠/恶地/热带草原系);如需限定干灌木方块,
  追加 `- blockIs{m=DEAD_BUSH}`。

## 行为对照(原版 → 本实现)

| 原版行为 | 实现方式 |
| --- | --- |
| 风力 -1/16、重力 0.012、摩擦 0.98、落地反弹 | 插件物理 (Tumbleweed.java) |
| 旋转滚动 + 落地压扁 | 插件计算四元数/压扁 → ItemDisplay 变换矩阵(或 ModelEngine root 骨骼) |
| 水中减速、卡墙老化加速、寿命淡出、脱管消失 | 插件(淡出 = 尺寸渐变模拟渐隐) |
| 践踏农田 (70% + doMobGriefing) | MM 技能 `TumbleweedTrample` |
| 骷髅射击风滚草 | MM 技能 + AI 条件 (Skeleton.yml) |
| 玩家周边自然生成 | MM RandomSpawner (TumbleweedSpawner.yml) |
| 战利品表 + 骷髅唱片 | MM Drops |
| 命名牌命名 → 持久 | 插件监听器(命名后不再消失) |

## 渲染层说明

- **craft-engine 模式(默认)**:每个风滚草挂一个 `ItemDisplay` 实体,物品为
  `tumbleweed:tumbleweed`(纸 + 自定义模型,模型由 craft-engine 资源包下发)。
  每 tick 只更新变换矩阵(围绕球心缩放/旋转 + 平移到碰撞箱中心),无客户端模组。
- **ModelEngine 模式(回退)**:每个风滚草挂 `tumbleweed.bbmodel`,每 tick 同步
  root 骨骼四元数与缩放。
- 模型为 5 层 × 12 段圆柱球近似(60 元素),视觉直径与碰撞箱一致(0.75 + size/8)。
- 切换渲染后端只需在 `config.yml` 改 `renderer` 并重启。

## 构建

```bash
# GitHub Actions(推荐,已在仓库配置 .github/workflows/build.yml,Java 25 + Gradle 9.6.1)
git push origin paper-port   # 自动编译并上传 jar artifact

# 本地构建
./gradlew build
# 产物: build/libs/Tumbleweed-1.0.0.jar
```

> 修改物品模型后重新生成 craft-engine 资源:
> `python tools/generate_ce_model.py`(输出到 `src/main/resources/craftengine/`)。
