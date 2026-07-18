package com.dwinovo.numen.core.pathing.astar;

import java.util.List;

import com.dwinovo.numen.core.pathing.moves.CalculationContext;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.core.BlockPos;

/**
 * 目的格哈希 → 成本系数表,默认 1.0。两类来源:
 * <ul>
 *   <li>backtrack:上一条路径上的全部格位打折
 *       (backtrackCostFavoringCoefficient,默认 0.5),重规划时旧路径上的
 *       动作成本减半,倾向沿旧路走,抑制路径抖动;</li>
 *   <li>avoidance:生物/刷怪笼周围的球形成本惩罚(mob 半径 8 系数 1.5、
 *       刷怪笼半径 16 系数 2.0,球形叠乘),受 NavSettings.avoidance 开关
 *       控制(默认关,关闭时表里只有 backtrack 折扣)。</li>
 * </ul>
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

    /**
     * 带规避球的构造:先按 backtrack 折扣建表,再把每个规避球叠乘进去
     * (球内已有值乘以 coefficient)。对应 Baritone Favoring(ctx, previous,
     * context) 的双源构造。
     */
    public Favoring(NavPath previous, CalculationContext context, List<Avoidance> avoidances) {
        this(previous, context.backtrackCostFavoringCoefficient);
        if (avoidances != null) {
            for (Avoidance avoid : avoidances) {
                avoid.applySpherical(favorings);
            }
        }
    }

    /** 无任何偏好的空表(首次规划,没有上一条路径) */
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
