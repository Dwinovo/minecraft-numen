package com.dwinovo.numen.pathing.plan;

/**
 * 代价常量表,全模块只此一份。单位一律是刻(20 刻一秒)。
 *
 * <p>前半是物理耗时:由原版玩家的真实速度换算,下落耗时按原版重力与空气阻力逐刻模拟。后半是估价与罚分:搜索的估价权重、
 * 摔伤与要问许可的格子怎么折成刻、走进生物危险半径加多少——这些不是物理量,是"值不值得"的权衡,也只写在这里。
 * 路线规格里的四项动作罚分(放、挖、跳、涉水)是按次可调的,不在这张表里。
 */
public final class ActionCosts {

    private ActionCosts() {}

    // ==================== 物理耗时 ====================

    /** 平地走一格(4.317 格/秒)。 */
    public static final double WALK_ONE_BLOCK = 20 / 4.317;
    /** 疾跑一格(5.612 格/秒)。 */
    public static final double SPRINT_ONE_BLOCK = 20 / 5.612;
    /** 潜行一格(1.3 格/秒)。 */
    public static final double SNEAK_ONE_BLOCK = 20 / 1.3;
    /** 水里走或游一格(2.2 格/秒)。 */
    public static final double WALK_ONE_IN_WATER = 20 / 2.2;
    /** 顺着梯子、藤蔓往上一格(2.35 格/秒)。 */
    public static final double CLIMB_UP_ONE = 20 / 2.35;
    /** 顺着梯子、藤蔓往下一格(3 格/秒,原版攀爬时下滑速度上限 0.15 格/刻)。 */
    public static final double CLIMB_DOWN_ONE = 20 / 3.0;
    /** 走到边沿并迈出去开始下落:半格走到边,再 0.3 格让身体离开边沿。 */
    public static final double WALK_OFF_EDGE = WALK_ONE_BLOCK * 0.8;
    /** 落进倒下的水里之后,低头把水收回桶里:转头与原版两次右键之间的间隔。 */
    public static final double SCOOP_WATER = 10;
    /** 落地后走回落点那一列的中心。 */
    public static final double CENTER_AFTER_FALL = WALK_ONE_BLOCK - WALK_OFF_EDGE;

    /** 原版每刻的重力加速度与竖直方向的空气阻力({@code LivingEntity.travel})。 */
    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;

    /**
     * 自由下落 {@code distance} 格要几刻:从静止起照原版逐刻先按当前速度移动、再加重力乘阻力,最后一刻按比例插值。
     */
    public static double fall(double distance) {
        if (distance <= 0) {
            return 0;
        }
        double left = distance;
        double velocity = 0;
        int ticks = 0;
        while (velocity <= 0 || left > velocity) {
            left -= velocity;
            ticks++;
            velocity = (velocity + GRAVITY) * DRAG;
        }
        return ticks + left / velocity;
    }

    /** 起跳上一格:起跳到最高点约 1.25 格,抛物线对称,升到 1 格的耗时就是全程减去顶端那 0.25 格。 */
    public static final double JUMP_ONE_BLOCK = fall(1.25) - fall(0.25);

    // ==================== 估价 ====================

    /** 估价里水平每格的价钱:取最快的走法(疾跑),估价因此不高过真实代价太多。 */
    public static final double ESTIMATE_PER_BLOCK = SPRINT_ONE_BLOCK;
    /** 估价里往上每格的价钱。 */
    public static final double ESTIMATE_UP = JUMP_ONE_BLOCK;
    /** 估价里往下每格的价钱:下两格耗时的一半。必须大于零,否则目标正上方的格子估价全是零,半程路线会塌回起点。 */
    public static final double ESTIMATE_DOWN = fall(2) / 2;

    // ==================== 罚分 ====================

    /** 摔掉一点血折多少刻:摔得起的高度也是路,只是疼;有不疼的走法时它自然让位。 */
    public static final double FALL_DAMAGE_PER_POINT = 20.0;
    /**
     * 身体走进的格紧挨着一格伤身的方块(岩浆、火、仙人掌),加这么多刻:没碰上,可身子歪一点、被推一下就碰上了。
     * 有稍远一点的路时走远一点,没有也照样走。
     */
    public static final double EXPOSED_SIDE = 2 * WALK_ONE_BLOCK;
    /** 身体进入一只生物危险半径里的一格,加这么多刻:穿过去约等于多绕十来格,够让路线绕开,又不至于宁可挖穿一座山。 */
    public static final double DANGER_PER_CELL = 3 * WALK_ONE_BLOCK;
    /**
     * 挖一格时站位与它之间每隔着一格硬遮挡,停在那儿加这么多刻:先得挖开它才看得见。约等于拿镐挖开一格石头再缓手的工夫;
     * 同样够得着的几个站位里挑挡得少的,多走三四格去一处挡得少的也值。
     */
    public static final double SIGHT_BLOCKER = 4 * WALK_ONE_BLOCK;
}
