package com.dwinovo.numen.core.pathing.moves;

/**
 * 一次移动成本计算的可变结果容器:落点坐标与成本。
 * 搜索循环复用同一实例,避免每条边一次分配。
 */
public final class MutableMoveResult {

    public int x;
    public int y;
    public int z;
    public double cost;

    public MutableMoveResult() {
        reset();
    }

    /** 重置为"不可行":坐标清零,成本置 INF。 */
    public void reset() {
        x = 0;
        y = 0;
        z = 0;
        cost = ActionCosts.COST_INF;
    }
}
