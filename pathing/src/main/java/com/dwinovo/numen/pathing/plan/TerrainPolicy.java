package com.dwinovo.numen.pathing.plan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 端口:一格能不能挖或放。规划只问它、自己不判;路线规格决定哪几种答复能进路线——放行的格在
 * 许挖(许放)时就能改,要问的格只在规格把它们算能走({@code consent})时进路线,拒绝的格永远不进。
 *
 * <p>搜索在工作线程里对很多格问它,所以实现必须按冻结的数据回答,可以从任何线程调用;要看这一格周围时只读传进来的视图
 * (规划时是搜索的快照,执行时是活世界),不碰别的世界。
 */
@FunctionalInterface
public interface TerrainPolicy {

    /** 什么都放行:不设限的宿主用。 */
    TerrainPolicy ALLOW_ALL = (change, pos, state, view) -> Permit.ALLOW;

    /** 要做的改动。 */
    sealed interface Change {

        /** 挖掉这一格。 */
        Change DIG = new Dig();

        /** 挖掉这一格。 */
        record Dig() implements Change {}

        /** 往这一格里放 {@code block}:垫路的料,或接住坠落倒下的水。 */
        record Place(Block block) implements Change {}
    }

    /**
     * @param state 这一格此刻的方块(放置时是要被顶替的那个)
     * @param view  问的时候读的世界:规划时是搜索的快照,执行时是活世界
     */
    Permit judge(Change change, BlockPos pos, BlockState state, BlockGetter view);
}
