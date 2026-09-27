package com.dwinovo.numen.pathing;

import java.util.HashMap;
import java.util.Map;

import com.dwinovo.numen.pathing.plan.WorldView;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;

/** 单测摆场景用的世界:一张坐标到方块状态的表,没摆的格是空气;世界边界是原版的默认值。 */
public final class TestWorld implements WorldView {

    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private final WorldBorder border = new WorldBorder();

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
        return fill(x0, y, z0, x1, y, z1, Blocks.STONE.defaultBlockState());
    }

    /** 把 {@code (x0, y0, z0)} 到 {@code (x1, y1, z1)} 的长方体填成 {@code state}。 */
    public TestWorld fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    set(x, y, z, state);
                }
            }
        }
        return this;
    }

    @Override
    public WorldBorder border() {
        return border;
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
