package com.dwinovo.numen.pathing.world;


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

    /** 平地疾跑稳态的速度(格每刻)。 */
    public static double sprintSpeed(BodyStats body) {
        return steadyStride(body.movementSpeed() * SPRINT_MULTIPLIER, DEFAULT_FRICTION);
    }

    /** 平地走一格要几刻。 */
    public static double walkTicksPerBlock(BodyStats body) {
        return 1 / steadyStride(body.movementSpeed(), DEFAULT_FRICTION);
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
     * 水下移动效率 {@code efficiency}(0 到 1)把两者向陆上的值插过去,1 时与陆上平走一样快。
     */
    public static double wadeTicksPerBlock(BodyStats body, double efficiency) {
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

    // ==================== 几步合起来的几何 ====================

    /** 走到边沿并迈出去开始下落:半格走到边,再让身体离开边沿(半个身宽),按平走的步速。 */
    public static double walkOffEdgeTicks(BodyStats body) {
        return (0.5 + body.width() / 2) * walkTicksPerBlock(body);
    }

    /** 落地后走回落点那一列的中心:一格里除去走出边沿的那一段。 */
    public static double centerAfterFallTicks(BodyStats body) {
        return (0.5 - body.width() / 2) * walkTicksPerBlock(body);
    }

    // ==================== 估价的下界 ====================

    /**
     * 搜索估价用的几个价钱(刻),都是这具身体各种走法里真实耗时的下界,见 {@link #pace}。
     *
     * @param walk  平地走一格(权衡以它为单位,不进估价)
     * @param sprint 水平每格:各种走法里最快的,疾跑一格
     * @param rise  往上升每格节点最少多少刻
     * @param drop  往下落每格节点最少多少刻
     */
    public record Pace(double walk, double sprint, double rise, double drop) {

        /**
         * 水平挪 {@code horizontal} 格(八方向距离)、竖直挪 {@code vertical} 格节点(上为正)至少要多少刻。水平与竖直取大者,不相加:
         * 一步可以同时前进又升降,代价至少是两者中大的那个;一条路线由许多步合成,每步的代价不小于它水平与竖直两项中大的那个,
         * 总和也就不小于水平总量与竖直总量各自的和中大的那个。
         *
         * <p>往上按节点数算(每一步至多升一个节点,见 {@link #pace});往下按米算。节点是脚所在的那一格({@link Footing#cellOf}),
         * 脚高在格子里可以高到差一点满一格,所以落 {@code n} 个节点,脚至少落 {@code n - 1} 米。
         */
        public double estimate(double horizontal, double vertical) {
            double across = sprint * horizontal;
            double along = vertical >= 0 ? rise * vertical : drop * Math.max(0, -vertical - 1);
            return Math.max(across, along);
        }
    }

    /**
     * 这具身体的估价价钱。每一项都取各种走法里最小的那个,所以估价不会高过任何一条路线的真实代价:
     * <ul>
     *   <li>水平每格:疾跑一格({@link #sprintTicksPerBlock})。平走、潜行、涉水、疾跑跳、爬梯子的水平耗时都不比它短;脚下方块的步速系数只会让
     *       走得更慢;</li>
     *   <li>往上每个节点:每种走法定义上一步至多升一个节点(上一级、斜上、跑酷落高一级、垫柱、攀爬、游),其中水平挪动的一步
     *       至少疾跑一格的耗时,垫柱一步至少一个起跳间隔({@link #JUMP_DELAY} 刻),攀爬一步至少 {@link #climbUpTicksPerBlock};取三者最小。
     *       半砖、楼梯台阶的上一步脚只抬半格也升一个节点,耗时仍是一步走路,所以不能按"脚抬了多高"算;</li>
     *   <li>往下每格:自由下落的终速是每刻 {@code 重力 × 0.98 / 0.02},落多高每格都不会比这更快。</li>
     * </ul>
     */
    public static Pace pace(BodyStats body) {
        double sprint = sprintTicksPerBlock(body);
        double rise = Math.min(sprint, Math.min(JUMP_DELAY, climbUpTicksPerBlock(body)));
        double drop = (1 - VERTICAL_DRAG) / (body.gravity() * VERTICAL_DRAG);
        return new Pace(walkTicksPerBlock(body), sprint, rise, drop);
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
