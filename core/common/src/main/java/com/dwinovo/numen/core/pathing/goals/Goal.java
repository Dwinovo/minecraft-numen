package com.dwinovo.numen.core.pathing.goals;

/**
 * 搜索目标:成员判定 + 启发式下界。heuristic 单位与动作成本一致
 * (tick),按乐观估计给出从 (x,y,z) 到目标的剩余成本。
 */
public interface Goal {

    /** (x,y,z) 是否已在目标内。 */
    boolean isInGoal(int x, int y, int z);

    /** 从 (x,y,z) 到目标的乐观剩余成本(tick);有到达价的目标把到达价算进来。 */
    double heuristic(int x, int y, int z);

    /**
     * 路停在 (x,y,z) 这个目标格之后还要付的价钱(tick),默认 0。复合目标里各成员价钱不同时
     * (挖矿的目标方块有的要主人同意),搜索按"走过去 + 到了再付"的总价挑终点,而不是谁近挑谁。
     * 只对 {@link #isInGoal} 成立的格有意义。
     */
    default double arrivalCost(int x, int y, int z) {
        return 0;
    }
}
