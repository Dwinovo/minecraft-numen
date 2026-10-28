package com.dwinovo.numen.tools.shape;

import com.dwinovo.numen.api.sdk.BlockAt;
import com.dwinovo.numen.api.sdk.LuaCodecs;
import com.dwinovo.numen.build.BuildStates;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.tools.shape.ShapeMasks.Mask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 读世界的形状操作:按 WorldEdit 的判据读已加载的方块,算出"要写什么"交回,自己不写世界(写是 {@code numen.build.place} 的事)。每个操作整块读、
 * 一次交回。交回的是和世界此刻不同的格(和世界一样的写了也没变化);一次交回的格数不超过 {@link LuaCodecs#MAX_CELLS}。
 */
final class TerrainReads {

    private TerrainReads() {}

    private static final Direction[] SIX = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP,
            Direction.DOWN};
    /** 只向水平四个方向与向下。 */
    private static final Direction[] WITHOUT_UP = {Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH,
            Direction.DOWN};

    // ---- 公用 ----

    private static <T> List<T> capped(List<T> list) {
        if (list.size() > LuaCodecs.MAX_CELLS) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "that comes to more than " + LuaCodecs.MAX_CELLS + " cells, the most "
                    + "one Cells holds; ask for a smaller area (or a smaller part of it at a time)", null);
        }
        return list;
    }

    private static BlockAt block(BlockPos pos, BlockState state) {
        return new BlockAt(BuildStates.written(state), pos.immutable());
    }

    private static boolean liquid(BlockState s) {
        return s.getBlock() instanceof LiquidBlock;
    }

    /** 这些格各自水平向外 margin 格盖到的区块都已加载:两块相隔很远的格只要那两处,不要中间那一大片。 */
    static void requireAround(LoadedBlocks w, List<BlockPos> cells, int margin) {
        Set<Long> chunks = new HashSet<>();
        for (BlockPos p : cells) {
            for (int cx = (p.getX() - margin) >> 4; cx <= (p.getX() + margin) >> 4; cx++) {
                for (int cz = (p.getZ() - margin) >> 4; cz <= (p.getZ() + margin) >> 4; cz++) {
                    chunks.add(ChunkPos.asLong(cx, cz));
                }
            }
        }
        w.requireChunks(chunks);
    }

    // ---- copy、replace、trim ----

    /** 每一格此刻的方块;有蒙版时不过蒙版的格交回空气({@code //copy -m})。 */
    static List<BlockAt> read(LoadedBlocks w, List<BlockPos> cells, Mask mask, int reach) {
        requireAround(w, cells, reach);
        List<BlockAt> out = new ArrayList<>(cells.size());
        for (BlockPos p : cells) {
            boolean in = mask == null || mask.test(w, p.getX(), p.getY(), p.getZ());
            out.add(block(p, in ? w.at(p) : Blocks.AIR.defaultBlockState()));
        }
        return capped(out);
    }

    /** 过蒙版的格。 */
    static List<BlockPos> matching(LoadedBlocks w, List<BlockPos> cells, Mask mask, int reach) {
        requireAround(w, cells, reach);
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : cells) {
            if (mask.test(w, p.getX(), p.getY(), p.getZ())) {
                out.add(p.immutable());
            }
        }
        return out;
    }

    // ---- hollow ----

    /** {@code //hollow}:从选区包围盒的边界泛洪找"外面"(挡不住移动的方块连成的),离外面不到 thickness 的留下,里面的交回。 */
    static List<BlockPos> hollow(LoadedBlocks w, List<BlockPos> cells, int thickness) {
        throw new UnsupportedOperationException("净室重写中");
    }

    private static boolean touches(Set<Long> set, BlockPos p) {
        throw new UnsupportedOperationException("净室重写中");
    }

    private static void flood(LoadedBlocks w, BlockPos origin, Set<Long> region, Set<Long> outside) {
        throw new UnsupportedOperationException("净室重写中");
    }

    // ---- 按列:overlay、naturalize、smooth ----

    /** {@code //overlay}:每一列(x、z)从 maxY 往下第一个不是空气的格,它头上那一格;列顶上(maxY + 1)就不是空气的列跳过。 */
    static List<BlockPos> overlay(LoadedBlocks w, List<BlockPos> columns, int minY, int maxY) {
        requireAround(w, columns, 0);
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos c : columns) {
            if (!w.at(c.getX(), maxY + 1, c.getZ()).isAir()) {
                continue;
            }
            for (int y = maxY; y >= minY; y--) {
                if (!w.at(c.getX(), y, c.getZ()).isAir()) {
                    out.add(new BlockPos(c.getX(), y + 1, c.getZ()));
                    break;
                }
            }
        }
        return capped(out);
    }

    private static boolean natural(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.STONE);
    }

    /** {@code //naturalize}:每列的草、泥土、石头,从最上面那块起,深 0 是草,1 到 3 是泥土,再往下是石头。 */
    static List<BlockAt> naturalize(LoadedBlocks w, List<BlockPos> columns, int minY, int maxY) {
        throw new UnsupportedOperationException("净室重写中");
    }

    /** {@code //smooth}:选区包围盒里每列的地表高度(蒙版里最高的一格,缺省挡得住移动的),Gaussian 核(5, 1.0)卷积 iterations 遍,列照新高度升或降。 */
    static List<BlockAt> smooth(LoadedBlocks w, BlockPos lo, BlockPos hi, int iterations, Mask mask, int reach) {
        throw new UnsupportedOperationException("净室重写中");
    }

    /** 这一格要成 state;世界里已经是了就不用写。 */
    private static void put(LoadedBlocks w, List<BlockAt> out, int x, int y, int z, BlockState state) {
        if (w.at(x, y, z) != state) {
            out.add(block(new BlockPos(x, y, z), state));
        }
    }

    /** 一列里最高的"地面":蒙版里最高的一格,没蒙版是挡得住移动的;一格也没有是 minY。 */
    private static int highest(LoadedBlocks w, int x, int z, int minY, int maxY, Mask mask) {
        for (int y = maxY; y >= minY; y--) {
            if (mask == null ? w.at(x, y, z).blocksMotion() : mask.test(w, x, y, z)) {
                return y;
            }
        }
        return minY;
    }

    /** 二维高斯核:每格的权重是 exp(-(dx² + dy²) / 2σ²),再除以全部权重的和,使总和为 1(圆周率那一项在归一时约掉,不用算)。边长 2r + 1。 */
    private static float[] gaussian(int radius, double sigma) {
        int side = 2 * radius + 1;
        double[] weights = new double[side * side];
        double total = 0;
        for (int row = 0; row < side; row++) {
            for (int col = 0; col < side; col++) {
                int dy = row - radius;
                int dx = col - radius;
                weights[row * side + col] = Math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma));
                total += weights[row * side + col];
            }
        }
        float[] kernel = new float[weights.length];
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] = (float) (weights[i] / total);
        }
        return kernel;
    }

    /** 核盖不到的(出了数据边)按这一格自己算;结果加 offset 再取整。 */
    private static int[] convolve(int[] in, int width, int height, float[] kernel, int radius, float offset) {
        throw new UnsupportedOperationException("净室重写中");
    }

    /** 核卷过一遍高度图,不取整(雪层的高度图是小数)。 */
    private static float[] convolve(float[] data, int width, int height, float[] kernel, int radius) {
        throw new UnsupportedOperationException("净室重写中");
    }

    /**
     * {@code //snowsmooth}:和 smooth 同一份高度图与高斯核,只是高度带小数(雪层一层是 1/8 格):每列的高度是地面上雪的高度,
     * 卷积后加半层,按新高度铺雪(下面 layerBlocks 格是雪块,再往下抄原来的)。
     */
    static List<BlockAt> snowSmooth(LoadedBlocks w, BlockPos lo, BlockPos hi, int iterations, Mask mask, int layerBlocks, int reach) {
        throw new UnsupportedOperationException("净室重写中");
    }

    private static void putSnowLayer(LoadedBlocks w, List<BlockAt> out, int x, int z, float height) {
        throw new UnsupportedOperationException("净室重写中");
    }

    /**
     * {@code //morph} 笔刷(erode 与 dilate 是它的两个预设):在 center 为心、半径 size 的球里,先侵蚀 erodeIterations 遍(实心方块露在外面的面
     * 不少于 minErodeFaces 个,就变成露出的那些面里最多的空气或液体),再膨胀 dilateIterations 遍(空气或液体贴着的实心面不少于 minDilateFaces 个,
     * 就变成贴着的面里最多的那种方块)。每一遍都读上一遍的结果。
     */
    static List<BlockAt> morph(LoadedBlocks w, BlockPos center, double size, int minErodeFaces, int erodeIterations,
                               int minDilateFaces, int dilateIterations) {
        throw new UnsupportedOperationException("净室重写中");
    }

    private static boolean emptyOrLiquid(BlockState s) {
        return s.isAir() || liquid(s);
    }

    /** 侵蚀(erode)看露出的面、改实心方块;膨胀看贴着的实心面、改空的格。 */
    private static void morphPass(BlockState[][][] from, BlockState[][][] to, int ceil, double sizeSq, boolean erode, int minFaces) {
        throw new UnsupportedOperationException("净室重写中");
    }

    // ---- 统计:count、distr ----

    /** {@code //count}:这些格里世界方块过蒙版的有几个。 */
    static int count(LoadedBlocks w, List<BlockPos> cells, Mask mask, int reach) {
        requireAround(w, cells, reach);
        int n = 0;
        for (BlockPos p : cells) {
            if (mask.test(w, p.getX(), p.getY(), p.getZ())) {
                n++;
            }
        }
        return n;
    }

    /** 一种方块和它有几个。 */
    record Counted(String name, int count) {}

    /** {@code //distr}:这些格里各种方块的数量(过蒙版的才算),多的在前;separate 时按方块状态分,否则按方块。 */
    static List<Counted> distribution(LoadedBlocks w, List<BlockPos> cells, Mask mask, int reach, boolean separate) {
        throw new UnsupportedOperationException("净室重写中");
    }

    // ---- 泛洪:fill、drain、fixwater ----

    /** 以 center 为心、半径 r(算作 r + 0.5)的椭球的内部:除法判,同 WorldEdit 的选区。 */
    private static boolean inBall(BlockPos center, double radius, int x, int y, int z) {
        double r = radius + 0.5;
        double dx = (x - center.getX()) / r;
        double dy = (y - center.getY()) / r;
        double dz = (z - center.getZ()) / r;
        return dx * dx + dy * dy + dz * dz <= 1;
    }

    /** 从起点按给定的方向广度优先走,能走的由 visitable 定;起点不问,一定在里面。交回走过的格,多到一张 Cells 装不下就失败。 */
    private interface Visitable {

        boolean test(BlockPos from, BlockPos to);
    }

    private static List<BlockPos> walk(List<BlockPos> starts, Direction[] directions, Visitable visitable) {
        throw new UnsupportedOperationException("净室重写中");
    }

    /** {@code //fill} 与 {@code //fillr}:从 at 起,球里、不高于 at、不深于 depth 的空气;fill 只在 at 那一层横着走、再往下,fillr 六个方向都走。 */
    static List<BlockPos> fill(LoadedBlocks w, BlockPos at, double radius, int depth, boolean recursive) {
        throw new UnsupportedOperationException("净室重写中");
    }

    /** {@code //drain}:at 周围 3×3×3 里水与岩浆(waterlogged 时加上含水的方块)连着的一片,球里;液体换空气,含水的方块去掉水。 */
    static List<BlockAt> drain(LoadedBlocks w, BlockPos at, double radius, boolean waterlogged) {
        throw new UnsupportedOperationException("净室重写中");
    }

    private static boolean drainable(BlockState s, boolean waterlogged) {
        return liquid(s) || (waterlogged && s.hasProperty(BlockStateProperties.WATERLOGGED)
                && s.getValue(BlockStateProperties.WATERLOGGED));
    }

    /** {@code //fixwater}、{@code //fixlava}:at 周围 3×3×3 里这种流体连着的一片,球里、不高于 at,只向下与横着走进空气或同种流体,都写成源头。 */
    static List<BlockAt> fixLiquid(LoadedBlocks w, BlockPos at, double radius, BlockState source) {
        throw new UnsupportedOperationException("净室重写中");
    }

    // ---- 圆柱里的:snow、thaw、green ----

    /** 圆柱(中心 at、半径 radius、算作 r + 0.5)的一列:除法判,同 WorldEdit 的圆柱选区。 */
    private static boolean inColumn(BlockPos center, double radius, int x, int z) {
        double r = radius + 0.5;
        double dx = (x - center.getX()) / r;
        double dz = (z - center.getZ()) / r;
        return dx * dx + dz * dz <= 1;
    }

    private static List<BlockPos> columnsOf(BlockPos at, double radius) {
        int reach = (int) Math.ceil(radius + 0.5);
        List<BlockPos> out = new ArrayList<>();
        for (int x = at.getX() - reach; x <= at.getX() + reach; x++) {
            for (int z = at.getZ() - reach; z <= at.getZ() + reach; z++) {
                if (inColumn(at, radius, x, z)) {
                    out.add(new BlockPos(x, at.getY(), z));
                }
            }
        }
        return out;
    }

    /** {@code //snow}:圆柱里每列最上面那块能托住雪的:静水结冰,别的头上落一层雪(stack 时已有的雪再加一层、满了成雪块)。 */
    static List<BlockAt> snow(LoadedBlocks w, BlockPos at, double radius, int height, boolean stack) {
        throw new UnsupportedOperationException("净室重写中");
    }

    private static boolean snowGround(BlockState s, boolean stack) {
        throw new UnsupportedOperationException("净室重写中");
    }

    private static void snowOn(LoadedBlocks w, List<BlockAt> out, BlockPos pos, boolean stack) {
        throw new UnsupportedOperationException("净室重写中");
    }

    /** {@code //thaw}:圆柱里每列最上面不是空气的一格:冰化成水、雪层变空气。 */
    static List<BlockAt> thaw(LoadedBlocks w, BlockPos at, double radius, int height) {
        throw new UnsupportedOperationException("净室重写中");
    }

    /** {@code //green}:圆柱里每列从上往下遇到的第一个泥土(coarse 时含粗泥土)变草方块;先遇到液体或挡得住移动的就停。 */
    static List<BlockAt> green(LoadedBlocks w, BlockPos at, double radius, int height, boolean coarse) {
        throw new UnsupportedOperationException("净室重写中");
    }

    // ---- 笔刷:gravity、heightmap ----

    /** 重力笔刷:radius 见方的每一列里,把悬空的方块落到它下面最低的空气上;落走的地方成空气。 */
    static List<BlockAt> gravity(LoadedBlocks w, BlockPos at, double radius, double height) {
        throw new UnsupportedOperationException("净室重写中");
    }

    /**
     * 高度图笔刷:每列从最高的地面起,按 grid(0 到 1 的格子,重采样到 radius 取整后的 2 倍加 1 见方,取最近的)乘 intensity 的高度往上加(erase:
     * 往下挖;flatten:不越过 at 那一层)。
     */
    static List<BlockAt> heightmap(LoadedBlocks w, BlockPos at, double radius, double[][] grid, double intensity, boolean erase,
                                   boolean flatten, boolean randomize) {
        throw new UnsupportedOperationException("净室重写中");
    }

    /** 格子重采样到 side × side 取最近的那一格;z 方向翻过来(格子的第一行是最靠南的)。 */
    private static double sample(double[][] grid, int x, int z, int side) {
        int rows = grid.length;
        int flipped = side - 1 - z;
        int row = Math.min(rows - 1, (int) Math.floor((flipped + 0.5) * rows / side));
        int cols = grid[row].length;
        int col = Math.min(cols - 1, (int) Math.floor((x + 0.5) * cols / side));
        return grid[row][col];
    }

    // ---- ^:照世界里原来的方块改 ----

    /**
     * {@code ^} 写法的图案:{@code ^[facing=east]} 把每格原来的方块改这些属性(这个方块没有的属性略过),{@code ^oak_planks} 换成这种方块、
     * 原来的属性照带(新方块有的),{@code ^oak_stairs[half=top]} 先换再改。
     */
    static List<BlockAt> restate(LoadedBlocks w, List<BlockPos> cells, String text) {
        requireAround(w, cells, 0);
        String body = text.trim().substring(1);
        int bracket = body.indexOf('[');
        String type = bracket < 0 ? body : body.substring(0, bracket);
        Map<String, String> states = new LinkedHashMap<>();
        if (bracket >= 0) {
            if (!body.endsWith("]")) {
                throw new IllegalArgumentException("'" + text + "' misses its closing ]");
            }
            for (String pair : body.substring(bracket + 1, body.length() - 1).split(",")) {
                if (pair.isBlank()) {
                    continue;
                }
                String[] kv = pair.split("=", 2);
                if (kv.length != 2 || kv[0].isBlank() || kv[1].isBlank()) {
                    throw new IllegalArgumentException("'" + text + "': '" + pair + "' is not property=value");
                }
                states.put(kv[0].trim(), kv[1].trim());
            }
        }
        if (type.isEmpty() && states.isEmpty()) {
            throw new IllegalArgumentException("'" + text + "' says nothing to change: write ^[property=value] or ^block");
        }
        BlockState made = type.isEmpty() ? null : BuildStates.parse(type, false).blockState();
        List<BlockAt> out = new ArrayList<>(cells.size());
        for (BlockPos p : cells) {
            BlockState state = w.at(p);
            if (made != null) {
                state = retyped(state, made);
            }
            state = withStates(state, states);
            out.add(block(p, state));
        }
        return capped(out);
    }

    private static BlockState retyped(BlockState old, BlockState made) {
        for (Property<?> p : old.getProperties()) {
            if (made.hasProperty(p)) {
                made = copy(made, old, p);
            }
        }
        return made;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState to, BlockState from, Property<T> p) {
        T value = from.getValue(p);
        return p.getPossibleValues().contains(value) ? to.setValue(p, value) : to;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState withStates(BlockState state, Map<String, String> states) {
        for (Property<?> p : state.getProperties()) {
            String want = states.get(p.getName());
            if (want != null) {
                Optional<?> value = p.getValue(want);
                if (value.isEmpty()) {
                    throw new IllegalArgumentException(BuildStates.written(state) + " has no value '" + want + "' for "
                            + p.getName());
                }
                state = state.setValue((Property) p, (Comparable) value.get());
            }
        }
        return state;
    }
}
