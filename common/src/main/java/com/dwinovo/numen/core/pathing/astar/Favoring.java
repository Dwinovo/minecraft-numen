package com.dwinovo.numen.core.pathing.astar;

import com.dwinovo.numen.core.pathing.moves.CalculationContext;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.core.BlockPos;

/**
 * 目的格哈希 → 成本系数表,默认 1.0。上一条路径上的全部格位打折
 * (backtrackCostFavoringCoefficient,默认 0.5):重规划时旧路径上的
 * 动作成本减半,倾向沿旧路走,抑制路径抖动。
 *
 * <p>怪物回避(mob_spawner 半径球 ×2.0、敌对生物半径球 ×1.5 的叠乘)
 * 属于同一张表的另一类来源,本引擎默认关且未启用,不实现;需要时在
 * 此处给表叠乘球形系数即可。
 */
public final class Favoring {

    private final Long2DoubleOpenHashMap favorings;

    /** 折扣系数取自上下文快照(backtrackCostFavoringCoefficient)。 */
    public Favoring(NavPath previous, CalculationContext context) {
        this(previous, context.backtrackCostFavoringCoefficient);
    }

    public Favoring(NavPath previous, double coefficient) {
        favorings = new Long2DoubleOpenHashMap();
        favorings.defaultReturnValue(1.0D);
        if (coefficient != 1D && previous != null) {
            for (BlockPos pos : previous.positions()) {
                favorings.put(PathNode.longHash(pos.getX(), pos.getY(), pos.getZ()), coefficient);
            }
        }
    }

    /** 无任何偏好的空表(首次规划,没有上一条路径)。 */
    public static Favoring empty() {
        return new Favoring(null, 1.0D);
    }

    public boolean isEmpty() {
        return favorings.isEmpty();
    }

    /** 查 (x,y,z) 的 {@link PathNode#longHash} 键;未命中返回 1.0。 */
    public double calculate(long hash) {
        return favorings.get(hash);
    }
}
