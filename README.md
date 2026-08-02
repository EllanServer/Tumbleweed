# 风滚草 (Tumbleweed) — Paper 插件版

将原版 [Tumbleweed (Forge mod)](https://github.com/konwboj/Tumbleweed) 移植为 Paper 插件,
忠实还原风力物理、旋转滚动、落地压扁、农田践踏、骷髅射击、寿命淡出与战利品表。

- 物理、旋转、压扁、寿命/淡出由本插件实现(MM 无法配置的部分)
- 怪物属性、践踏、掉落、骷髅 AI、自然生成全部由 MythicMobs 配置驱动
- 渲染与玩家交互由 **CraftEngine 家具系统托管**:风滚草是一个 CE 家具
  (ItemDisplay 元素显示 + interaction hitbox 承接交互),模型为**原版 mod 的原始模型结构**
  (9 块交叉薄板编织球 + 原版 16×16 纹理,取自 konwboj/Tumbleweed, LGPL-3.0)

## 前置依赖

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| Paper | 26.2+ | 服务端(1.21.9+ 协议) |
| MythicMobs | 5.13+ | 怪物框架(本文档所有行为配置均基于 5.13 语法) |
| CraftEngine | 26.7.4+ | 家具托管:渲染(ItemDisplay)+ 交互事件(FurnitureHitEvent/FurnitureInteractEvent) |

## 安装

1. 安装 [CraftEngine](https://github.com/MoMiMCraft/CraftEngine) 26.7.4+ 与 MythicMobs 5.13+。
2. 将 `Tumbleweed-1.0.0.jar` 放入 `plugins/` 目录,重启服务器。
3. 首次启动时插件自动导出(不会覆盖已存在的文件):

| 导出路径 | 用途 |
| --- | --- |
| `plugins/MythicMobs/Mobs/Tumbleweed.yml` | 风滚草怪物定义 |
| `plugins/MythicMobs/Mobs/Skeleton.yml` | 骷髅射击 AI(覆盖原版骷髅) |
| `plugins/MythicMobs/Spawners/TumbleweedSpawner.yml` | 自然生成器 (RandomSpawner) |
| `plugins/CraftEngine/resources/tumbleweed/pack.yml` | CE 家具包定义 |
| `plugins/CraftEngine/resources/tumbleweed/configuration/furniture/tumbleweed.yml` | 家具定义(显示元素 + interaction hitbox) |
| `plugins/CraftEngine/resources/tumbleweed/resourcepack/assets/tumbleweed/models/item/tumbleweed.json` | 3D 模型(原版模型结构:9 板编织球) |
| `plugins/CraftEngine/resources/tumbleweed/resourcepack/assets/tumbleweed/textures/item/tumbleweed.png` | 模型纹理(原版 16×16 纹理) |

4. 服务器控制台执行 `/mm reload` 加载怪物配置,执行 `/ce reload all` 加载 CE 家具与模型。
5. 若导出被关闭(`config.yml` 中 `auto-export-resources: false`),
   可手动从 jar 内提取 `mythicmobs/`、`craftengine/` 目录按上述路径放置。

## 配置说明

### `plugins/Tumbleweed/config.yml`

```yaml
wind-multiplier: 1.0        # 风力倍率(原版强度为 1.0,调大滚得更快)
auto-export-resources: true # 首次启动自动导出 MM/CE 资源

# 性能优化(对应原版 1.14 ServerEntityMixin 的同步精简思路)
performance:
  distant-physics-distance: 96  # 距最近玩家超过该距离(格)的风滚草物理降频;0 = 关闭
  distant-physics-interval: 4   # 降频区每 N tick 才运行一次物理与家具同步
```

### `plugins/CraftEngine/resources/tumbleweed/configuration/furniture/tumbleweed.yml` — CE 家具

- `elements`:ItemDisplay 显示元素,引用 `tumbleweed:tumbleweed` 物品模型(9 板编织球)。
- `hitboxes`:`interaction` 命中盒(1.2×1.2)承接玩家交互:
  - 玩家**打击**家具 → `FurnitureHitEvent` → 对载体实体造成 1 点伤害
    (Pig 1 点血即死,掉落由 MythicMobs 死亡事件结算);
  - 玩家**右击**家具(手持命名牌)→ `FurnitureInteractEvent` → 命名并设为持久。
- 命中盒宽度/高度可按需调整(与原版碰撞箱 `0.75 + size/8` 的交互手感对齐)。

### `plugins/MythicMobs/Mobs/Tumbleweed.yml` — 怪物

- `Drops`:战利品表(定制表:小麦种子 20% / 木棍 25% / 干灌木 10% / 泥土 10%,
  音乐唱片 5% 且仅非玩家击杀掉落。注:原版全部版本均为 16 项权重表——
  骨头/干灌木/线/羽毛/小麦/木棍/甘蔗 ×3、西瓜/南瓜种子/金粒 ×2、
  命名牌/鞍/绿宝石/钻石/铁锭/金锭 ×1,无泥土与唱片;如需对齐原版,
  将 Drops 替换为上述条目即可)。按需增删条目即可。
- `Skills` → `TumbleweedTrample`:践踏农田,`~onTimer:10` + `chance=0.7`
  (原版为落地瞬间 70% 概率;MM 无 onLand 触发器,以每 10 tick 70% 近似,
  改 `chance` 可调节概率,脚下方块必须为 `FARMLAND` 才转化)。
- 血量 1、无 AI、免疫摔落/火焰/溺水/爆炸;原猪实体隐形、不可碰撞,
  视觉与交互完全由 CE 家具承担。
- `Despawn: {F: 0, D: 0}` 已禁用 MM 消失计时,寿命与脱管消失由插件接管
  (2 分钟,80 tick 淡出,玩家离开 110 格消失,均与原版一致)。

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
| 风力 0.08/-0.08(每 2 分钟随机翻转)、重力 0.012、摩擦 0.98、落地反弹 | 插件物理 (Tumbleweed.java) |
| 旋转滚动(原版系数 2π·v/5size)+ 落地压扁(新版本特性) | 插件计算四元数/压扁 → CE 家具 ItemDisplay 变换矩阵 |
| 水中减速、卡墙老化加速、寿命淡出、脱管消失 | 插件 |
| 践踏农田 (70% + doMobGriefing) | MM 技能 `TumbleweedTrample` |
| 骷髅射击风滚草 | MM 技能 + AI 条件 (Skeleton.yml) |
| 玩家周边自然生成 | MM RandomSpawner (TumbleweedSpawner.yml) |
| 战利品表 + 骷髅唱片 | MM Drops |
| 玩家打击即死(掉落结算) | CE `FurnitureHitEvent` → 载体实体 1 点伤害 → MM 死亡事件 |
| 命名牌命名 → 持久 | CE `FurnitureInteractEvent`(命名后不再消失) |

## 渲染与交互托管(CraftEngine 家具)

- **显示**:每个风滚草对应一个 CE 家具(`tumbleweed:tumbleweed`),
  元素为 ItemDisplay,物品模型是原版 9 板编织球(blockbench json,球心在模型中心);
  插件每 tick 设置变换矩阵 `T(0, mcSize/2, 0) × 球心旋转 × 压扁/淡出缩放`,
  与碰撞箱中心对齐。家具的追踪、剔除与网络同步全部由 CE 托管。
- **交互**:玩家点击/打击家具由 CE 的 interaction hitbox 拦截并派发
  `FurnitureHitEvent` / `FurnitureInteractEvent`,插件不再监听 Bukkit 实体交互事件;
  载体实体(Pig)隐形且 `collidable=false`,物理与 MM 技能完全不受玩家交互影响。
- **生命周期**:家具随风滚草生成而创建、随消失/死亡/脱管而移除
  (`CraftEngineFurniture.place/remove`),玩家无需手持家具物品。

## 性能优化说明

原版 1.14 分支针对无 AI 的风滚草实体做了网络同步精简(`ServerEntityMixin`,
涉及 `VecDeltaCodec`/`ServerEntity`/`ServerPlayer`/`Mth`,解决位置增量编码在
高频 setPosition 下的精度与开销问题)。本插件在服务端等价位置做了如下优化:

1. **玩家距离缓存**:脱管检查(110 格)不再每风滚草每 tick 遍历全服玩家,
   改为每 10 tick 刷新一次各风滚草到最近玩家的距离(按世界分组一次取位置)。
   脱管判定最多延迟 10 tick(0.5 秒),阈值远大于误差,玩家无感知。
2. **远处物理降频**:距最近玩家超过 `distant-physics-distance`(默认 96 格,
   超过常见渲染视距)的风滚草,物理计算与 CE 家具同步(moveTo + 变换)降频为每
   `distant-physics-interval`(默认 4)tick 一次;寿命按真实时间补偿(不会变长),
   玩家靠近后自动恢复全速。淡出中的风滚草不降频。
3. **CE 家具托管同步**:显示实体的追踪/剔除/网络包由 CraftEngine 管理
   (其 EntityCulling 体系只向可见玩家同步),插件侧不再自行维护渲染同步;
   家具 moveTo 仅在风滚草实际移动的 tick 触发,静止时不发同步。
4. **变换缓存**:压扁/淡出缩放不变时跳过变换矩阵重发(旋转每 tick 都在变,始终同步)。
5. **对象复用**:每 tick 不再分配摩擦 Vector、旋转用 Quaternionf、移动用 Location;
   水中检测单次计算。
6. **实体探测减负**:风滚草静止(速度 < 0.0005)时跳过附近实体探测;探测范围按
   原版 AABB 修正(y 不再向头顶扩展);按原版 `canBePushed` 语义不推玩家。

参考:原版混入文件 `Common/src/main/java/net/konwboy/tumbleweed/mixins/ServerEntityMixin.java`(1.14 分支)。

## 模型说明

- `tumbleweed.json`(CE 物品模型)按原版 mod 的 `ModelTumbleweed.java` 重建:
  4 组共 9 块交叉薄板(正交组 + 绕 Y/Z/X 各 45° 组),与原版一致的编织球造型;
  模型空间 16×16×16,球心在 (8,8,8),渲染时以碰撞箱中心为球心旋转/缩放。
- 纹理为原版 `textures/entity/tumbleweed.png`(16×16,半透明编织镂空)。
- 素材来源:konwboj/Tumbleweed (LGPL-3.0),生成脚本 `tools/generate_ce_model.py`。
- 渲染缩放 `1 + size/8`、淡出 alpha 渐变(以整体缩放近似)均与原版 RenderTumbleweed 一致。

## 构建

```bash
# GitHub Actions(推荐,已在仓库配置 .github/workflows/build.yml,Java 25 + Gradle 9.6.1)
git push origin paper-port   # 自动编译并上传 jar artifact

# 本地构建
./gradlew build
# 产物: build/libs/Tumbleweed-1.0.0.jar
```
