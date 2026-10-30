package com.dwinovo.numen.pathing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;

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

    private final Map<BlockPos, BlockState> blocks;
    /** 摆过非空气方块的区段;摆回空气也不划掉,只会让 {@link #airSection} 少答一次是。 */
    private final Set<Long> occupied;
    /** 每个区段里摆过哪几种方块(摆走了也不划掉):{@link #sectionMayHold} 只问它,不扫整张表。 */
    private final Map<Long, Set<BlockState>> kinds;
    private final WorldBorder border = new WorldBorder();
    private int loadedRadius = Integer.MAX_VALUE;
    /** 加载的那块正方形的中心区块。 */
    private int loadedX;
    private int loadedZ;
    private BlockState ground = Blocks.AIR.defaultBlockState();
    private int groundTop = Integer.MIN_VALUE;

    public TestWorld() {
        this.blocks = new HashMap<>();
        this.occupied = new HashSet<>();
        this.kinds = new HashMap<>();
    }

    /** 同一份方块表的另一个视窗:加载的区块换一块。 */
    private TestWorld(TestWorld shared, int centerX, int centerZ, int radius) {
        this.blocks = shared.blocks;
        this.occupied = shared.occupied;
        this.kinds = shared.kinds;
        this.ground = shared.ground;
        this.groundTop = shared.groundTop;
        this.loadedX = centerX;
        this.loadedZ = centerZ;
        this.loadedRadius = radius;
    }

    /**
     * 为从 {@code from} 出发的一次搜索拷的快照那样的视窗:同一份方块,只有以 {@code from} 所在区块为中心、半径 {@code chunks} 个区块的区块算加载了
     * (真实运行里搜索的快照就是这样,{@code WorldSnapshot.around})。
     */
    public TestWorld window(BlockPos from, int chunks) {
        return new TestWorld(this, SectionPos.blockToSectionCoord(from.getX()), SectionPos.blockToSectionCoord(from.getZ()), chunks);
    }

    public TestWorld set(int x, int y, int z, BlockState state) {
        return set(new BlockPos(x, y, z), state);
    }

    public TestWorld set(BlockPos pos, BlockState state) {
        blocks.put(pos.immutable(), state);
        if (!state.isAir()) {
            long section = SectionPos.blockToSection(pos.asLong());
            occupied.add(section);
            kinds.computeIfAbsent(section, k -> new HashSet<>()).add(state);
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
    public void forEachBlock(int fromY, int toY, Predicate<BlockState> wanted, ObjIntConsumer<BlockState> visitor) {
        blocks.forEach((pos, state) -> {
            if (pos.getY() >= fromY && pos.getY() < toY && wanted.test(state)) {
                visitor.accept(state, pos.getY());
            }
        });
        if (wanted.test(ground)) {
            for (int y = Math.max(getMinBuildHeight(), fromY); y <= groundTop && y < toY; y++) {
                visitor.accept(ground, y);
            }
        }
    }

    @Override
    public boolean sectionMayHold(int x, int y, int z, Predicate<BlockState> wanted) {
        int sy = SectionPos.blockToSectionCoord(y);
        Set<BlockState> here = kinds.get(SectionPos.asLong(SectionPos.blockToSectionCoord(x), sy, SectionPos.blockToSectionCoord(z)));
        if (here != null && here.stream().anyMatch(wanted)) {
            return true;
        }
        return sy <= SectionPos.blockToSectionCoord(groundTop) && wanted.test(ground);
    }

    /** 只有设了 {@link #loadedWithin} 才有边界:那一圈区块的外沿一列一列回调;整个世界都在时没有。 */
    @Override
    public void forEachBoundaryColumn(ColumnVisitor visitor) {
        if (loadedRadius == Integer.MAX_VALUE) {
            return;
        }
        int loX = (loadedX - loadedRadius) << 4;
        int hiX = ((loadedX + loadedRadius) << 4) + 15;
        int loZ = (loadedZ - loadedRadius) << 4;
        int hiZ = ((loadedZ + loadedRadius) << 4) + 15;
        for (int k = loZ; k <= hiZ; k++) {
            visitor.accept(loX, k);
            visitor.accept(hiX, k);
        }
        for (int k = loX; k <= hiX; k++) {
            visitor.accept(k, loZ);
            visitor.accept(k, hiZ);
        }
    }

    @Override
    public boolean isLoaded(int x, int z) {
        return Math.abs(SectionPos.blockToSectionCoord(x) - loadedX) <= loadedRadius
                && Math.abs(SectionPos.blockToSectionCoord(z) - loadedZ) <= loadedRadius;
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
