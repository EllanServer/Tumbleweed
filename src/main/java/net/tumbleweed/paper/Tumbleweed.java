package net.tumbleweed.paper;

import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.tumbleweed.paper.model.RotationState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
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

    private BukkitFurniture furniture;    // craft-engine 家具 (显示 + 交互,CE 托管)

    private int age;
    private int fadeProgress;
    private boolean fading;
    private boolean persistent;
    private int physicsCycle; // 降频调度计数 (距玩家过远时每 N tick 跑一次物理)

    // 物理状态
    private final Vector motion = new Vector();
    private final Vector prevMotion = new Vector();
    private boolean onGround;
    private boolean horizontalCollision;
    private boolean prevVerticalCollision;

    // 旋转状态 (原版客户端逻辑,在服务端计算后交给 CE 家具渲染)
    private final RotationState rotation;
    private final float rotOffsetX;
    private final float rotOffsetY;
    private final float rotOffsetZ;

    private final Random random = new Random();

    // 性能:复用临时对象,避免每 tick 分配
    private final Quaternionf rotTemp = new Quaternionf();

    public Tumbleweed(Entity entity, int size) {
        this.entity = entity;
        this.size = Math.max(1, Math.min(4, size));
        this.rotation = new RotationState();

        // 原版以实体 id 作为随机种子:windMod 与 lifetime 可复现
        Random seeded = new Random(entity.getEntityId());
        this.windModX = 1.0 + 0.2 - 0.4 * seeded.nextDouble();
        this.windModZ = 1.0 + 0.2 - 0.4 * seeded.nextDouble();
        this.lifetime = 2 * 60 * 20;

        this.rotOffsetX = 360f * random.nextFloat();
        this.rotOffsetY = 360f * random.nextFloat();
        this.rotOffsetZ = 360f * random.nextFloat();
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

        // 践踏农田已由 MythicMobs 配置实现 (Tumbleweed.yml 的 TumbleweedTrample 技能)
        // 脱管检查 (玩家离开 110 格) 由 TumbleweedManager 统一处理,使用缓存的最近玩家距离
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
        Location loc = entity.getLocation();
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();

        double width = mcSize();
        BoundingBox bb = new BoundingBox(x - width / 2, y, z - width / 2, x + width / 2, y + width, z + width / 2);

        horizontalCollision = false;
        onGround = false;

        // X 轴
        if (motion.getX() != 0 && collidesWithBlocks(entity.getWorld(), bb.shift(motion.getX(), 0, 0))) {
            horizontalCollision = true;
            motion.setX(0);
        } else {
            x += motion.getX();
        }
        // Z 轴
        if (motion.getZ() != 0 && collidesWithBlocks(entity.getWorld(), bb.shift(0, 0, motion.getZ()))) {
            horizontalCollision = true;
            motion.setZ(0);
        } else {
            z += motion.getZ();
        }
        // Y 轴
        if (motion.getY() != 0 && collidesWithBlocks(entity.getWorld(), bb.shift(0, motion.getY(), 0))) {
            if (motion.getY() < 0) {
                onGround = true;
            }
            motion.setY(0);
        } else {
            y += motion.getY();
        }

        // 复用读取时的 Location,避免每 tick 额外分配
        loc.setX(x);
        loc.setY(y);
        loc.setZ(z);
        entity.teleport(loc);
    }

    private boolean collidesWithBlocks(World world, BoundingBox bb) {
        int minX = (int) Math.floor(bb.getMinX());
        int maxX = (int) Math.floor(bb.getMaxX());
        int minY = (int) Math.floor(bb.getMinY());
        int maxY = (int) Math.floor(bb.getMaxY());
        int minZ = (int) Math.floor(bb.getMinZ());
        int maxZ = (int) Math.floor(bb.getMaxZ());
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (type.isAir() || type == Material.WATER || type == Material.BUBBLE_COLUMN) {
                        continue;
                    }
                    BlockData data = block.getBlockData();
                    if (!data.getMaterial().isCollidable()) {
                        continue;
                    }
                    // 精确 AABB 相交检测 (细方块如花/草不影响)
                    BoundingBox blockBb = BoundingBox.of(block);
                    if (blockBb.overlaps(bb)) {
                        return true;
                    }
                }
            }
        }
        return false;
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
        // 原版 AABB 仅 x/z 扩展 0.2,y 不扩展;以 AABB 中心为探测中心避免头顶实体被误推
        Location center = entity.getLocation().add(0, width / 2, 0);
        double range = width / 2 + 0.2;

        // 26.2: getNearbyEntities(Location, dx, dy, dz, Predicate) 返回 Collection
        Collection<Entity> nearby = entity.getWorld().getNearbyEntities(center, range, range, range,
                e -> e != entity && !manager.isTumbleweed(e));

        for (Entity e : nearby) {
            // 空矿车:骑乘 (原版特性)
            if (e instanceof Minecart mc && mc.getPassengers().isEmpty()
                    && entity.getVehicle() == null
                    && mc.getVelocity().lengthSquared() > 0.01) {
                mc.addPassenger(entity);
                motion.add(new Vector(0, 0.25, 0));
                continue;
            }

            // 推开附近生物 (原版仅推 canBePushed=true 的实体,玩家 canBePushed=false 不受推)
            double pushX = motion.getX() * 0.3;
            double pushZ = motion.getZ() * 0.3;
            if (pushX * pushX + pushZ * pushZ < 0.0001) {
                continue;
            }
            if (e instanceof LivingEntity living && !(e instanceof Player)) {
                Vector push = living.getVelocity().add(new Vector(pushX, 0.15, pushZ));
                living.setVelocity(push);
            }
        }
    }

    public boolean isInWater() {
        Material type = entity.getLocation().getBlock().getType();
        return type == Material.WATER || type == Material.BUBBLE_COLUMN;
    }

    /** 原版 getDimensions:碰撞与渲染尺寸。 */
    public double mcSize() {
        return BASE_SIZE + size * (1 / 8d);
    }

    /** 原版渲染: 1.0 + size/8。 */
    public float modelScale() {
        return 1.0f + size / 8f;
    }

    public Entity entity() {
        return entity;
    }

    /** craft-engine 家具 (显示实体 + 交互 hitbox,由 CE 托管);未创建时为 null。 */
    public BukkitFurniture furniture() {
        return furniture;
    }

    public void setFurniture(BukkitFurniture furniture) {
        this.furniture = furniture;
    }

    /** 本 tick 是否在水平移动 (用于决定家具 moveTo 是否必要)。 */
    public boolean isMoving() {
        double x = motion.getX();
        double z = motion.getZ();
        return x * x + z * z > 0.0001;
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

    public float rotOffsetX() {
        return rotOffsetX;
    }

    public float rotOffsetY() {
        return rotOffsetY;
    }

    public float rotOffsetZ() {
        return rotOffsetZ;
    }
}
