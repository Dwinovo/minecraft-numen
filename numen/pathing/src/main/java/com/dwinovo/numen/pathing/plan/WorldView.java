package com.dwinovo.numen.pathing.plan;

import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.border.WorldBorder;

/**
 * 规划读的只读世界:方块、世界边界,以及这个维度倒不倒得出水。搜索时是派发那一刻的快照,执行复核时是活世界,前提函数对
 * 两者一视同仁。
 */
public interface WorldView extends BlockGetter {

    WorldBorder border();

    /** 这个维度倒出的水当场蒸发(原版 {@code dimensionType().ultraWarm()},下界):用水桶接不住坠落。 */
    boolean ultraWarm();

    /**
     * {@code (x, y, z)} 所在的区段(区块里 16 格高的那一段)整段都是空气。只在确知时答是,答否不说明什么:往下找落点时
     * 整段跳过,结论与逐格读一样。
     */
    boolean airSection(int x, int y, int z);
}
