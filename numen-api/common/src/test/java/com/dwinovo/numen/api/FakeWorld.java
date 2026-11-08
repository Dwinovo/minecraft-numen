package com.dwinovo.numen.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.HashMap;
import java.util.Map;

/** Map 后备的方块视图:几何与权限的测试只需要"这一格是什么";没摆的格是空气。 */
public final class FakeWorld implements BlockGetter {

    public final Map<BlockPos, BlockState> blocks = new HashMap<>();

    public FakeWorld set(BlockPos pos, BlockState state) {
        blocks.put(pos.immutable(), state);
        return this;
    }

    public FakeWorld set(int x, int y, int z, BlockState state) {
        return set(new BlockPos(x, y, z), state);
    }

    /** 以 {@code (x0, y, z0)} 到 {@code (x1, y, z1)} 铺一层石头地板。 */
    public FakeWorld floor(int x0, int z0, int x1, int z1, int y) {
        return fill(x0, y, z0, x1, y, z1, Blocks.STONE.defaultBlockState());
    }

    /** 把 {@code (x0, y0, z0)} 到 {@code (x1, y1, z1)} 的长方体填成 {@code state}。 */
    public FakeWorld fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    set(x, y, z, state);
                }
            }
        }
        return this;
    }

    @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public BlockState getBlockState(BlockPos pos) {
        return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public int getHeight() { return 384; }
    @Override public int getMinBuildHeight() { return -64; }

    /** 无头引导;失败返回 false(测试跳过而不失败)。 */
    public static boolean boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
