package com.dwinovo.numen.pathing.plan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 端口:一格能不能挖或放。规划只问它、自己不判;路线规格决定哪几种答复能进路线——放行的格在
 * {@code alter=natural} 起就能改,要问的格只在 {@code alter=any} 下进路线,拒绝的格永远不进。
 *
 * <p>搜索在工作线程里对很多格问它,所以实现必须按派发那一刻冻结的数据回答,可以从任何线程调用,不碰活世界。
 */
@FunctionalInterface
public interface TerrainPolicy {

    /** 什么都放行:不设限的宿主用。 */
    TerrainPolicy ALLOW_ALL = (change, pos, state) -> Permit.ALLOW;

    /** 要做的改动。 */
    enum Change {
        /** 挖掉这一格。 */
        DIG,
        /** 往这一格里放一块。 */
        PLACE
    }

    /**
     * @param state 这一格此刻的方块(放置时是要被顶替的那个)
     */
    Permit judge(Change change, BlockPos pos, BlockState state);
}
