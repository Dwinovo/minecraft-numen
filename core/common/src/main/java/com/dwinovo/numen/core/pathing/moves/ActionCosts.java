package com.dwinovo.numen.core.pathing.moves;

/**
 * 动作成本常量表。所有成本单位为 tick(20 tick/s),由各动作的真实物理
 * 耗时换算而来;坠落成本表由逐 tick 重力模拟生成。
 */
public final class ActionCosts {

    private ActionCosts() {}

    /** 平走一格(4.317 格/s)。 */
    public static final double WALK_ONE_BLOCK_COST = 20 / 4.317; // 4.633
    /** 水中走一格(2.2 格/s)。 */
    public static final double WALK_ONE_IN_WATER_COST = 20 / 2.2; // 9.091
    /** 灵魂沙上走一格(约半速)。 */
    public static final double WALK_ONE_OVER_SOUL_SAND_COST = WALK_ONE_BLOCK_COST * 2; // 9.266
    /** 上梯子一格(2.35 格/s)。 */
    public static final double LADDER_UP_ONE_COST = 20 / 2.35; // 8.511
    /** 下梯子一格(3.0 格/s)。 */
    public static final double LADDER_DOWN_ONE_COST = 20 / 3.0; // 6.667
    /** 潜行一格(1.3 格/s)。 */
    public static final double SNEAK_ONE_BLOCK_COST = 20 / 1.3; // 15.385
    /** 疾跑一格(5.612 格/s)。 */
    public static final double SPRINT_ONE_BLOCK_COST = 20 / 5.612; // 3.564
    /** 疾跑相对平走的折扣系数。 */
    public static final double SPRINT_MULTIPLIER = SPRINT_ONE_BLOCK_COST / WALK_ONE_BLOCK_COST; // 0.769
    /** 走出方块边缘:0.5 走到边 + 0.3 迈出去开始下落。 */
    public static final double WALK_OFF_BLOCK_COST = WALK_ONE_BLOCK_COST * 0.8; // 3.706
    /** 落地后走回新方块中心的补偿。 */
    public static final double CENTER_AFTER_FALL_COST = WALK_ONE_BLOCK_COST - WALK_OFF_BLOCK_COST; // 0.927

    /**
     * 不可行哨兵。取有限值而非 Double.MAX_VALUE:多个 INF 相加时
     * 不会溢出为负。
     */
    public static final double COST_INF = 1000000;

    /** 坠落 n 格的耗时表,下标 0..4096。 */
    public static final double[] FALL_N_BLOCKS_COST = generateFallNBlocksCost();

    public static final double FALL_1_25_BLOCKS_COST = distanceToTicks(1.25);
    public static final double FALL_0_25_BLOCKS_COST = distanceToTicks(0.25);

    /**
     * 起跳上一格的耗时。按跳可升 1.25 格,抛物线对称:
     * 0→1 段耗时 = 全程(1.25)耗时 − 顶端(0.25)耗时,顶端一段与
     * "坠落 0.25 格"用时相同,故取两者之差。
     */
    public static final double JUMP_ONE_BLOCK_COST = FALL_1_25_BLOCKS_COST - FALL_0_25_BLOCKS_COST; // 3.163

    private static double[] generateFallNBlocksCost() {
        double[] costs = new double[4097];
        for (int i = 0; i < 4097; i++) {
            costs[i] = distanceToTicks(i);
        }
        return costs;
    }

    /** 坠落第 ticks 个 tick 内的位移(格/tick)。 */
    public static double velocity(int ticks) {
        return (Math.pow(0.98, ticks) - 1) * -3.92;
    }

    /** 坠落 distance 格所需 tick 数:逐 tick 累减位移,末段线性插值。 */
    public static double distanceToTicks(double distance) {
        if (distance == 0) {
            return 0; // 避免 0/0 得 NaN
        }
        double tmpDistance = distance;
        int tickCount = 0;
        while (true) {
            double fallDistance = velocity(tickCount);
            if (tmpDistance <= fallDistance) {
                return tickCount + tmpDistance / fallDistance;
            }
            tmpDistance -= fallDistance;
            tickCount++;
        }
    }
}
