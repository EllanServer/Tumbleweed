# 风滚草 (Tumbleweed) — Paper 插件版

将原版 [Tumbleweed (Forge mod)](https://github.com/konwboj/Tumbleweed) 移植为 Paper 插件,
忠实还原风力物理、旋转滚动、落地压扁、农田践踏、骷髅射击、寿命淡出与战利品表。

- 物理、旋转、压扁、寿命/淡出由本插件实现(MM 无法配置的部分)
- 怪物属性、践踏、掉落、骷髅 AI、自然生成全部由 MythicMobs 配置驱动
- 3D 渲染由 ModelEngine 承担,模型为**原版 mod 的原始模型结构**
  (9 块交叉薄板编织球 + 原版 16×16 纹理,取自 konwboj/Tumbleweed, LGPL-3.0)

## 前置依赖

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| Paper | 26.2+ | 服务端(1.21.9+ 协议) |
| MythicMobs | 5.13+ | 怪物框架(本文档所有行为配置均基于 5.13 语法) |
| ModelEngine | R4.1.1 | 3D 模型渲染(蓝图 `tumbleweed.bbmodel`) |

## 安装

1. 将 `Tumbleweed-1.0.0.jar` 放入 `plugins/` 目录,重启服务器。
2. 首次启动时插件自动导出(不会覆盖已存在的文件):

| 导出路径 | 用途 |
| --- | --- |
| `plugins/MythicMobs/Mobs/Tumbleweed.yml` | 风滚草怪物定义 |
| `plugins/MythicMobs/Mobs/Skeleton.yml` | 骷髅射击 AI(覆盖原版骷髅) |
| `plugins/MythicMobs/Spawners/TumbleweedSpawner.yml` | 自然生成器 (RandomSpawner) |
| `plugins/ModelEngine/blueprints/tumbleweed.bbmodel` | 3D 模型蓝图(原版模型结构:9 板编织球) |
| `plugins/ModelEngine/textures/tumbleweed.png` | 模型纹理(原版 16×16 纹理) |

3. 服务器控制台执行 `/mm reload` 加载怪物配置,执行 `/me reload` 加载模型蓝图。
4. 若导出被关闭(`config.yml` 中 `auto-export-resources: false`),
   可手动从 jar 内提取 `mythicmobs/`、`modelengine/` 目录按上述路径放置。

## 配置说明

### `plugins/Tumbleweed/config.yml`

```yaml
wind-multiplier: 1.0        # 风力倍率(原版强度为 1.0,调大滚得更快)
auto-export-resources: true # 首次启动自动导出 MM/ME 资源

# 性能优化(对应原版 1.14 ServerEntityMixin 的同步精简思路)
performance:
  distant-physics-distance: 96  # 距最近玩家超过该距离(格)的风滚草物理降频;0 = 关闭
  distant-physics-interval: 4   # 降频区每 N tick 才运行一次物理与渲染同步
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

### `plugins/MythicMobs/Spawners/TumbleweedSpawner.yml` — 自然生成

- `Chance: 0.1` 每次尝试生成概率;`MobAmount: 1-2` 20% 概率成双;
- `MinDistance/MaxDistance` 20~48 格(玩家周围);
- `Conditions` 限定干燥生物群系(沙漠/恶地/热带草原系);如需限定干灌木方块,
  追加 `- blockIs{m=DEAD_BUSH}`。

## 行为对照(原版 → 本实现)

| 原版行为 | 实现方式 |
| --- | --- |
| 风力 0.08/-0.08(每 2 分钟随机翻转)、重力 0.012、摩擦 0.98、落地反弹 | 插件物理 (Tumbleweed.java) |
| 旋转滚动(原版系数 2π·v/5size)+ 落地压扁(新版本特性) | 插件计算四元数/压扁 → ModelEngine root 骨骼 |
| 水中减速、卡墙老化加速、寿命淡出、脱管消失 | 插件 |
| 践踏农田 (70% + doMobGriefing) | MM 技能 `TumbleweedTrample` |
| 骷髅射击风滚草 | MM 技能 + AI 条件 (Skeleton.yml) |
| 玩家周边自然生成 | MM RandomSpawner (TumbleweedSpawner.yml) |
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
4. **对象复用**:每 tick 不再分配摩擦 Vector、旋转用 Quaternionf、移动用 Location;
   水中检测单次计算。
5. **实体探测减负**:风滚草静止(速度 < 0.0005)时跳过附近实体探测;探测范围按
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
