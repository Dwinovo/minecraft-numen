package com.dwinovo.numen.pathing.plan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 一步在执行时要对世界做的一件事:挖掉一格、放下一块、开关一扇门、下落时倒一桶水接住自己。规划把它们作为数据交出,
 * 执行的控制器照着做,账单照着记;任何一种走法都可以带上它们。
 */
public sealed interface Edit {

    /** 动的是哪一格。 */
    BlockPos pos();

    /** 算不算改地形:挖与放算,开关门不算(不改路线规格的改动预算,也不问许可)。 */
    default boolean alters() {
        return true;
    }

    /**
     * 挖掉 {@code pos} 的 {@code state}。
     *
     * @param permit     许可的答复:放行或要问
     * @param eyeInWater 挖的时候眼睛泡在水里
     * @param grounded   挖的时候脚踏实地
     */
    record Dig(BlockPos pos, BlockState state, Permit permit, boolean eyeInWater, boolean grounded) implements Edit {}

    /**
     * 往 {@code pos} 放一块 {@code block},顶替掉原来的 {@code replaced}(空气、水、高草……)。
     *
     * @param permit 许可的答复:放行或要问
     */
    record Place(BlockPos pos, BlockState replaced, Block block, Permit permit) implements Edit {}

    /**
     * 下落摔不起时用水接住:落到落点之前,往落点 {@code pos}(原来是 {@code replaced},空气之类)倒一桶水,落进水里;
     * 落定之后再把水收回桶里。一步走完世界照旧,只在这一步当中多了一格水。
     *
     * @param permit 许可对往这一格倒水的答复:放行或要问
     */
    record Catch(BlockPos pos, BlockState replaced, Permit permit) implements Edit {}

    /** 开关 {@code pos} 这扇门(或栅栏门、活板门);门的另一半随原版一起翻转。{@code state} 是开关之前的样子。 */
    record Door(BlockPos pos, BlockState state) implements Edit {

        @Override
        public boolean alters() {
            return false;
        }
    }
}
