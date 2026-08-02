# 风滚草 (Tumbleweed) — Paper 插件版

将原版 [Tumbleweed (Forge mod)](https://github.com/konwboj/Tumbleweed) 移植为 Paper 插件,
忠实还原风力物理、旋转滚动、落地压扁、农田践踏、骷髅射击、寿命淡出与战利品表。

- 物理、旋转、压扁、寿命/淡出由本插件实现(MM 无法配置的部分)
- 怪物属性、践踏、掉落、骷髅 AI 由 MythicMobs 配置驱动
- 自然生成由本插件按原版 `Spawner` 逻辑实现(干灌木限定/成双/上限等 MM 表达不了),
  经 MythicMobs API 创建实体,怪物属性/践踏/掉落仍由 MM 配置驱动
- 3D 渲染由 ModelEngine 承担,模型为**原版 mod 的原始模型结构**
  (9 块交叉薄板编织球 + 原版 16×16 纹理,取自 konwboj/Tumbleweed, LGPL-3.0)

## 前置依赖

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| Paper | 26.2+ | 服务端(1.21.9+ 协议) |
| MythicMobs | 5.13+ | 怪物框架(本文档所有行为配置均基于 5.13 语法) |
| ModelEngine | R4.1.1 | 3D 模型渲染(蓝图 `tumbleweed.bbmodel`) |
| CraftEngine(可选) | 26.x | 可选优化:实体可见性剔除(culling),未安装时自动降级 |

## 安装

1. 将 `Tumbleweed-1.0.0.jar` 放入 `plugins/` 目录,重启服务器。
2. 首次启动时插件自动导出(不会覆盖已存在的文件):

| 导出路径 | 用途 |
| --- | --- |
| `plugins/MythicMobs/Mobs/Tumbleweed.yml` | 风滚草怪物定义 |
| `plugins/MythicMobs/Mobs/Skeleton.yml` | 骷髅射击 AI(覆盖原版骷髅) |
| `plugins/ModelEngine/blueprints/tumbleweed.bbmodel` | 3D 模型蓝图(原版模型结构:9 板编织球) |
| `plugins/ModelEngine/textures/tumbleweed.png` | 模型纹理(原版 16×16 纹理) |

> 注:自然生成已由插件接管,不再导出 `TumbleweedSpawner.yml`。
> 若服务器上残留旧版导出的 `plugins/MythicMobs/Spawners/TumbleweedSpawner.yml`,
> **请手动删除**(或注释掉),否则会与插件生成器双重生成。

3. 服务器控制台执行 `/mm reload` 加载怪物配置,执行 `/me reload` 加载模型蓝图。
4. 若导出被关闭(`config.yml` 中 `auto-export-resources: false`),
   可手动从 jar 内提取 `mythicmobs/`、`modelengine/` 目录按上述路径放置。

## 配置说明

### `plugins/Tumbleweed/config.yml`

```yaml
wind-multiplier: 1.0        # 风力倍率(原版强度为 1.0,调大滚得更快)
auto-export-resources: true # 首次启动自动导出 MM/ME 资源

# 自然生成(原版 Spawner 逻辑,插件实现)
spawner:
  enabled: true          # 是否启用自然生成
  chance: 0.5            # 每个候选区块的生成概率(原版 spawnChance: 0.5)
  max-per-player: 8      # 每玩家数量上限(原版 maxPerPlayer: 8,按候选区比例折算)

# 性能优化(对应原版 1.14 ServerEntityMixin 的同步精简思路)
performance:
  distant-physics-distance: 96  # 距最近玩家超过该距离(格)的风滚草物理降频;0 = 关闭
  distant-physics-interval: 4   # 降频区每 N tick 才运行一次物理与渲染同步

  # CraftEngine 可见性剔除优化(可选,未安装 CraftEngine 时自动降级为纯距离判定)
  culling-enabled: true      # 是否启用 CE 可见性剔除
  culling-near-distance: 32  # 距最近玩家该距离(格)内始终全速,不做剔除
                             # 32 ~ distant-physics-distance 区间查询 CE 可见性,
                             # 不可见则降频;超过远距离按距离降频
```

### `plugins/MythicMobs/Mobs/Tumbleweed.yml` — 怪物

- `Drops`:战利品表(定制表:小麦种子 20% / 木棍 25% / 干灌木 10% / 泥土 10%,
  音乐唱片 5% 且仅非玩家击杀掉落。注:原版全部版本均为 16 项权重表——
  骨头/干灌木/线/羽毛/小麦/木棍/甘蔗 ×3、西瓜/南瓜种子/金粒 ×2、
  命名牌/鞍/绿宝石/钻石/铁锭/金锭 ×1,无泥土与唱片;如需对齐原版,
  将 Drops 替换为上述条目即可)。按需增删条目即可。
- `Skills` → `TumbleweedTrample`:践踏农田,`~onTimer:10` + `chance=0.7`
  (原版为落地瞬间 70% 概率;MM 无 onLand 触发器,以每 10 tick 70% 近似,
  改 `chance` 可调节概率,脚下方块必须为 `FARMLAND` 才转化)。
- 血量 1、无 AI、免疫摔落/火焰/溺水/爆炸;原猪实体隐形,渲染由渲染后端承担。
- `Despawn: {F: 0, D: 0}` 已禁用 MM 消失计时,寿命与脱管消失由插件接管
  (2 分钟,80 tick 淡出,玩家离开 110 格消失,均与原版一致)。

### `plugins/MythicMobs/Mobs/Skeleton.yml` — 骷髅射击 AI

- `~onTimer:20` + `chance=0.06` ≈ 原版每 tick 0.3% 概率;
- 目标:9~18 格内、视线内最近的风滚草;箭速 32 blocks/s(原版 1.6);
- 覆盖 vanilla 骷髅,全服生效。若只想部分骷髅参与,将该技能移至自定义怪物类型。

### 自然生成(插件按原版 `Spawner` 逻辑实现)

原版 1.14 的 `Spawner.java` 细节较多(干灌木生成点、±5 格偏移、20% 成双、
动态上限),MythicMobs 的 RandomSpawner 无法完整表达,故由本插件接管:

- 每 10 秒对每个世界检查一次(受 `domobspawning` 游戏规则控制);
- 候选范围:每个非旁观玩家所在区块周围 ±8 区块(排除角落)、世界边界内、
  区块中心群系为干燥/沙地群系(沙漠/恶地/稀树草原系);
- 生成点:必须在**干灌木 (dead_bush)** 上且可见天空,最多 10 次找位尝试
  (干灌木 ±5 格、高度 ±2 格,下方须为不透明方块);
- 20% 概率一次生成两只;生成点 32 格内无玩家、世界出生点 24 格外;
- 概率与上限见 `config.yml` 的 `spawner` 段(默认对齐原版 `spawnChance: 0.5`、
  `maxPerPlayer: 8`,上限按候选区数量比例折算);
- 实体经 MythicMobs API 创建,怪物属性/践踏/掉落仍由 MM 配置驱动。

> 升级注意:若服务器上残留旧版导出的 `plugins/MythicMobs/Spawners/TumbleweedSpawner.yml`,
> 请手动删除,否则会与插件生成器双重生成。

## 行为对照(原版 → 本实现)

| 原版行为 | 实现方式 |
| --- | --- |
| 风力 0.08/-0.08(每 2 分钟随机翻转)、重力 0.012、摩擦 0.98、落地反弹 | 插件物理 (Tumbleweed.java) |
| 旋转滚动(原版系数 2π·v/5size)+ 落地压扁(新版本特性) | 插件计算四元数/压扁 → ModelEngine root 骨骼 |
| 水中减速、卡墙老化加速、寿命淡出、脱管消失 | 插件 |
| 践踏农田 (70% + doMobGriefing) | MM 技能 `TumbleweedTrample` |
| 骷髅射击风滚草 | MM 技能 + AI 条件 (Skeleton.yml) |
| 干灌木上自然生成 (±8 区块 / 20% 成双 / 数量上限) | 插件生成器 (TumbleweedSpawner.java,经 MM API 建实体) |
| 战利品表 + 骷髅唱片 | MM Drops |
| 命名牌命名 → 持久 | 插件监听器(命名后不再消失) |

## 性能优化说明

原版 1.14 分支针对无 AI 的风滚草实体做了网络同步精简(`ServerEntityMixin`,
涉及 `VecDeltaCodec`/`ServerEntity`/`ServerPlayer`/`Mth`,解决位置增量编码在
高频 setPosition 下的精度与开销问题)。本插件在服务端等价位置做了如下优化:

1. **玩家距离缓存**:脱管检查(110 格)不再每风滚草每 tick 遍历全服玩家,
   改为每 10 tick 刷新一次各风滚草到最近玩家的距离(按世界分组一次取位置)。
   脱管判定最多延迟 10 tick(0.5 秒),阈值远大于误差,玩家无感知。
2. **远处物理降频**:距最近玩家超过 `distant-physics-distance`(默认 96 格,
   超过常见渲染视距)的风滚草,物理计算与 ModelEngine 渲染同步降频为每
   `distant-physics-interval`(默认 4)tick 一次;寿命按真实时间补偿(不会变长),
   玩家靠近后自动恢复全速。淡出中的风滚草不降频。
3. **ModelEngine scale 缓存**:正常滚动时模型缩放不变,不再每 tick 重复发送
   scale 同步包(压扁/恢复/淡出期间缩放每 tick 变化,仍全速同步)。
4. **CraftEngine 可见性剔除(可选)**:安装 CraftEngine 后(未安装自动降级,
   行为等同仅距离判定),对距最近玩家 `culling-near-distance` 至
   `distant-physics-distance` 区间的风滚草,每 10 tick 通过
   `EntityCulling.isVisible` 判断该玩家视角是否可见(视锥 + 遮挡);
   不可见的风滚草进入降频循环(物理与渲染同步降频),玩家转头看见时立即恢复全速。
   距离配置见 `config.yml` 的 `performance` 段(`culling-enabled` /
   `culling-near-distance`,默认 32);超过远距离的风滚草仍按距离降频。
5. **对象复用**:每 tick 不再分配摩擦 Vector、旋转用 Quaternionf、移动用 Location;
   水中检测单次计算。
6. **实体探测减负**:风滚草静止(速度 < 0.0005)时跳过附近实体探测;探测范围按
   原版 AABB 修正(y 不再向头顶扩展);按原版 `canBePushed` 语义不推玩家。

参考:原版混入文件 `Common/src/main/java/net/konwboy/tumbleweed/mixins/ServerEntityMixin.java`(1.14 分支)。

## 模型说明

- `tumbleweed.bbmodel` 按原版 mod 的 `ModelTumbleweed.java` 重建:
  4 组共 9 块交叉薄板(正交组 + 绕 Y/Z/X 各 45° 组),与原版一致的编织球造型;
  模型中心在实体脚底上方 0.25 格(原版 `GlStateManager.translate(y + 0.25F)`),直径 16px(1 格)。
- 纹理为原版 `textures/entity/tumbleweed.png`(16×16,半透明编织镂空)。
- 素材来源:konwboj/Tumbleweed (LGPL-3.0),生成脚本 `tools/generate_bbmodel.py`。
- 渲染缩放 `1 + size/8`、淡出 alpha 渐变均与原版 RenderTumbleweed 一致。

## 构建

```bash
# GitHub Actions(推荐,已在仓库配置 .github/workflows/build.yml,Java 25 + Gradle 9.6.1)
git push origin paper-port   # 自动编译并上传 jar artifact

# 本地构建
./gradlew build
# 产物: build/libs/Tumbleweed-1.0.0.jar
```
