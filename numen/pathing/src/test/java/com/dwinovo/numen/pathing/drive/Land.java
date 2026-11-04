package com.dwinovo.numen.pathing.drive;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.IntBinaryOperator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;

/**
 * 滑翔场景的地形夹具:一块固定的地(每列一个地面高度,最上一层草、往下两层土、再下是石头),上面按固定种子摆树、山脊、池塘、墙。
 * 场景写在 {@code [0, width) × [0, depth)} 里;四周再各铺 {@link #MARGIN} 格同样的地(世界不会在场地边上断掉:绕着落点盘旋的圈、迫降的落点都可能出场地)。
 * 只有这一整块里已加载的区块算加载了(默认全加载;{@link #streaming} 之后只有身体周围的,飞到哪加载到哪,和同伴一样照真玩家加载区块);
 * 之外读出来是虚空空气。高度图({@link #top})按原版 {@code MOTION_BLOCKING}:
 * 第一个挡动作或有流体的方块之上。同样的参数摆出来的地形逐格相同,场景的结论因此是确定的。
 */
final class Land implements Terrain {

    private static final BlockState VOID = Blocks.VOID_AIR.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState LEAF = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);

    /** 场地四周各铺多少格(区块的整数倍)。 */
    static final int MARGIN = 64;

    /** 整块地的宽与深(含四周的 {@link #MARGIN}),整块地的西北角是 {@code (-MARGIN, -MARGIN)}。 */
    private final int width;
    private final int depth;
    /** 每列最上一层地面(草)的高度。 */
    private final int[] surface;
    /** 摆在地面之外(或改掉地面)的方块,按列、按高度。 */
    private final Map<Long, TreeMap<Integer, BlockState>> placed = new HashMap<>();
    private final WorldBorder border = new WorldBorder();
    /** 每个区块加载了没有;默认全加载。 */
    private final boolean[] loaded;

    /** 平地:地面最上一层草在 {@code ground} 高度。 */
    static Land flat(int width, int depth, int ground) {
        return new Land(width, depth, (x, z) -> ground);
    }

    private Land(int fieldWidth, int fieldDepth, IntBinaryOperator height) {
        this.width = fieldWidth + 2 * MARGIN;
        this.depth = fieldDepth + 2 * MARGIN;
        this.surface = new int[width * depth];
        this.loaded = new boolean[width / 16 * (depth / 16)];
        java.util.Arrays.fill(loaded, true);
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                surface[x * depth + z] = height.applyAsInt(x - MARGIN, z - MARGIN);
            }
        }
    }

    // ==================== 区块加载 ====================

    /** 从现在起一个区块也没加载;身体飞到哪,{@link #follow} 把它周围加载到哪。 */
    Land streaming() {
        java.util.Arrays.fill(loaded, false);
        return this;
    }

    /** 以 {@code at} 为中心、{@code chunks} 个区块为半径的区块都加载上(加载了就不卸)。 */
    void follow(net.minecraft.world.phys.Vec3 at, int chunks) {
        int cx = SectionPos.blockToSectionCoord((int) Math.floor(at.x) + MARGIN);
        int cz = SectionPos.blockToSectionCoord((int) Math.floor(at.z) + MARGIN);
        for (int x = Math.max(0, cx - chunks); x <= Math.min(width / 16 - 1, cx + chunks); x++) {
            for (int z = Math.max(0, cz - chunks); z <= Math.min(depth / 16 - 1, cz + chunks); z++) {
                loaded[x * (depth / 16) + z] = true;
            }
        }
    }

    // ==================== 摆东西 ====================

    Land set(int x, int y, int z, BlockState state) {
        placed.computeIfAbsent(column(x, z), k -> new TreeMap<>()).put(y, state);
        return this;
    }

    Land fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    set(x, y, z, state);
                }
            }
        }
        return this;
    }

    /** 地面高度(最上一层草)。 */
    int ground(int x, int z) {
        return surface[(x + MARGIN) * depth + z + MARGIN];
    }

    /** 一棵橡树(原版的样子):树干 {@code trunk} 格,顶上两层五乘五(去掉四角)、两层三乘三的树叶。 */
    Land tree(int x, int z, int trunk) {
        int base = ground(x, z);
        for (int y = 1; y <= trunk; y++) {
            set(x, base + y, z, Blocks.OAK_LOG.defaultBlockState());
        }
        int top = base + trunk;
        for (int dy = -2; dy <= 1; dy++) {
            int radius = dy <= -1 ? 2 : 1;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    boolean corner = Math.abs(dx) == radius && Math.abs(dz) == radius;
                    if (!(corner && (radius == 2 || dy == 1)) && getBlockState(new BlockPos(x + dx, top + dy, z + dz)).isAir()) {
                        set(x + dx, top + dy, z + dz, LEAF);
                    }
                }
            }
        }
        return this;
    }

    /**
     * 树林:{@code x0..x1}、{@code z0..z1} 里每隔 {@code spacing} 格一棵树(位置与高度由 {@code seed} 定),{@code clearing}({@code {x0, x1, z0, z1}})那一块留空。
     */
    Land forest(int x0, int z0, int x1, int z1, int spacing, long seed, int[] clearing) {
        Random random = new Random(seed);
        for (int x = x0; x <= x1; x += spacing) {
            for (int z = z0; z <= z1; z += spacing) {
                int tx = x + random.nextInt(2);
                int tz = z + random.nextInt(2);
                boolean cleared = clearing != null && tx >= clearing[0] - 2 && tx <= clearing[1] + 2 && tz >= clearing[2] - 2 && tz <= clearing[3] + 2;
                int trunk = 5 + random.nextInt(2);
                if (!cleared) {
                    tree(tx, tz, trunk);
                }
            }
        }
        return this;
    }

    // ==================== 读 ====================

    private static long column(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private boolean inside(int x, int z) {
        int i = x + MARGIN;
        int k = z + MARGIN;
        return i >= 0 && k >= 0 && i < width && k < depth && loaded[(i >> 4) * (depth / 16) + (k >> 4)];
    }

    @Override
    public boolean isLoaded(int x, int z) {
        return inside(x, z);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int x = pos.getX();
        int z = pos.getZ();
        if (!inside(x, z)) {
            return VOID;
        }
        TreeMap<Integer, BlockState> above = placed.get(column(x, z));
        BlockState set = above == null ? null : above.get(pos.getY());
        if (set != null) {
            return set;
        }
        int top = ground(x, z);
        int y = pos.getY();
        if (y > top) {
            return AIR;
        }
        return (y == top ? Blocks.GRASS_BLOCK : y >= top - 2 ? Blocks.DIRT : Blocks.STONE).defaultBlockState();
    }

    @Override
    public int top(int x, int z) {
        if (!inside(x, z)) {
            return getMinBuildHeight();
        }
        TreeMap<Integer, BlockState> above = placed.get(column(x, z));
        int from = Math.max(ground(x, z), above == null || above.isEmpty() ? Integer.MIN_VALUE : above.lastKey());
        for (int y = from; y >= getMinBuildHeight(); y--) {
            BlockState state = getBlockState(new BlockPos(x, y, z));
            if (state.blocksMotion() || !state.getFluidState().isEmpty()) {
                return y + 1;
            }
        }
        return getMinBuildHeight();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public boolean airSection(int x, int y, int z) {
        return !inside(x, z) || SectionPos.blockToSectionCoord(y) > SectionPos.blockToSectionCoord(getMinBuildHeight() + getHeight() - 1);
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
    public int getHeight() {
        return 384;
    }

    @Override
    public int getMinBuildHeight() {
        return -64;
    }
}
