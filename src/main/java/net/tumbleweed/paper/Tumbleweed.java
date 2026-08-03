package net.tumbleweed.paper;

import net.tumbleweed.paper.model.RotationState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;

import java.util.Collection;
import java.util.Random;

/**
 * 风滚草的物理与行为,忠实移植原版 EntityTumbleweed 的 tick 逻辑。
 *
 * 原版关键数值 (master, 1.8.9):
 *  - 风力: windX = 0.08, windZ = -0.08 (每 2 分钟各 50% 概率翻转符号),
 *    windModX/Z 独立随机, 1.0 + (0.2 - 0.4 * rand) ∈ [0.8, 1.2]
 *  - 重力 0.012 / tick,摩擦 0.98 / tick,速度低于 0.005 清零
 *  - 落地反弹: |windX|>=0.05 或 |windZ|>=0.05 时 y = max(-prevY*0.7, 0.24 - size*0.02),
 *    否则 y = -prevY*0.7
 *  - 水平撞墙反弹: motionX/Z = -移动前速度 * 0.4
 *  - 水中:速度 xz * 0.95, y + 0.01,无风力
 *  - 卡墙或水中时年龄加速 8 倍;寿命 2 分钟;之后 80 tick 淡出消失
 *  - 玩家离开 110 格后消失 (原版 d3 > 110*110,三维距离)
 *  - 旋转:每 tick 绕 X 转 -2π*motionZ/(5*size)、绕 Z 转 2π*motionX/(5*size) 弧度
 *  - 尺寸: mcSize = 0.75 + size * 1/8 (size 1-4;原版随机为 1-(3-rand(5)) 疑似笔误,取 1-4)
 *  - 落地压扁为 mod 新版本特性 (master 1.8.9 无),按新版本行为保留
 */
public class Tumbleweed {

    public static final int FADE_TIME = 4 * 20;       // 80 ticks 淡出
    private static final int DESPAWN_RANGE = 110;     // 脱离玩家范围 (原版 110)
    private static final double BASE_SIZE = 3 / 4d;   // 0.75
    private static final double GRAVITY = 0.012;
    private static final double FRICTION = 0.98;
    private static final float MOTION_CUTOFF = 0.005f; // 速度阈值 (原版)
    private static final double ROT_DIVISOR = 5.0;     // 原版旋转: 2π * v / (5 * size)

    private final Entity entity;          // MythicMobs 载体实体 (Pig, NoAI)
    private final int size;
    private final double windModX;        // 原版: 独立随机 0.8 ~ 1.2
    private final double windModZ;
    private final int lifetime;

    private int age;
    private int fadeProgress;
    private boolean fading;
    private boolean persistent;
    private int physicsCycle; // 降频调度计数 (距玩家过远时每 N tick 跑一次物理)
    private int tickIndex = -1; // TumbleweedManager.ticking 列表中的索引 (标记删除用)

    // 物理状态
    private final Vector motion = new Vector();
    private final Vector prevMotion = new Vector();
    private boolean onGround;
    private boolean horizontalCollision;
    private boolean prevVerticalCollision;

    // 旋转状态 (原版客户端逻辑,在服务端计算后交给 ModelEngine)
    private final RotationState rotation;

    private final Random random = new Random();

    // 性能:复用临时对象,避免每 tick 分配
    private final Quaternionf rotTemp = new Quaternionf();
    private final Location cachedLoc;          // 复用 Location,避免每 tick getLocation() 分配新对象
    private final BoundingBox moveBB;          // moveEntity 复用 BB
    // Chunk 直读缓存 (借鉴 CE EntityCulling 的 lastVisitChunk 思路:命中上次 chunk 免 hash 查找)
    private org.bukkit.ChunkSnapshot lastSnapshot;
    private int lastSnapshotX = Integer.MIN_VALUE;
    private int lastSnapshotZ = Integer.MIN_VALUE;
    // CE 可见性判定用的 AABB 快照 (主线程物理后写入,CE 异步线程只读)。
    // 用 6 个 public volatile 字段而非 BoundingBox:跨包访问 + 零分配 + 可见性正确,
    // 使 Tumbleweed 核心类保持零 CE 依赖 (Cullable 适配在 CullingIntegration 隔离层)。
    public volatile double cullMinX, cullMinY, cullMinZ, cullMaxX, cullMaxY, cullMaxZ;

    // ModelEngine 渲染快照 (主线程物理 tick 末尾写入,ME 异步线程池 PRE_MODEL_TICK 阶段只读)。
    // ME R4 的模型更新 (ModelUpdaters) 全部在 work-stealing 线程池执行,主线程直接写
    // SafeTransform 字段存在跨线程竞争 (SafeTransform 写侧无 release fence),因此 transform
    // 写入必须发生在 ME 异步线程,这里只提供 volatile 快照中转 (同理零分配 + 可见性正确)。
    // 旋转 = RotationState.quat 的四元数分量;scale = renderScaleX/Y/Z 乘淡出 alpha 的最终值。
    public volatile float renderRotX, renderRotY, renderRotZ, renderRotW;
    public volatile float renderScaleX, renderScaleY, renderScaleZ;

    public Tumbleweed(Entity entity, int size) {
        this.entity = entity;
        this.size = Math.max(1, Math.min(4, size));
        this.rotation = new RotationState();

        // 原版以实体 id 作为随机种子:windMod 与 lifetime 可复现
        Random seeded = new Random(entity.getEntityId());
        this.windModX = 1.0 + 0.2 - 0.4 * seeded.nextDouble();
        this.windModZ = 1.0 + 0.2 - 0.4 * seeded.nextDouble();
        this.lifetime = 2 * 60 * 20;

        // 预分配复用对象
        this.cachedLoc = entity.getLocation();
        double w = mcSize();
        this.moveBB = new BoundingBox(0, 0, 0, 0, 0, 0);
        updateCullAabb();
        updateRenderSnapshot(); // 初始化渲染快照 (模型 attach 前即有有效值)
    }

    /** 每个 tick 调用一次,驱动物理与旋转。 */
    public void tick(TumbleweedManager manager) {
        if (!entity.isValid() || entity.isDead()) {
            manager.remove(this);
            return;
        }

        // 原版 preTickClient (压扁恢复) —— 在物理前执行
        rotation.tickPre();

        boolean inWater = isInWater();

        // 重力
        if (!inWater) {
            motion.setY(motion.getY() - GRAVITY);
        }

        prevMotion.copy(motion);
        moveEntity();
        updateCullAabb();

        // 风力 (原版: windX=0.08, windZ=-0.08,每 2 分钟随机翻转;windModX/Z 独立)
        // windMultiplier 为插件配置倍率 (原版无,默认 1.0)
        double windMultiplier = TumbleweedPlugin.getInstance().pluginConfig().windMultiplier();
        double windX = TumbleweedPlugin.windX() * windModX * windMultiplier;
        double windZ = TumbleweedPlugin.windZ() * windModZ * windMultiplier;
        if (inWater) {
            motion.setX(motion.getX() * 0.95);
            motion.setZ(motion.getZ() * 0.95);
            motion.setY(motion.getY() + 0.01);
            windX = 0;
            windZ = 0;
        } else if (windX != 0 || windZ != 0) {
            motion.setX(windX);
            motion.setZ(windZ);
        }

        // 旋转 (原版 tickClient,在 moveEntity 之后、反弹之前,使用移动后的 motion)
        tickRotation();

        // 落地反弹
        if (onGround) {
            double bounce;
            if (windX * windX + windZ * windZ >= 0.05 * 0.05) {
                bounce = Math.max(-prevMotion.getY() * 0.7, 0.24 - Math.abs(size) * 0.02);
            } else {
                bounce = -prevMotion.getY() * 0.7;
            }
            motion.setY(bounce);
        }

        // 水平撞墙反弹 (原版 isCollidedHorizontally: motion = -移动前速度 * 0.4)
        if (horizontalCollision) {
            motion.setX(-prevMotion.getX() * 0.4);
            motion.setZ(-prevMotion.getZ() * 0.4);
        }

        // 摩擦 (标量,避免每 tick 分配 Vector)
        motion.multiply(FRICTION);

        // 速度阈值清零 (原版 |motion| < 0.005)
        if (Math.abs(motion.getX()) < MOTION_CUTOFF) {
            motion.setX(0);
        }
        if (Math.abs(motion.getY()) < MOTION_CUTOFF) {
            motion.setY(0);
        }
        if (Math.abs(motion.getZ()) < MOTION_CUTOFF) {
            motion.setZ(0);
        }

        collideWithNearbyEntities(manager);

        // 卡墙或水中:老化加速
        age += (horizontalCollision || inWater) ? 8 : 1;
        if (age > lifetime && fadeProgress == 0) {
            fading = true;
        }

        if (fading) {
            fadeProgress++;
            if (fadeProgress > FADE_TIME) {
                manager.remove(this);
                return;
            }
        }

        // 物理与旋转完成后写渲染快照,供 ModelEngine 异步线程同步 (降频跳过的 tick 不写,
        // 快照不变,ME 侧惰性检测自然跳过,行为与原先降频时跳过 sync 一致)
        updateRenderSnapshot();

        // 践踏农田已由 MythicMobs 配置实现 (Tumbleweed.yml 的 TumbleweedTrample 技能)
        // 脱管检查 (玩家离开 110 格) 由 TumbleweedManager 统一处理,使用缓存的最近玩家距离
    }

    /** 物理 tick 末尾调用:把旋转/缩放/淡出写入 volatile 渲染快照。 */
    public void updateRenderSnapshot() {
        Quaternionf q = rotation.quat;
        renderRotX = q.x;
        renderRotY = q.y;
        renderRotZ = q.z;
        renderRotW = q.w;
        float fade = alpha();
        renderScaleX = renderScaleX() * fade;
        renderScaleY = renderScaleY() * fade;
        renderScaleZ = renderScaleZ() * fade;
    }

    /** 降频跳过物理 tick 时,仅推进寿命,保持风滚草按真实时间老化。 */
    public void ageTick(int ticks) {
        age += ticks;
        if (age > lifetime && fadeProgress == 0) {
            fading = true;
        }
    }

    /** 降频调度:距玩家过远时每 interval tick 返回一次 true (返回 true 的 tick 才跑物理)。 */
    public boolean shouldRunPhysics(int interval) {
        physicsCycle++;
        return physicsCycle % Math.max(1, interval) == 0;
    }

    /** 逐轴 AABB 方块碰撞移动 (模拟原版 move(MoverType.SELF, ...))。 */
    private void moveEntity() {
        // 复用 cachedLoc,避免每 tick 分配新 Location
        entity.getLocation(cachedLoc);
        double x = cachedLoc.getX();
        double y = cachedLoc.getY();
        double z = cachedLoc.getZ();

        double width = mcSize();
        double halfW = width / 2;

        horizontalCollision = false;
        onGround = false;

        // X 轴 — 复用 moveBB
        if (motion.getX() != 0) {
            moveBB.resize(x - halfW + motion.getX(), y, z - halfW,
                    x + halfW + motion.getX(), y + width, z + halfW);
            if (collidesWithBlocks(entity.getWorld(), moveBB)) {
                horizontalCollision = true;
                motion.setX(0);
            } else {
                x += motion.getX();
            }
        }
        // Z 轴
        if (motion.getZ() != 0) {
            moveBB.resize(x - halfW, y, z - halfW + motion.getZ(),
                    x + halfW, y + width, z + halfW + motion.getZ());
            if (collidesWithBlocks(entity.getWorld(), moveBB)) {
                horizontalCollision = true;
                motion.setZ(0);
            } else {
                z += motion.getZ();
            }
        }
        // Y 轴
        if (motion.getY() != 0) {
            moveBB.resize(x - halfW, y + motion.getY(), z - halfW,
                    x + halfW, y + width + motion.getY(), z + halfW);
            if (collidesWithBlocks(entity.getWorld(), moveBB)) {
                if (motion.getY() < 0) {
                    onGround = true;
                }
                motion.setY(0);
            } else {
                y += motion.getY();
            }
        }

        // 复用 cachedLoc 写回
        cachedLoc.setX(x);
        cachedLoc.setY(y);
        cachedLoc.setZ(z);
        entity.teleport(cachedLoc);
    }

    private boolean collidesWithBlocks(World world, BoundingBox bb) {
        int minX = (int) Math.floor(bb.getMinX());
        int maxX = (int) Math.floor(bb.getMaxX());
        int minY = (int) Math.floor(bb.getMinY());
        int maxY = (int) Math.floor(bb.getMaxY());
        int minZ = (int) Math.floor(bb.getMinZ());
        int maxZ = (int) Math.floor(bb.getMaxZ());
        int worldMinY = world.getMinHeight();
        int worldMaxY = world.getMaxHeight();
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                // 世界高度范围外视为空气 (虚空/天空),避免越界查询
                if (by < worldMinY || by >= worldMaxY) {
                    continue;
                }
                for (int bz = minZ; bz <= maxZ; bz++) {
                    // Chunk 直读 (借鉴 CE isOccluding 思路):跳过 getBlockAt 的 Block 对象分配
                    Material type = blockTypeAt(world, bx, by, bz);
                    if (type.isAir() || type == Material.WATER || type == Material.BUBBLE_COLUMN) {
                        continue;
                    }
                    if (!type.isCollidable()) {
                        continue;
                    }
                    // 快路径:occluding 方块 = 完整 1×1×1 碰撞,BB 已覆盖该格,必然碰撞
                    if (type.isOccluding()) {
                        return true;
                    }
                    // 非完整方块 (台阶/栅栏等):精确 AABB 相交检测 (慢路径,少量)
                    Block block = blockAt(world, bx, by, bz);
                    if (BoundingBox.of(block).overlaps(bb)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 按全局坐标直读方块材质。思路借鉴 CE 的 isOccluding (直读 chunk 数据,避免 getBlockAt 创建 Block 对象):
     * Paper 的 ChunkSnapshot(false,false,false,false) 只是轻量包装,内部直接引用 palette 数组
     * (无数据拷贝),getBlockType 为纯数组读取;命中 lastSnapshot 缓存免 chunk 查找。
     */
    private Material blockTypeAt(World world, int x, int y, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        org.bukkit.ChunkSnapshot snap = lastSnapshot;
        if (snap == null || cx != lastSnapshotX || cz != lastSnapshotZ) {
            snap = world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false, false);
            lastSnapshot = snap;
            lastSnapshotX = cx;
            lastSnapshotZ = cz;
        }
        return snap.getBlockType(x & 15, y, z & 15);
    }

    /** 按全局坐标取 Block (仅慢路径:非完整碰撞方块使用)。 */
    private Block blockAt(World world, int x, int y, int z) {
        return world.getChunkAt(x >> 4, z >> 4).getBlock(x & 15, y, z & 15);
    }

    /** 原版 tickClient 旋转:落地压扁为新版本特性;旋转按 master 1.8.9 原样复刻。 */
    private void tickRotation() {
        if (!prevVerticalCollision && onGround) {
            rotation.stretch *= 0.70f;
        }
        prevVerticalCollision = onGround;

        // 原版: rotX(度) = 360 * (-motionZ / (5*size)) → ωX(弧度) = -2π * motionZ / (5*size)
        //       rotZ(度) = 360 * ( motionX / (5*size)) → ωZ(弧度) =  2π * motionX / (5*size)
        // 每 tick 直接右乘 (quat = quat * Qx * Qz),无角速度累积/阻力
        float motionAngleX = (float) (-2 * Math.PI * motion.getZ() / (ROT_DIVISOR * size));
        float motionAngleZ = (float) (2 * Math.PI * motion.getX() / (ROT_DIVISOR * size));

        // JOML rotateXYZ(ax, 0, az) 生成 Qx*Qz;quat.mul(...) 右乘 → quat * Qx * Qz,与原版一致
        rotation.quat.mul(rotTemp.rotateXYZ(motionAngleX, 0, motionAngleZ));
    }

    /** 原版 collideWithNearbyEntities:推开附近实体;空矿车可骑乘。 */
    private void collideWithNearbyEntities(TumbleweedManager manager) {
        // 静止时无推力 (推力基于自身速度,此时约为 0),跳过实体探测
        double speedSq = motion.getX() * motion.getX() + motion.getZ() * motion.getZ();
        if (speedSq < 0.0005) {
            return;
        }

        double width = mcSize();
        // 复用 cachedLoc 避免新建 Location;手动偏移 y 到中心
        entity.getLocation(cachedLoc);
        cachedLoc.setY(cachedLoc.getY() + width / 2);
        double range = width / 2 + 0.2;

        Collection<Entity> nearby = entity.getWorld().getNearbyEntities(cachedLoc, range, range, range,
                e -> e != entity && !manager.isTumbleweed(e));

        for (Entity e : nearby) {
            // 空矿车:骑乘 (原版特性)
            if (e instanceof Minecart mc && mc.getPassengers().isEmpty()
                    && entity.getVehicle() == null
                    && mc.getVelocity().lengthSquared() > 0.01) {
                mc.addPassenger(entity);
                Vector vel = entity.getVelocity();
                vel.setY(vel.getY() + 0.25);
                entity.setVelocity(vel);
                continue;
            }

            // 推开附近生物 (原版仅推 canBePushed=true 的实体,玩家 canBePushed=false 不受推)
            double pushX = motion.getX() * 0.3;
            double pushZ = motion.getZ() * 0.3;
            if (pushX * pushX + pushZ * pushZ < 0.0001) {
                continue;
            }
            if (e instanceof LivingEntity living && !(e instanceof Player)) {
                Vector push = living.getVelocity();
                push.setX(push.getX() + pushX);
                push.setY(push.getY() + 0.15);
                push.setZ(push.getZ() + pushZ);
                living.setVelocity(push);
            }
        }
    }

    public boolean isInWater() {
        entity.getLocation(cachedLoc);
        int y = cachedLoc.getBlockY();
        if (y < 0 || y >= entity.getWorld().getMaxHeight()) {
            return false;
        }
        Material type = blockTypeAt(entity.getWorld(),
                cachedLoc.getBlockX(), y, cachedLoc.getBlockZ());
        return type == Material.WATER || type == Material.BUBBLE_COLUMN;
    }

    /** 原版 getDimensions:碰撞与渲染尺寸。 */
    public double mcSize() {
        return BASE_SIZE + size * (1 / 8d);
    }

    /** 更新 CE 判定用的 AABB 快照 (主线程物理后调用;cachedLoc 此时为最新位置)。 */
    public void updateCullAabb() {
        double w = mcSize() * 0.5;
        double h = mcSize();
        cullMinX = cachedLoc.getX() - w;
        cullMinY = cachedLoc.getY();
        cullMinZ = cachedLoc.getZ() - w;
        cullMaxX = cachedLoc.getX() + w;
        cullMaxY = cachedLoc.getY() + h;
        cullMaxZ = cachedLoc.getZ() + w;
    }

    /** 原版渲染: 1.0 + size/8。 */
    public float modelScale() {
        return 1.0f + size / 8f;
    }

    public Entity entity() {
        return entity;
    }

    public int size() {
        return size;
    }

    public Vector motion() {
        return motion;
    }

    public RotationState rotation() {
        return rotation;
    }

    public int fadeProgress() {
        return fadeProgress;
    }

    public boolean isFading() {
        return fading;
    }

    public float alpha() {
        return fading ? 1f - fadeProgress / (float) FADE_TIME : 1f;
    }

    public void setPersistent(boolean persistent) {
        this.persistent = persistent;
    }

    public boolean isPersistent() {
        return persistent;
    }

    public int tickIndex() {
        return tickIndex;
    }

    public void setTickIndex(int tickIndex) {
        this.tickIndex = tickIndex;
    }

    /** 渲染压扁:scaleY = stretch,scaleXZ = 2 - stretch (原版 render 逻辑)。 */
    public float renderScaleX() {
        return modelScale() * (2f - rotation.stretch);
    }

    public float renderScaleY() {
        return modelScale() * rotation.stretch;
    }

    public float renderScaleZ() {
        return modelScale() * (2f - rotation.stretch);
    }
}
