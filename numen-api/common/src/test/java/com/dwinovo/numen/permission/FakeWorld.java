package com.dwinovo.numen.permission;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.HashMap;
import java.util.Map;

/** Map 后备的方块视图:权限层的测试只需要"这一格是什么"。 */
final class FakeWorld implements BlockGetter {

    final Map<BlockPos, BlockState> blocks = new HashMap<>();

    void set(BlockPos pos, BlockState state) {
        blocks.put(pos.immutable(), state);
    }

    @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public BlockState getBlockState(BlockPos pos) {
        return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public int getHeight() { return 384; }
    @Override public int getMinBuildHeight() { return -64; }

    /** 无头引导;失败返回 false(测试跳过而不失败)。 */
    static boolean boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
