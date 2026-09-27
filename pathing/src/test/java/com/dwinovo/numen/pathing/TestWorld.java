package com.dwinovo.numen.pathing;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** 单测摆场景用的世界:一张坐标到方块状态的表,没摆的格是空气。 */
public final class TestWorld implements BlockGetter {

    private final Map<BlockPos, BlockState> blocks = new HashMap<>();

    public TestWorld set(int x, int y, int z, BlockState state) {
        blocks.put(new BlockPos(x, y, z), state);
        return this;
    }

    public TestWorld set(BlockPos pos, BlockState state) {
        blocks.put(pos.immutable(), state);
        return this;
    }

    /** 以 {@code (x0, y, z0)} 到 {@code (x1, y, z1)} 铺一层石头地板。 */
    public TestWorld floor(int x0, int z0, int x1, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                set(x, y, z, Blocks.STONE.defaultBlockState());
            }
        }
        return this;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return 384;
    }

    @Override
    public int getMinBuildHeight() {
        return -64;
    }
}
