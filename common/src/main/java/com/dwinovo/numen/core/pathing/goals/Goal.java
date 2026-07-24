package com.dwinovo.numen.core.pathing.goals;

/**
 * 搜索目标:成员判定 + 启发式下界。heuristic 单位与动作成本一致
 * (tick),按乐观估计给出从 (x,y,z) 到目标的剩余成本。
 */
public interface Goal {

    /** (x,y,z) 是否已在目标内。 */
    boolean isInGoal(int x, int y, int z);

    /** 从 (x,y,z) 到目标的乐观剩余成本(tick)。 */
    double heuristic(int x, int y, int z);
}
