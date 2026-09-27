package com.dwinovo.numen.pathing.plan;

import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.border.WorldBorder;

/**
 * 规划读的只读世界:方块与世界边界。搜索时是派发那一刻的快照,执行复核时是活世界,前提函数对两者一视同仁。
 */
public interface WorldView extends BlockGetter {

    WorldBorder border();
}
