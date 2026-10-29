package com.dwinovo.numen.pathing.search;

import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.world.Kinematics;

import net.minecraft.core.BlockPos;

/**
 * 一次搜索的估价:节点走到目标至少还要多少刻。全模块只在这里把三样加起来,每一样都是下界,所以估价不高过任何一条路线的真实代价:
 * <ul>
 *   <li>疾跑底价:水平距离乘疾跑一格({@link Goal#estimate});</li>
 *   <li>挖开的额外代价:路上绕不开要挖掉的格至少多少钱({@link Burial});</li>
 *   <li>升降的额外代价:要升降几个节点,每个至少比水平底价多花多少({@link Vertical})。</li>
 * </ul>
 * 后两样在开搜时按世界算一次,之后每个节点只查表加乘法。再加上这一趟的记忆里抬高过的增量({@link TripMemory}):
 * 展开过却没通到目标的节点,估价比原来更远。
 */
final class Estimate {

    private final Goal goal;
    private final Kinematics.Pace pace;
    private final Burial burial;
    private final Vertical vertical;
    private final TripMemory.Frozen memory;
    /** 估价乘多少:沿旧路打折之后({@link Favoring#floor})每一步的价钱可能低到没折扣时的几分之几,估价同乘才仍是下界。 */
    private final double scale;

    private Estimate(Goal goal, Kinematics.Pace pace, Burial burial, Vertical vertical, TripMemory.Frozen memory, double scale) {
        this.goal = goal;
        this.pace = pace;
        this.burial = burial;
        this.vertical = vertical;
        this.memory = memory;
        this.scale = scale;
    }

    /** 这次搜索的估价:在 {@code view} 上按 {@code model} 的身体与规格,从 {@code start} 往 {@code goal};埋深最多定下 {@code cap} 格。 */
    static Estimate of(SearchView view, CostModel model, Goal goal, BlockPos start, int cap, TripMemory.Frozen memory,
                       double scale) {
        return new Estimate(goal, Kinematics.pace(model.body().stats()), Burial.of(view, model, goal, start, cap),
                Vertical.of(view, model, goal), memory, scale);
    }

    /** 脚在节点 {@code (x, y, z)} 的身体走到目标至少还要多少刻。 */
    double at(int x, int y, int z) {
        return base(x, y, z) + memory.raised(BlockPos.asLong(x, y, z));
    }

    /** 同上,不含这一趟记忆里抬高的增量:纯粹是这一格相对目标的位置。 */
    double base(int x, int y, int z) {
        return scale * (goal.estimate(x, y, z, pace) + burial.floor(x, y, z) + vertical.floor(y));
    }

    double at(BlockPos node) {
        return at(node.getX(), node.getY(), node.getZ());
    }
}
