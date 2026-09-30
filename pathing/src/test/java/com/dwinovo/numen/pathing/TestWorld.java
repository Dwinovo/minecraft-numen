package com.dwinovo.numen.pathing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.dwinovo.numen.pathing.search.SearchView;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;

/**
 * 单测摆场景用的世界:一张坐标到方块状态的表,没摆的格是空气;铺了无边的地({@link #ground})时,地面以下没摆的格是地。默认所有区块都算加载了;{@link #loadedWithin} 把加载的区块
 * 限在以原点所在区块为中心的一块正方形里。
 */
public final class TestWorld implements SearchView {

    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    /** 摆过非空气方块的区段;摆回空气也不划掉,只会让 {@link #airSection} 少答一次是。 */
    private final Set<Long> occupied = new HashSet<>();
    private final WorldBorder border = new WorldBorder();
    private int loadedRadius = Integer.MAX_VALUE;
    private BlockState ground = Blocks.AIR.defaultBlockState();
    private int groundTop = Integer.MIN_VALUE;

    public TestWorld set(int x, int y, int z, BlockState state) {
        return set(new BlockPos(x, y, z), state);
    }

    public TestWorld set(BlockPos pos, BlockState state) {
        blocks.put(pos.immutable(), state);
        if (!state.isAir()) {
            occupied.add(SectionPos.blockToSection(pos.asLong()));
        }
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

    /** 高度不超过 {@code top} 的格,没摆过的都是 {@code state}:一片往四面无边铺开的地。 */
    public TestWorld ground(int top, BlockState state) {
        this.groundTop = top;
        this.ground = state;
        return this;
    }

    /** 只有离原点所在区块不超过 {@code chunks} 个区块的区块算加载了。 */
    public TestWorld loadedWithin(int chunks) {
        this.loadedRadius = chunks;
        return this;
    }

    @Override
    public boolean isLoaded(int x, int z) {
        return Math.abs(SectionPos.blockToSectionCoord(x)) <= loadedRadius
                && Math.abs(SectionPos.blockToSectionCoord(z)) <= loadedRadius;
    }

    @Override
    public WorldBorder border() {
        return border;
    }

    @Override
    public boolean ultraWarm() {
        return false;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState set = blocks.get(pos);
        if (set != null) {
            return set;
        }
        return pos.getY() <= groundTop ? ground : Blocks.AIR.defaultBlockState();
    }

    @Override
    public boolean airSection(int x, int y, int z) {
        if (SectionPos.blockToSectionCoord(y) <= SectionPos.blockToSectionCoord(groundTop) && !ground.isAir()) {
            return false;
        }
        return !occupied.contains(SectionPos.asLong(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(y),
                SectionPos.blockToSectionCoord(z)));
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
