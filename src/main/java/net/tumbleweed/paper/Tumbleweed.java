package net.tumbleweed.paper;

import com.ticxo.modelengine.api.model.ActiveModel;
import net.tumbleweed.paper.model.RotationState;
import org.bukkit.GameRule;
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

import java.util.List;
import java.util.Random;

/**
 * 风滚草的物理与行为,忠实移植原版 EntityTumbleweed 的 tick 逻辑。
 *
 * 原版关键数值:
 *  - 风力: X/Z = -1/16 * windMod,windMod = 1.05 - 0.1 * rand(以实体 id 为种子)
 *  - 重力 0.012 / tick,摩擦 0.98 / tick
 *  - 落地反弹: windSpeed >= 0.05 时 y = max(-prevY*0.7, 0.24 - |size|*0.02),否则 y = -prevY*0.7
 *  - 水中:速度 xz * 0.95,y + 0.02,无风力
 *  - 卡墙或水中时年龄加速 8 倍;寿命 2 分钟 + 0~200 tick;之后 80 tick 淡出消失
 *  - 玩家离开 160 格后消失
 *  - 尺寸: mcSize = 0.75 + size * 1/8 (size 1-4)
 */
public class Tumbleweed {

    public static final int FADE_TIME = 4 * 20;       // 80 ticks 淡出
    private static final int DESPAWN_RANGE = 160;     // 脱离玩家范围
    private static final double BASE_SIZE = 3 / 4d;   // 0.75
    private static final double WIND = -1 / 16d;      // 基础风速
    private static final double GRAVITY = 0.012;
    private static final double FRICTION = 0.98;

    private final Entity entity;          // MythicMobs 载体实体 (Pig, NoAI)
    private final int size;
    private final double windMod;
    private final int lifetime;

    private int age;
    private int fadeProgress;
    private boolean fading;
    private boolean persistent;

    // 物理状态
    private final Vector motion = new Vector();
    private final Vector prevMotion = new Vector();
    private boolean onGround;
    private boolean horizontalCollision;
    private boolean prevVerticalCollision;

    // 旋转状态 (原版客户端逻辑,在服务端计算后交给 ModelEngine)
    private final RotationState rotation;
    private float angularSpeedX;
    private float angularSpeedZ;
    private final float rotOffsetX;
    private final float rotOffsetY;
    private final float rotOffsetZ;

    /** ModelEngine 活动模型引用 (由 ModelController 附加)。 */
    private ActiveModel model;

    private final Random random = new Random();

    public Tumbleweed(Entity entity, int size) {
        this.entity = entity;
        this.size = Math.max(1, Math.min(4, size));
        this.rotation = new RotationState();

        // 原版以实体 id 作为随机种子:windMod 与 lifetime 可复现
        Random seeded = new Random(entity.getEntityId());
        this.windMod = 1.05 - 0.1 * seeded.nextDouble();
        this.lifetime = 2 * 60 * 20 + seeded.nextInt(200);

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

        // 重力
        if (!isInWater()) {
            motion.setY(motion.getY() - GRAVITY);
        }

        prevMotion.copy(motion);
        moveEntity();

        // 风力
        double windX = WIND * windMod;
        double windZ = WIND * windMod;
        if (isInWater()) {
            motion.setX(motion.getX() * 0.95);
            motion.setZ(motion.getZ() * 0.95);
            motion.setY(motion.getY() + 0.02);
            windX = 0;
            windZ = 0;
        } else if (windX != 0 || windZ != 0) {
            motion.setX(windX);
            motion.setZ(windZ);
        }

        // 旋转 (原版 tickClient)
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

        // 摩擦
        motion.multiply(FRICTION, FRICTION, FRICTION);

        collideWithNearbyEntities(manager);

        // 卡墙或水中:老化加速
        age += (horizontalCollision || isInWater()) ? 8 : 1;
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

        // 无玩家时消失
        if (!persistent) {
            Player nearest = entity.getWorld().getPlayers().stream()
                    .filter(p -> p.isOnline() && p.getWorld().equals(entity.getWorld()))
                    .min((a, b) -> Double.compare(a.getLocation().distanceSquared(entity.getLocation()),
                            b.getLocation().distanceSquared(entity.getLocation())))
                    .orElse(null);
            if (nearest != null
                    && nearest.getLocation().distanceSquared(entity.getLocation()) > DESPAWN_RANGE * DESPAWN_RANGE) {
                manager.remove(this);
                return;
            }
        }

        trampleFarmland();
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

        Location target = new Location(entity.getWorld(), x, y, z, loc.getYaw(), loc.getPitch());
        entity.teleport(target);
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

    /** 原版 tickClient:旋转与落地压扁。 */
    private void tickRotation() {
        if (!prevVerticalCollision && onGround) {
            rotation.stretch *= 0.70f;
        }
        prevVerticalCollision = onGround;

        float motionAngleX = (float) (prevMotion.getZ() / (mcSize() * 0.5));
        float motionAngleZ = (float) (-prevMotion.getX() / (mcSize() * 0.5));

        if (onGround) {
            angularSpeedX = motionAngleX;
            angularSpeedZ = motionAngleZ;
        }
        if (isInWater()) {
            angularSpeedX += motionAngleX * 0.2f;
            angularSpeedZ += motionAngleZ * 0.2f;
        }

        float resistance = isInWater() ? 0.9f : 0.96f;
        angularSpeedX *= resistance;
        angularSpeedZ *= resistance;

        Quaternionf temp = new Quaternionf();
        temp.rotateXYZ(angularSpeedX, 0, angularSpeedZ);
        temp.mul(rotation.quat);
        rotation.quat = temp;
    }

    /** 原版 collideWithNearbyEntities:推开附近实体;空矿车可骑乘。 */
    private void collideWithNearbyEntities(TumbleweedManager manager) {
        double width = mcSize();
        Location loc = entity.getLocation();
        BoundingBox bb = new BoundingBox(
                loc.getX() - width / 2 - 0.2, loc.getY(),
                loc.getZ() - width / 2 - 0.2,
                loc.getX() + width / 2 + 0.2, loc.getY() + width + 1,
                loc.getZ() + width / 2 + 0.2);

        List<Entity> nearby = entity.getWorld().getNearbyEntities(bb,
                e -> e != entity && e.isPushable() && !manager.isTumbleweed(e));

        for (Entity e : nearby) {
            // 空矿车:骑乘 (原版特性)
            if (e instanceof Minecart mc && mc.getPassengers().isEmpty()
                    && entity.getVehicle() == null
                    && mc.getVelocity().lengthSquared() > 0.01) {
                mc.addPassenger(entity);
                motion.add(new Vector(0, 0.25, 0));
                continue;
            }

            // 推开附近实体 (玩家/生物)
            double pushX = motion.getX() * 0.3;
            double pushZ = motion.getZ() * 0.3;
            if (pushX * pushX + pushZ * pushZ < 0.0001) {
                continue;
            }
            if (e instanceof LivingEntity living) {
                Vector push = living.getVelocity().add(new Vector(pushX, 0.15, pushZ));
                living.setVelocity(push);
            }
        }
    }

    /** 落地践踏农田 (原版 FarmlandMixin:70% 概率 + 游戏规则 doMobGriefing)。 */
    private void trampleFarmland() {
        if (!onGround || !TumbleweedPlugin.getInstance().pluginConfig().isDamageCrops()) {
            return;
        }
        Location loc = entity.getLocation();
        if (entity.getWorld().getGameRuleValue(GameRule.DO_MOB_GRIEFING)
                && random.nextFloat() < 0.7F) {
            Block block = entity.getWorld().getBlockAt(loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());
            if (block.getType() == Material.FARMLAND) {
                block.setType(Material.DIRT);
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

    public ActiveModel getModel() {
        return model;
    }

    public void setModel(ActiveModel model) {
        this.model = model;
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
