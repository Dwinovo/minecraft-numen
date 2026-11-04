package com.dwinovo.numen.pathing.drive;

import java.util.List;
import java.util.Random;

import com.dwinovo.numen.api.entity.Controls;
import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.api.entity.Neck;
import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.BodySnapshot;
import com.dwinovo.numen.pathing.plan.Mobility;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Clearance;
import com.dwinovo.numen.pathing.world.Kinematics;
import com.dwinovo.numen.pathing.world.Kinematics.Keys;
import com.dwinovo.numen.pathing.world.Kinematics.Motion;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 单测里的滑翔身体:{@link Craft} 的模拟实现。一刻的顺序与服务端一样——控制器({@link Sortie})读状态、落键、转视角,然后 {@link #step} 把按着的键落到物理上:
 * 起跳、空中按下跳展开鞘翅、滑翔与烟花的推力走 {@link Kinematics#glide}(和控制器往前推演同一个步进器)、没展开时按原版的重力与阻力落下;
 * 碰撞按身体盒逐轴挤出去({@link Clearance}):脚碰到地面落地(停飞、按坠落距离伤血)、水平撞上方块按原版的动能公式伤血。
 * 控制器每一刻的决策是真的,物理的对错(推算与原版对不对得上)由 GameTest 里的对照用例守。
 *
 * <p>烟花一支推 10 到 21 刻(原版:{@code 10 × 飞行时长} 再加最多 11 刻的随机,实体的随机不定种子,同一趟每次飞出来的轨迹都不一样),随机用调用方给的种子:
 * 同一个种子同一个场景每次一样,场景换几个种子跑,结论要对真实里的各种推法都成立。
 */
final class Glider implements Craft {

    /** 滑翔姿势的身体:宽 0.6、高 0.6(原版 {@code FALL_FLYING} 姿势)。 */
    private static final BodyStats GLIDING = new BodyStats(EntityDimensions.scalable(0.6F, 0.6F).withEyeHeight(0.4F), 0.6, 0.42, 0.08, 0.1, 0.3, 4.5,
            false, false);
    /** 鞘翅的耐久上限。 */
    private static final int ELYTRA_DURABILITY = 432;
    private static final double GRAVITY = 0.08;
    private static final double JUMP = 0.42;
    /** 在地上、在空中水平速度每刻乘的系数(地面 0.6 × 0.91;空中 0.91)。 */
    private static final double GROUND_DRAG = 0.6 * 0.91;
    private static final double AIR_DRAG = 0.91;
    private static final double VERTICAL_DRAG = 0.98;
    /** 原版撞墙伤血:(撞前水平速度 − 撞后水平速度) × 10 − 3。 */
    private static final double KINETIC = 10.0;
    private static final double KINETIC_FREE = 3.0;
    private static final double MAX_HEALTH = 20.0;
    private static final int BISECT = 10;
    /** 身体周围加载多少个区块(流式加载的地形用)。 */
    private static final int VIEW = 8;

    private final Land land;
    private final Controls keys = new Controls();
    private final EditLedger ledger = new EditLedger();
    private final Random random;
    private final Dynamics dynamics;
    private final BodySnapshot snapshot;
    private Vec3 pos;
    private Vec3 velocity = Vec3.ZERO;
    private Neck neck;
    private boolean onGround = true;
    private boolean gliding;
    private boolean jumpedBefore;
    private boolean wearsElytra = true;
    private double fall;
    private double health = MAX_HEALTH;
    private double lowest = MAX_HEALTH;
    private int wear;
    private int glidingTicks;
    private int rockets;
    private int burning;
    private int fired;
    private boolean collided;
    private boolean pinned;
    /** 伤过血的每一次:第几刻、怎么伤的。 */
    private final StringBuilder hurts = new StringBuilder();
    /** 最近十几刻的位置、速度、俯仰,伤血时附在 {@link #hurts} 里,好看是怎么撞上去的。 */
    private final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
    private int ticks;

    /** 站在 {@code at}(脚底中心)、朝 {@code yaw}、背着 {@code rockets} 支烟花的身体;{@code seed} 定每支烟花多推几刻。 */
    Glider(Land land, Vec3 at, float yaw, int rockets, long seed) {
        Vanilla.boot();
        this.random = new Random(seed);
        this.land = land;
        this.pos = at;
        this.neck = Neck.at(yaw, 0);
        this.rockets = rockets;
        this.snapshot = new BodySnapshot(Vanilla.SURVIVAL, GameType.SURVIVAL, 20, 3, 1, 20, 0, List.of(), Fixtures.MINING, Fixtures.BREATH, mobility());
        this.dynamics = Dynamics.gliding(GRAVITY, 10, 3);
    }

    /** 走到了 {@code cell}:起飞点是 {@link Glide#admit} 挑的,从身体站的地方走过去是走路那一段({@link Voyage})的事,这里直接放过去。 */
    Glider standAt(BlockPos cell) {
        pos = Vec3.atBottomCenterOf(cell);
        return this;
    }

    /**
     * 悬在原地不动(不受重力、速度归零):用来摆"推力与撞上去的反力正好抵消"的被顶住的局面,身体盒仍与方块碰撞。
     */
    Glider pinned() {
        pinned = true;
        velocity = Vec3.ZERO;
        return this;
    }

    /** 身上的烟花被拿光了。 */
    void loseRockets() {
        rockets = 0;
    }

    /** 没穿鞘翅。 */
    Glider bare() {
        wearsElytra = false;
        return this;
    }

    // ==================== 一刻 ====================

    /** 把这一刻按着的键落到物理上:控制器每刻先 {@code tick},身体再 {@code step}。 */
    void step() {
        ticks++;
        recent.addLast(String.format("(%.1f,%.2f,%.1f v %.2f,%.2f,%.2f p %.0f)", pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z, neck.pitch()));
        if (recent.size() > 10) {
            recent.removeFirst();
        }
        boolean jump = keys.held(Key.JUMP);
        boolean pressed = jump && !jumpedBefore;
        jumpedBefore = jump;
        Vec3 before = pos;
        land.follow(pos, VIEW);
        if (gliding) {
            glide();
        } else {
            fallFree(pressed);
        }
        if (pinned) {
            velocity = Vec3.ZERO;
        }
        if (pos.y < before.y - 100) {
            throw new IllegalStateException("掉出世界了:" + pos);
        }
        lowest = Math.min(lowest, health);
    }

    /** 没展开:地上站着或空中自由落体;按下跳,地上是起跳,空中是展开鞘翅(穿着没坏的鞘翅)。 */
    private void fallFree(boolean pressed) {
        Vec3 v = velocity;
        if (pressed && onGround) {
            v = new Vec3(v.x, JUMP, v.z);
            onGround = false;
        } else if (pressed && !onGround && wearsElytra && wear < ELYTRA_DURABILITY - 1) {
            gliding = true;
            glidingTicks = 0;
            glide();
            return;
        }
        Vec3 from = pos;
        Vec3 moved = move(v, standing());
        double drag = onGround ? GROUND_DRAG : AIR_DRAG;
        velocity = new Vec3(moved.x * drag, (moved.y - GRAVITY) * VERTICAL_DRAG, moved.z * drag);
        if (onGround) {
            hurtFall();
        } else {
            fall = dynamics.accrue(fall, new Motion(from, v), new Motion(pos, velocity));
        }
    }

    /** 滑翔一刻:{@link Kinematics#glide} 给出这一刻的速度,再带着碰撞挪过去。 */
    private void glide() {
        Motion start = new Motion(pos, velocity);
        fall = dynamics.settle(fall, start);
        boolean rocket = burning > 0;
        if (rocket) {
            burning--;
        }
        Motion next = Kinematics.glide(start, new Keys(0, 0, false, false, false, neck.yaw(), neck.pitch(), rocket), GRAVITY);
        Vec3 want = next.pos().subtract(pos);
        double speedBefore = Math.hypot(want.x, want.z);
        Vec3 moved = move(want, GLIDING);
        // move 之后速度:被挡的轴归零;没被挡的那部分照 glide 给的速度(含烟花)
        Vec3 v = next.velocity();
        velocity = new Vec3(moved.x == want.x ? v.x : 0, moved.y == want.y ? v.y : 0, moved.z == want.z ? v.z : 0);
        if (collided && (moved.x != want.x || moved.z != want.z)) {
            double speedAfter = Math.hypot(velocity.x, velocity.z);
            double damage = (speedBefore - speedAfter) * KINETIC - KINETIC_FREE;
            if (damage > 0) {
                health -= damage;
                hurts.append(String.format(" 第%d刻撞上方块 -%.1f(撞前水平速度 %.2f)%s", ticks, damage, speedBefore, hurts.isEmpty() ? " 近况 " + String.join(" ", recent) : ""));
            }
        }
        if (++glidingTicks % 20 == 0) {
            wear++;
        }
        if (onGround) {
            gliding = false;
            hurtFall();
        } else {
            fall = dynamics.accrue(fall, start, new Motion(pos, velocity));
        }
    }

    /** 落地:按攒下的坠落距离伤血,清零(原版落地那一刻不再攒这一刻落下的)。 */
    private void hurtFall() {
        double damage = dynamics.hurt(fall);
        if (damage > 0) {
            hurts.append(String.format(" 第%d刻落地 -%.1f(坠落距离 %.1f)", ticks, damage, fall));
        }
        health -= damage;
        fall = 0;
    }

    // ==================== 碰撞 ====================

    private BodyStats standing() {
        return Vanilla.SURVIVAL;
    }

    /** 身体盒在 {@code at} 与方块有没有交叠。 */
    private boolean blocked(BodyStats stats, Vec3 at) {
        return !Clearance.blockersAlong(land, stats, at.x, at.y, at.z, at.x, at.y, at.z).isEmpty();
    }

    /**
     * 逐轴挤着挪:先竖直、再 x、再 z,每个轴取走得通的最大一段(二分)。交回实际挪了多少;竖直往下被挡就是落地。
     */
    private Vec3 move(Vec3 want, BodyStats stats) {
        collided = false;
        double dy = along(stats, 1, want.y);
        onGround = !pinned && want.y < 0 && dy != want.y;
        double dx = along(stats, 0, want.x);
        double dz = along(stats, 2, want.z);
        collided = dx != want.x || dy != want.y || dz != want.z;
        return new Vec3(dx, dy, dz);
    }

    /** 沿第 {@code axis} 轴从此刻的位置挪 {@code delta},被挡就挪到刚好贴上的地方;位置当场更新。 */
    private double along(BodyStats stats, int axis, double delta) {
        if (delta == 0) {
            return 0;
        }
        Vec3 full = shifted(axis, delta);
        if (!blocked(stats, full)) {
            pos = full;
            return delta;
        }
        double lo = 0;
        double hi = 1;
        for (int i = 0; i < BISECT; i++) {
            double mid = (lo + hi) / 2;
            if (blocked(stats, shifted(axis, delta * mid))) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        pos = shifted(axis, delta * lo);
        return delta * lo;
    }

    private Vec3 shifted(int axis, double delta) {
        return switch (axis) {
            case 0 -> pos.add(delta, 0, 0);
            case 1 -> pos.add(0, delta, 0);
            default -> pos.add(0, 0, delta);
        };
    }

    // ==================== 给测试看的 ====================

    /** 到此刻为止最少剩几点血。 */
    double lowestHealth() {
        return lowest;
    }

    /** 伤过血的每一次(没伤过为空),失败信息用。 */
    String hurts() {
        return hurts.toString();
    }

    int ticks() {
        return ticks;
    }

    // ==================== Craft ====================

    @Override
    public String who() {
        return "glider";
    }

    @Override
    public String describe() {
        return String.format("身体 %.1f,%.1f,%.1f %s 血 %.1f", pos.x, pos.y, pos.z, onGround ? "地上" : "空中", health);
    }

    @Override
    public Terrain world() {
        return land;
    }

    @Override
    public BodySnapshot snapshot() {
        return new BodySnapshot(snapshot.stats(), snapshot.gameMode(), (float) health, snapshot.safeFallDistance(), snapshot.fallDamageMultiplier(), snapshot.foodLevel(),
                snapshot.waterMovementEfficiency(), snapshot.inventory(), snapshot.mining(), snapshot.breath(), mobility());
    }

    @Override
    public Mobility mobility() {
        return new Mobility(false, false, Mobility.VANILLA_FLYING_SPEED, wearsElytra ? Math.max(0, ELYTRA_DURABILITY - wear - 1) : 0, rockets, rockets == 0 ? 0 : 10);
    }

    @Override
    public Vec3 position() {
        return pos;
    }

    @Override
    public Vec3 velocity() {
        return velocity;
    }

    @Override
    public BlockPos cell() {
        return BlockPos.containing(pos);
    }

    @Override
    public AABB box() {
        BodyStats stats = gliding ? GLIDING : standing();
        double half = stats.width() / 2;
        return new AABB(pos.x - half, pos.y, pos.z - half, pos.x + half, pos.y + stats.height(), pos.z + half);
    }

    @Override
    public boolean onGround() {
        return onGround;
    }

    @Override
    public boolean gliding() {
        return gliding;
    }

    @Override
    public float yaw() {
        return neck.yaw();
    }

    @Override
    public float pitch() {
        return neck.pitch();
    }

    @Override
    public double eyeHeight() {
        return standing().eyeHeight();
    }

    @Override
    public double gravity() {
        return GRAVITY;
    }

    @Override
    public double fallen() {
        return fall;
    }

    @Override
    public float health() {
        return (float) health;
    }

    @Override
    public int wear() {
        return wear;
    }

    @Override
    public Controls keys() {
        return keys;
    }

    @Override
    public void steer(float yaw, float pitch) {
        neck = neck.toward(yaw, pitch);
    }

    @Override
    public Neck neck() {
        return neck;
    }

    @Override
    public boolean holdRocket() {
        return rockets > 0;
    }

    @Override
    public boolean launchRocket() {
        if (rockets == 0) {
            return false;
        }
        rockets--;
        fired++;
        burning = 10 + random.nextInt(12);
        return true;
    }

    @Override
    public EditLedger ledger() {
        return ledger;
    }

    /** 放过几支烟花。 */
    int fired() {
        return fired;
    }
}
