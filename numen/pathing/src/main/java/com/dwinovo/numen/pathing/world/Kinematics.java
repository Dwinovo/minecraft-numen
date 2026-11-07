package com.dwinovo.numen.pathing.world;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 身体怎么动:全模块只在这里算一份。读身体的量({@link BodyStats}:移动速度、起跳力度、重力),照原版逐刻推,规划定价、搜索估价、
 * 执行预判落点、看护定期限用的都是它。纯函数,不接触实体与世界;脚下方块的量(步速系数、起跳系数、摩擦)由调用方从方块上读了交进来。
 *
 * <p>单位:速度是格每刻,耗时是刻(20 刻一秒)。公式取自原版 {@code LivingEntity.travel}、{@code moveRelative}、
 * {@code getFrictionInfluencedSpeed}:每刻先把这一刻的输入加速度加进速度、按这个速度移动,再乘摩擦与空气阻力。
 * <ul>
 *   <li>水平:地上每刻的加速度是移动速度乘 {@code 0.216 / 摩擦³},前进键的输入再乘 0.98;速度每刻乘"方块摩擦 × 0.91"。
 *       稳态下每刻走的距离是加速度除以(1 − 摩擦 × 0.91);</li>
 *   <li>竖直:起跳速度是起跳力度乘脚下方块的起跳系数,每刻先按当前速度移动,再减重力、乘 0.98;</li>
 *   <li>攀爬:往上每刻 (0.2 − 重力) × 0.98,往下速度上限 0.15。</li>
 * </ul>
 *
 * <p>这里算的是几何上的最短耗时:不含起步加速、转身与跳的时机,规划拿它当一步的价钱、看护拿它的倍数当期限。
 *
 * <p>空中另有一族逐刻步进({@link #fly}、{@link #glide}、{@link #boost}):状态(位置与速度)与这一刻的输入进,下一刻的状态出,
 * 公式取自原版 {@code Player.travel} 的飞行分支、{@code LivingEntity.travel} 的滑翔分支与 {@code FireworkRocketEntity} 的推力,
 * float 与 double 的混用也照原版,所以与真身体逐刻对得上(GameTest 对照)。步进器不碰世界:撞方块、落地算不进去,要判撞不撞的另扫碰撞({@link Clearance})。
 */
public final class Kinematics {

    /** 原版方块默认摩擦({@code Block.friction})。规划按它定价。 */
    public static final double DEFAULT_FRICTION = 0.6;
    /** 空中、水平方向每刻乘的空气阻力。 */
    public static final double AIR_DRAG = 0.91;
    /** 竖直方向每刻乘的空气阻力。 */
    private static final double VERTICAL_DRAG = 0.98;
    /** 前进键的输入强度({@code LivingEntity.aiStep} 把输入乘 0.98)。 */
    private static final double INPUT = 0.98;
    /** 地上加速度公式里的系数({@code getFrictionInfluencedSpeed} 的 0.21600002)。 */
    private static final double GROUND_COEFFICIENT = 0.21600002;
    /** 空中每刻的加速度:走与疾跑({@code Player.getFlyingSpeed})。 */
    private static final double AIR_ACCELERATION = 0.02;
    private static final double AIR_ACCELERATION_SPRINTING = 0.026;
    /** 疾跑让移动速度乘的倍数:原版加的是 +0.3 的"乘总量"修饰符。1.21.1 里这个修饰符是 {@code LivingEntity} 的私有常量,没有公开的属性或常量能读。 */
    public static final double SPRINT_MULTIPLIER = 1.3;
    /** 水里的摩擦,与水下移动效率 1 时的摩擦(正好是陆上的 0.6 × 0.91)。 */
    private static final double WATER_FRICTION = 0.8;
    private static final double WATER_FRICTION_EFFICIENT = 0.54600006;
    /** 水里的基础加速度。 */
    private static final double WATER_ACCELERATION = 0.02;
    /** 在梯子、藤蔓上往上顶:竖直速度置为 0.2 之后再走一刻重力与阻力。 */
    private static final double CLIMB_UP_VELOCITY = 0.2;
    /** 在梯子、藤蔓上往下滑的速度上限。 */
    private static final double CLIMB_DOWN_SPEED = 0.15;
    /** 两次起跳之间至少隔这么多刻:原版起跳后 {@code noJumpDelay} 置为 10,按着跳也要等它减到 0。 */
    public static final int JUMP_DELAY = 10;
    /** 速度小于它原版就归零。 */
    public static final double REST = 0.003;
    /** 玩家的水平速度,平方小于它原版就把水平两个分量一起归零({@code LivingEntity.aiStep};即水平速度小于 {@link #REST})。 */
    private static final double HORIZONTAL_REST_SQR = 9.0E-6;

    private Kinematics() {}

    // ==================== 水平 ====================

    /** 地上按前进键一刻加的速度:{@code speed} 是移动速度,{@code friction} 是脚下方块的摩擦。 */
    public static double groundAcceleration(double speed, double friction) {
        return speed * (GROUND_COEFFICIENT / (friction * friction * friction)) * INPUT;
    }

    /** 地上每刻速度乘的系数:方块摩擦乘空气阻力。 */
    public static double groundDrag(double friction) {
        return friction * AIR_DRAG;
    }

    /** 空中按前进键一刻加的速度。 */
    public static double airAcceleration(boolean sprinting) {
        return (sprinting ? AIR_ACCELERATION_SPRINTING : AIR_ACCELERATION) * INPUT;
    }

    /** 地上一直按前进键,稳态下每刻走多远(格):加速度除以(1 − 摩擦 × 空气阻力)。 */
    public static double steadyStride(double speed, double friction) {
        return groundAcceleration(speed, friction) / (1 - groundDrag(friction));
    }

    /**
     * 地上以 {@code speed}(格每刻)冲着,要停下来最少滑出多远(格):决定得晚一刻,先按惯性滑一刻(速度乘地上的阻力),
     * 之后每刻按住反向键减去一刻的加速度({@link #groundAcceleration},按走的速度,反向键没有疾跑加成),直到停住。
     * 步态拿它判冲劲接不接得住:停下的距离要小于身体在落点格里能多滑出去而不碰到邻格的余量。
     */
    public static double stopDistance(BodyStats body, double speed) {
        double drag = groundDrag(DEFAULT_FRICTION);
        double brake = groundAcceleration(body.movementSpeed(), DEFAULT_FRICTION);
        double v = speed * drag;
        double distance = v;
        while (v > REST) {
            v = Math.max(0, v - brake);
            distance += v;
            v *= drag;
        }
        return distance;
    }

    /** 平地一直按前进键,疾跑({@code sprinting})或平走时每刻加的速度。 */
    public static double pushAcceleration(BodyStats body, boolean sprinting) {
        return groundAcceleration(body.movementSpeed() * (sprinting ? SPRINT_MULTIPLIER : 1), DEFAULT_FRICTION);
    }

    /** 平地疾跑稳态的速度(格每刻)。 */
    public static double sprintSpeed(BodyStats body) {
        return steadyStride(body.movementSpeed() * SPRINT_MULTIPLIER, DEFAULT_FRICTION);
    }

    /** 平地走的稳态速度(格每刻)。 */
    public static double walkSpeed(BodyStats body) {
        return steadyStride(body.movementSpeed(), DEFAULT_FRICTION);
    }

    /** 平地走一格要几刻。 */
    public static double walkTicksPerBlock(BodyStats body) {
        return 1 / walkSpeed(body);
    }

    /** 平地疾跑一格要几刻。 */
    public static double sprintTicksPerBlock(BodyStats body) {
        return 1 / sprintSpeed(body);
    }

    /** 平地潜行一格要几刻:移动输入乘身体的潜行倍数({@link BodyStats#sneakingSpeed})。 */
    public static double sneakTicksPerBlock(BodyStats body) {
        return 1 / (steadyStride(body.movementSpeed(), DEFAULT_FRICTION) * body.sneakingSpeed());
    }

    /**
     * 水里走或游一格要几刻,照原版水里的移动({@code LivingEntity.travel}):摩擦 0.8 与加速度 0.02,
     * 水下移动效率 {@code efficiency}(属性值,夹在 0 到 1)把两者向陆上的值插过去,1 时与陆上平走一样快。
     */
    public static double wadeTicksPerBlock(BodyStats body, double efficiency) {
        efficiency = Math.max(0, Math.min(1, efficiency));
        double friction = WATER_FRICTION + (WATER_FRICTION_EFFICIENT - WATER_FRICTION) * efficiency;
        double acceleration = (WATER_ACCELERATION + (body.movementSpeed() - WATER_ACCELERATION) * efficiency) * INPUT;
        return 1 / (acceleration / (1 - friction));
    }

    // ==================== 竖直 ====================

    /** 起跳的初速度:起跳力度乘脚下方块的起跳系数({@code LivingEntity.getJumpPower})。 */
    public static double jumpVelocity(BodyStats body, double blockJumpFactor) {
        return body.jumpStrength() * blockJumpFactor;
    }

    /**
     * 从地面起跳,脚能升到的最高处(相对起跳时的脚):每刻先按当前速度移动,再减重力、乘空气阻力,速度不再为正时到顶。
     * 原版玩家在普通方块上约 1.252,蜂蜜块上只有一半的力度。
     */
    public static double jumpHeight(BodyStats body, double blockJumpFactor) {
        double velocity = jumpVelocity(body, blockJumpFactor);
        double rise = 0;
        while (velocity > 0) {
            rise += velocity;
            velocity = (velocity - body.gravity()) * VERTICAL_DRAG;
        }
        return rise;
    }

    /**
     * 起跳之后脚第一次升到 {@code height} 格高要几刻(最后一刻按比例插值);升不到是无穷。这是往上每升一格的下界:
     * 不论水平怎么走,升一格不会比这更快。
     */
    public static double riseTicks(BodyStats body, double blockJumpFactor, double height) {
        double velocity = jumpVelocity(body, blockJumpFactor);
        double risen = 0;
        int ticks = 0;
        while (velocity > 0) {
            if (risen + velocity >= height) {
                return ticks + (height - risen) / velocity;
            }
            risen += velocity;
            ticks++;
            velocity = (velocity - body.gravity()) * VERTICAL_DRAG;
        }
        return Double.POSITIVE_INFINITY;
    }

    /**
     * 从地面起跳到落在比起跳时高 {@code rise} 格的平面上要几刻(整刻数:原版一刻一刻地走,过了顶点往下落、脚第一次不高于那个平面的那一刻
     * 落地)。原版玩家落在 +1 上是 9 刻,落在同一高度上是 12 刻;顶点够不着那个平面是无穷。
     */
    public static double landTicks(BodyStats body, double blockJumpFactor, double rise) {
        double velocity = jumpVelocity(body, blockJumpFactor);
        double y = 0;
        double apex = 0;
        for (int ticks = 1; ticks < 200; ticks++) {
            y += velocity;
            apex = Math.max(apex, y);
            if (velocity < 0 && y <= rise) {
                return apex >= rise ? ticks : Double.POSITIVE_INFINITY;
            }
            velocity = (velocity - body.gravity()) * VERTICAL_DRAG;
        }
        return Double.POSITIVE_INFINITY;
    }

    /**
     * 一连跳上去的一步要几刻:落到那一高度的工夫,不短于两次起跳之间的间隔({@link #JUMP_DELAY})。一格一格往上跳的路(台阶、
     * 垫柱)每一步都是它。
     */
    public static double jumpCycleTicks(BodyStats body, double blockJumpFactor, double rise) {
        return Math.max(landTicks(body, blockJumpFactor, rise), JUMP_DELAY);
    }

    /**
     * 自由下落 {@code distance} 格要几刻:从静止起照原版逐刻先按当前速度移动、再减重力乘阻力,最后一刻按比例插值。
     */
    public static double fallTicks(double gravity, double distance) {
        if (distance <= 0) {
            return 0;
        }
        double left = distance;
        double velocity = 0;
        int ticks = 0;
        while (velocity <= 0 || left > velocity) {
            left -= velocity;
            ticks++;
            velocity = (velocity + gravity) * VERTICAL_DRAG;
        }
        return ticks + left / velocity;
    }

    /** 从脚高 {@code y}、竖直速度 {@code vy} 落到 {@code landingY} 要几刻(原版先移动,再减重力乘阻力;至少一刻)。 */
    public static int ticksToLand(double y, double vy, double landingY, double gravity, int horizon) {
        int ticks = 0;
        while (y > landingY && ticks < horizon) {
            y += vy;
            vy = (vy - gravity) * VERTICAL_DRAG;
            ticks++;
        }
        return Math.max(ticks, 1);
    }

    /** 顺着梯子、藤蔓往上爬一格要几刻。 */
    public static double climbUpTicksPerBlock(BodyStats body) {
        return 1 / ((CLIMB_UP_VELOCITY - body.gravity()) * VERTICAL_DRAG);
    }

    /** 顺着梯子、藤蔓往下滑一格要几刻。 */
    public static double climbDownTicksPerBlock() {
        return 1 / CLIMB_DOWN_SPEED;
    }

    // ==================== 空中:飞行、滑翔、烟花 ====================

    /** 飞行时水平速度每刻乘的阻力:原版 {@code travel} 里的 {@code 0.91F}(空中的摩擦)。 */
    public static final double FLY_DRAG = 0.91F;
    /** 飞行时竖直速度每刻乘的系数({@code Player.travel} 飞行分支的 {@code d2 * 0.6})。 */
    public static final double FLY_VERTICAL_DRAG = 0.6;
    /** 飞着按跳或潜行每刻加的竖直速度是飞行速度的几倍({@code LocalPlayer.aiStep} 的 {@code * 3.0F})。 */
    public static final float FLY_LIFT = 3.0F;
    /** 疾跑时飞行速度翻倍({@code Player.getFlyingSpeed})。 */
    private static final float FLY_SPRINT = 2.0F;
    /** 滑翔时水平、竖直速度每刻乘的系数({@code LivingEntity.travel} 滑翔分支末尾的 {@code 0.99F, 0.98F, 0.99F})。 */
    private static final double GLIDE_HORIZONTAL_DRAG = 0.99F;
    private static final double GLIDE_VERTICAL_DRAG = 0.98F;
    /** 一刻里每个方向键的输入强度(原版 {@code LivingEntity.aiStep} 把 {@code xxa}、{@code zza} 乘 {@code 0.98F})。 */
    private static final float KEY_INPUT = 0.98F;
    /** 角度转弧度,{@code Entity.getInputVector}(移动输入按偏航转)的写法:{@code (float) Math.PI / 180F}。 */
    private static final float DEGREES = (float) Math.PI / 180F;
    /** 角度转弧度,视线与滑翔俯仰({@code Entity.calculateViewVector}、{@code LivingEntity.travel})的写法:{@code (float) (Math.PI / 180.0)}。 */
    private static final float VIEW_DEGREES = (float) (Math.PI / 180.0);

    /** 空中一刻开始时身体的状态:位置(脚底中心)与速度(格每刻)。 */
    public record Motion(Vec3 pos, Vec3 velocity) {}

    /**
     * 一刻里的输入:前后左右(各取 -1、0、1,正的是前、左)、跳、潜行、疾跑、此刻的偏航与俯仰(度),以及这一刻有没有一支烟花在推
     * ({@link #glide} 在这一刻的滑翔之后加上烟花的推力)。
     */
    public record Keys(int forward, int left, boolean jump, boolean sneak, boolean sprint, float yaw, float pitch, boolean rocket) {

        /** 什么键也不按,朝 {@code yaw}、{@code pitch}。 */
        public static Keys idle(float yaw, float pitch) {
            return new Keys(0, 0, false, false, false, yaw, pitch, false);
        }

        /** 同样的键,朝 {@code yaw}、{@code pitch}。 */
        public Keys facing(float yaw, float pitch) {
            return new Keys(forward, left, jump, sneak, sprint, yaw, pitch, rocket);
        }

        /** 同样的键,这一刻有没有一支烟花挂在身上推({@code rocket} 的另一个意思:候选里它是"现在放一支",步进时它是"正在推")。 */
        public Keys burning(boolean on) {
            return new Keys(forward, left, jump, sneak, sprint, yaw, pitch, on);
        }
    }

    /**
     * 创造飞行走一刻(原版:{@code LocalPlayer.aiStep} 先往竖直速度里加按键的 ±3 × 飞行速度,
     * {@code LivingEntity.aiStep} 把太小的速度归零(见 {@link #settle}),{@code Player.travel} 的飞行分支再走一遍
     * 空中的 {@code LivingEntity.travel}——水平先把输入加速度加进速度、按这个速度挪,再乘 {@code 0.91F};竖直挪的是加过按键的速度,
     * 之后速度只剩六成)。不含碰撞。
     *
     * @param flyingSpeed 能力里的飞行速度({@code Abilities.getFlyingSpeed},原版 0.05)
     */
    public static Motion fly(Motion state, Keys keys, float flyingSpeed) {
        int lift = (keys.jump() ? 1 : 0) - (keys.sneak() ? 1 : 0);
        double vy = state.velocity().y;
        if (lift != 0) {
            vy += (double) ((float) lift * flyingSpeed * FLY_LIFT);
        }
        Vec3 start = settle(new Vec3(state.velocity().x, vy, state.velocity().z));
        float speed = keys.sprint() ? flyingSpeed * FLY_SPRINT : flyingSpeed;
        Vec3 input = relative(new Vec3(keys.left() * KEY_INPUT, 0.0, keys.forward() * KEY_INPUT), speed, keys.yaw());
        Vec3 moving = new Vec3(start.x + input.x, start.y, start.z + input.z);
        return new Motion(state.pos().add(moving), new Vec3(moving.x * FLY_DRAG, start.y * FLY_VERTICAL_DRAG, moving.z * FLY_DRAG));
    }

    /**
     * 滑翔走一刻({@code LivingEntity.travel} 的滑翔分支):重力按俯仰折一部分、俯冲时把下落的速度转成前进、抬头时把前进的速度转成上升,
     * 水平速度向视线的水平方向靠拢,最后各乘阻力、按这个速度挪。有烟花在推({@link Keys#rocket})就在这之后加上它的推力({@link #boost},
     * 原版烟花实体在身体的这一刻之后才刻)。不含碰撞。
     *
     * @param gravity 身体的重力({@code Attributes.GRAVITY},原版 0.08)
     */
    public static Motion glide(Motion state, Keys keys, double gravity) {
        Vec3 start = settle(state.velocity());
        Vec3 look = view(keys.pitch(), keys.yaw());
        float pitch = keys.pitch() * VIEW_DEGREES;
        double lookHorizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        double speedHorizontal = start.horizontalDistance();
        double lookLength = look.length();
        double cos = Math.cos((double) pitch);
        cos = cos * cos * Math.min(1.0, lookLength / 0.4);
        Vec3 v = start.add(0.0, gravity * (-1.0 + cos * 0.75), 0.0);
        if (v.y < 0.0 && lookHorizontal > 0.0) {
            double dive = v.y * -0.1 * cos;
            v = v.add(look.x * dive / lookHorizontal, dive, look.z * dive / lookHorizontal);
        }
        if (pitch < 0.0F && lookHorizontal > 0.0) {
            double climb = speedHorizontal * (double) (-Mth.sin(pitch)) * 0.04;
            v = v.add(-look.x * climb / lookHorizontal, climb * 3.2, -look.z * climb / lookHorizontal);
        }
        if (lookHorizontal > 0.0) {
            v = v.add((look.x / lookHorizontal * speedHorizontal - v.x) * 0.1, 0.0,
                    (look.z / lookHorizontal * speedHorizontal - v.z) * 0.1);
        }
        v = v.multiply(GLIDE_HORIZONTAL_DRAG, GLIDE_VERTICAL_DRAG, GLIDE_HORIZONTAL_DRAG);
        return new Motion(state.pos().add(v), keys.rocket() ? boost(v, look) : v);
    }

    /** 视线的方向({@code Entity.calculateViewVector}):各分量按 float 乘,再转成 double。 */
    public static Vec3 view(float pitch, float yaw) {
        float pitchRadians = pitch * VIEW_DEGREES;
        float yawRadians = -yaw * VIEW_DEGREES;
        float yawCos = Mth.cos(yawRadians);
        float yawSin = Mth.sin(yawRadians);
        float pitchCos = Mth.cos(pitchRadians);
        float pitchSin = Mth.sin(pitchRadians);
        return new Vec3((double) (yawSin * pitchCos), (double) (-pitchSin), (double) (yawCos * pitchCos));
    }

    /** 一支挂在身上的烟花这一刻给滑翔中的身体加的速度({@code FireworkRocketEntity.tick}):朝视线的方向,把速度往每刻 1.5 格拉。 */
    public static Vec3 boost(Vec3 velocity, Vec3 look) {
        return velocity.add(look.x * 0.1 + (look.x * 1.5 - velocity.x) * 0.5, look.y * 0.1 + (look.y * 1.5 - velocity.y) * 0.5,
                look.z * 0.1 + (look.z * 1.5 - velocity.z) * 0.5);
    }

    /**
     * 原版每刻开头把太小的速度归零({@code LivingEntity.aiStep}):玩家的水平速度按整体看,平方小于 {@link #HORIZONTAL_REST_SQR}
     * 两个分量一起归零(各自小于 {@link #REST} 而合起来不小的,照样留着);竖直分量小于 {@link #REST} 归零。
     */
    private static Vec3 settle(Vec3 v) {
        boolean still = v.horizontalDistanceSqr() < HORIZONTAL_REST_SQR;
        return new Vec3(still ? 0.0 : v.x, Math.abs(v.y) < REST ? 0.0 : v.y, still ? 0.0 : v.z);
    }

    /** 把前后左右的输入按飞行速度与偏航转成世界里的加速度({@code Entity.getInputVector}):输入向量长过 1 的先归一。 */
    private static Vec3 relative(Vec3 input, float speed, float yaw) {
        double length = input.lengthSqr();
        if (length < 1.0E-7) {
            return Vec3.ZERO;
        }
        Vec3 scaled = (length > 1.0 ? input.normalize() : input).scale(speed);
        float sin = Mth.sin(yaw * DEGREES);
        float cos = Mth.cos(yaw * DEGREES);
        return new Vec3(scaled.x * cos - scaled.z * sin, scaled.y, scaled.z * cos + scaled.x * sin);
    }

    /** 飞着一直按住前进,疾跑({@code sprinting})或平飞时水平稳态速度(格每刻):每刻加的速度除以(1 − {@link #FLY_DRAG})。 */
    public static double flySpeed(float flyingSpeed, boolean sprinting) {
        return (double) KEY_INPUT * (double) (flyingSpeed * (sprinting ? FLY_SPRINT : 1.0F)) / (1 - FLY_DRAG);
    }

    /** 飞着一直按住跳(或潜行),竖直稳态速度(格每刻):每刻加的 3 × 飞行速度除以(1 − 0.6)。 */
    public static double flyClimb(float flyingSpeed) {
        return flyingSpeed * FLY_LIFT / (1 - FLY_VERTICAL_DRAG);
    }

    /** 飞着水平挪一格要几刻:疾跑稳态。 */
    public static double flyTicksPerBlock(float flyingSpeed) {
        return 1 / flySpeed(flyingSpeed, true);
    }

    /** 飞着升或降一格要几刻。 */
    public static double flyClimbTicksPerBlock(float flyingSpeed) {
        return 1 / flyClimb(flyingSpeed);
    }

    /** 从地面起飞的按键:按、松、按,连按两下跳(原版 {@code Controls.DOUBLE_TAP} 之内),第三刻切成飞行。 */
    public static final int TAKEOFF_TAPS = 3;

    /**
     * 从静止起一直疾飞,逐刻比稳态速度慢掉的那部分合起来是几刻:起飞之后加速到巡航的损失。按步进器({@link #fly})逐刻推到追上稳态。
     */
    public static double flyLaunchLoss(float flyingSpeed) {
        return LAUNCH_LOSS.computeIfAbsent(flyingSpeed, Kinematics::launchLoss);
    }

    /** 飞行速度各不相同的身体很少,算过的记下:定价每展开一个节点都要问。 */
    private static final java.util.Map<Float, Double> LAUNCH_LOSS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Float, Double> STOP_LOSS = new java.util.concurrent.ConcurrentHashMap<>();

    private static double launchLoss(float flyingSpeed) {
        double cruise = flySpeed(flyingSpeed, true);
        Keys cruising = new Keys(1, 0, false, false, true, 0, 0, false);
        Motion m = new Motion(Vec3.ZERO, Vec3.ZERO);
        double lost = 0;
        for (int i = 0; i < 400; i++) {
            Motion next = fly(m, cruising, flyingSpeed);
            double moved = next.pos().z - m.pos().z;
            if (moved >= cruise * 0.999) {
                break;
            }
            lost += 1 - moved / cruise;
            m = next;
        }
        return lost;
    }

    /** 起飞要几刻:连按两下跳,加上起飞之后加速到巡航的损失({@link #flyLaunchLoss})。 */
    public static double takeoffTicks(float flyingSpeed) {
        return TAKEOFF_TAPS + flyLaunchLoss(flyingSpeed);
    }

    /**
     * 飞着竖直方向这一刻按 {@code lift}(1 是跳、-1 是潜行、0 是松着)之后,脚最终停在比现在高多少:这一刻挪的是竖直速度 {@code vy} 加上按键的 ±3 × 飞行速度,
     * 之后每刻只剩六成。
     */
    public static double flyRise(float flyingSpeed, double vy, int lift) {
        double moving = vy + (lift == 0 ? 0.0 : (double) ((float) lift * flyingSpeed * FLY_LIFT));
        return moving / (1 - FLY_VERTICAL_DRAG);
    }

    /**
     * 飞着以水平 {@code speed}(格每刻)冲着,要停下:每刻按住后退键(平飞的加速度,没有疾跑加成)直到速度被抵到零,再松开。
     * 交出这样停下花的刻数与滑出的距离(格);停车的控制器与定价读的是同一份。
     */
    public static Stop flyStop(float flyingSpeed, double speed) {
        double brake = (double) KEY_INPUT * flyingSpeed;
        double v = speed;
        double distance = 0;
        int ticks = 0;
        while (v > REST) {
            double moving = v - brake;
            ticks++;
            if (moving <= 0) {
                break;
            }
            distance += moving;
            v = moving * FLY_DRAG;
        }
        return new Stop(ticks, distance);
    }

    /** 从疾飞的巡航速度停下,比一路巡航滑完这段距离多花几刻:降落停在落点上要付的。 */
    public static double flyStopLoss(float flyingSpeed) {
        return STOP_LOSS.computeIfAbsent(flyingSpeed, speed -> {
            double cruise = flySpeed(speed, true);
            return flyStop(speed, cruise).lost(cruise);
        });
    }

    /** 停下花的刻数与滑出的距离(格)。 */
    public record Stop(int ticks, double distance) {

        /** 比一路按巡航速度 {@code cruise}(格每刻)走完这段距离多花的刻数。 */
        public double lost(double cruise) {
            return ticks - distance / cruise;
        }
    }

    /**
     * 飞着这一刻按 {@code first},之后松开所有键,身体最终停在哪(位置):逐刻 {@link #fly} 推到速度小于 {@link #REST}。
     */
    public static Vec3 flyRest(Motion state, Keys first, float flyingSpeed) {
        Motion m = fly(state, first, flyingSpeed);
        Keys idle = Keys.idle(first.yaw(), first.pitch());
        for (int i = 0; i < 400 && m.velocity().lengthSqr() > REST * REST; i++) {
            m = fly(m, idle, flyingSpeed);
        }
        return m.pos();
    }

    // ==================== 走出边沿 ====================

    /**
     * 地上沿前进方向走向支撑的边沿,走出去落下 {@code drop} 格,落地那一刻身体中心在哪(沿前进方向,离此刻的位置多远;包括落地那一刻
     * 自己的水平挪动——原版先处理竖直方向落到地上,再接着水平挪,落在边上的会被这一挪带出支撑):地上这一刻挪 {@code move},之后
     * 每刻先乘惯性({@link #groundDrag})再加 {@code groundAccel}(按着前进键是 {@link #groundAcceleration},松着为 0),挪过
     * {@code toEdge} 就离了地;空中每刻加 {@code airAccel}(按着前进键是 {@link #airAcceleration},松着为 0)再乘阻力({@link #AIR_DRAG}),
     * 直到落下 {@code drop} 格({@link #ticksToLand},从站着时的竖直速度起)。步态与走出边沿的控制拿它预判落点:冲出去太快就落过了落点那一列。
     *
     * @param move   这一刻身体会挪的距离:上一刻留下的速度加这一刻按键的加速度
     * @param toEdge 身体中心此刻离"身子整个离开支撑"那一点的距离(整块的边沿是半格加半个身宽)
     * @return 松着键滑不到边沿就停住了为 NaN
     */
    public static double dropLanding(double gravity, double toEdge, double move, double groundAccel, double airAccel, double drop) {
        double drag = groundDrag(DEFAULT_FRICTION);
        double x = 0;
        double m = move;
        while (x + m < toEdge) {
            x += m;
            m = m * drag + groundAccel;
            if (m < REST) {
                return Double.NaN;
            }
        }
        x += m;
        double v = m * drag;
        int air = ticksToLand(drop, -gravity * VERTICAL_DRAG, 0, gravity, 200);
        for (int t = 0; t < air; t++) {
            v += airAccel;
            x += v;
            v *= AIR_DRAG;
        }
        return x;
    }

    // ==================== 几步合起来的几何 ====================

    /** 走到边沿并迈出去开始下落:半格走到边,再让身体离开边沿(半个身宽),按平走的步速。 */
    public static double walkOffEdgeTicks(BodyStats body) {
        return (0.5 + body.width() / 2) * walkTicksPerBlock(body);
    }

    /** 落地后走回落点那一列的中心:一格里除去走出边沿的那一段。 */
    public static double centerAfterFallTicks(BodyStats body) {
        return (0.5 - body.width() / 2) * walkTicksPerBlock(body);
    }

    /**
     * 落到地上的耗时:自由下落 {@code drop} 格,至少要走回落点那一列的中心({@link #centerAfterFallTicks})。下一级、下落、斜着下一级、
     * 向下挖的落地都是它;估价拿它给往下落的步子定下界。
     */
    public static double fallLandingTicks(BodyStats body, double drop) {
        return Math.max(fallTicks(body.gravity(), drop), centerAfterFallTicks(body));
    }

    // ==================== 估价的底价 ====================

    /**
     * 搜索估价的水平底价,这具身体各种走法里真实耗时的下界,见 {@link #pace}。估价是"疾跑底价加额外代价下界":底价按水平距离乘
     * {@code sprint},额外代价(挖、升、降)由搜索按世界另算,价钱都来自这里与成本模型。
     *
     * @param walk   平地走一格(权衡以它为单位,不进估价)
     * @param sprint 水平每格:各种走法里最快的,疾跑一格
     */
    public record Pace(double walk, double sprint) {

        /** 身体能飞({@code flyingSpeed} 是能力里的飞行速度)时,水平每格取疾飞与疾跑里更快的那个;下界仍是下界。 */
        public Pace withFlight(float flyingSpeed) {
            return new Pace(walk, Math.min(sprint, flyTicksPerBlock(flyingSpeed)));
        }
    }

    /**
     * 这具身体的估价底价。水平每格取疾跑一格({@link #sprintTicksPerBlock}):平走、潜行、涉水、疾跑跳、爬梯子的水平耗时都不比它短;
     * 脚下方块的步速系数只会让走得更慢。
     */
    public static Pace pace(BodyStats body) {
        return new Pace(walkTicksPerBlock(body), sprintTicksPerBlock(body));
    }

    /**
     * 跳过去 {@code span} 列:按助跑的步速(疾跑或平走)跨过去;空中的时间不会比起跳到落在 {@code rise}(落点比起跳时高几格,
     * 低处为负)上更短,取两者里长的。
     */
    public static double leapTicks(BodyStats body, int span, boolean sprint, double rise) {
        double pace = sprint ? sprintTicksPerBlock(body) : walkTicksPerBlock(body);
        return Math.max(span * pace, landTicks(body, 1.0, rise));
    }
}
