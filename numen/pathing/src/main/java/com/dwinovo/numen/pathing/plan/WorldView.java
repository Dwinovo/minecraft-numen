package com.dwinovo.numen.pathing.plan;

import com.dwinovo.numen.pathing.world.CellFlags;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;

/**
 * 规划读的只读世界:方块、世界边界,以及这个维度倒不倒得出水。搜索时是派发那一刻的快照,执行复核时是活世界,前提函数对
 * 两者一视同仁。
 */
public interface WorldView extends BlockGetter {

    /**
     * Paper 给 {@code BlockGetter} 加了两个抽象方法({@code getBlockStateIfLoaded}、{@code getFluidIfLoaded}:只读已加载的,
     * 没加载是 null),不实现就编不过。原版的 {@code BlockGetter} 没有它们,下面两个默认实现在原版上只是两个无关的方法。
     * 视图读的时候从不加载区块(没加载的读出来是空气或带标记),所以答案与 {@code getBlockState} / {@code getFluidState} 一致,
     * 和原版调 {@code getBlockState} 的行为相同。
     */
    default BlockState getBlockStateIfLoaded(BlockPos pos) {
        return getBlockState(pos);
    }

    default FluidState getFluidIfLoaded(BlockPos pos) {
        return getFluidState(pos);
    }

    WorldBorder border();

    /** 这个维度倒出的水当场蒸发(原版 {@code dimensionType().ultraWarm()},下界):用水桶接不住坠落。 */
    boolean ultraWarm();

    /**
     * {@code (x, y, z)} 所在的区段(区块里 16 格高的那一段)整段都是空气。只在确知时答是,答否不说明什么:往下找落点时
     * 整段跳过,结论与逐格读一样。
     */
    boolean airSection(int x, int y, int z);

    /** {@code (x, y, z)} 这一格的标志字({@link CellFlags}):由这一格的方块状态推出;搜索的快照另有按层现成的。 */
    default int cellFlags(int x, int y, int z) {
        return CellFlags.of(getBlockState(new BlockPos(x, y, z)));
    }

    /**
     * 同 {@link #cellFlags(int, int, int)},读同一列从 {@code y} 起往上 {@code n} 格,标志字写进 {@code into} 从 {@code at} 起的
     * {@code n} 个字节(只存低八位,它们都在八位以内),交回这 {@code n} 个标志字按位或的结果。
     */
    default int cellFlags(int x, int y, int z, int n, byte[] into, int at) {
        int all = 0;
        for (int i = 0; i < n; i++) {
            int flags = cellFlags(x, y + i, z);
            into[at + i] = (byte) flags;
            all |= flags;
        }
        return all;
    }
}
