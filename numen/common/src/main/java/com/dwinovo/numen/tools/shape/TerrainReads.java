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
        requireAround(w, cells, 1);
        Set<Long> region = new HashSet<>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos p : cells) {
            region.add(p.asLong());
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
        }
        // 包围盒六个面上的每一格都是泛洪的起点;不在选区里、又不贴着选区的面上的格起了点也只记下自己,对结果没有影响,所以起点只取
        // 选区的格和它们的邻格里落在面上的,代价按格数、不按包围盒的面积(两块相隔很远的格,包围盒的面是天文数字)
        Set<Long> outside = new HashSet<>();
        Set<Long> started = new HashSet<>();
        for (BlockPos p : cells) {
            for (int i = -1; i < SIX.length; i++) {
                BlockPos q = i < 0 ? p : p.relative(SIX[i]);
                boolean onFace = q.getX() == minX || q.getX() == maxX || q.getY() == minY || q.getY() == maxY
                        || q.getZ() == minZ || q.getZ() == maxZ;
                boolean inBox = q.getX() >= minX && q.getX() <= maxX && q.getY() >= minY && q.getY() <= maxY
                        && q.getZ() >= minZ && q.getZ() <= maxZ;
                if (onFace && inBox && started.add(q.asLong())) {
                    flood(w, q, region, outside);
                }
            }
        }
        for (int i = 1; i < thickness; i++) {
            Set<Long> next = new HashSet<>();
            for (BlockPos p : cells) {
                if (touches(outside, p)) {
                    next.add(p.asLong());
                }
            }
            outside.addAll(next);
        }
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : cells) {
            if (!touches(outside, p)) {
                out.add(p.immutable());
            }
        }
        return out;
    }

    private static boolean touches(Set<Long> set, BlockPos p) {
        for (Direction d : SIX) {
            if (set.contains(p.relative(d).asLong())) {
                return true;
            }
        }
        return false;
    }

    private static void flood(LoadedBlocks w, BlockPos origin, Set<Long> region, Set<Long> outside) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            if (w.at(current).blocksMotion() || !outside.add(current.asLong())) {
                continue;
            }
            if (!region.contains(current.asLong())) {
                continue;
            }
            for (Direction d : SIX) {
                queue.add(current.relative(d));
            }
        }
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
        requireAround(w, columns, 0);
        List<BlockAt> out = new ArrayList<>();
        for (BlockPos c : columns) {
            int x = c.getX();
            int z = c.getZ();
            if (natural(w.at(x, maxY + 1, z))) {
                continue;
            }
            int groundY = Integer.MIN_VALUE;
            for (int y = maxY; y >= minY; y--) {
                BlockState here = w.at(x, y, z);
                if (groundY == Integer.MIN_VALUE && natural(here)) {
                    groundY = y;
                }
                if (groundY != Integer.MIN_VALUE && natural(here)) {
                    int depth = groundY - y;
                    BlockState target = depth == 0 ? Blocks.GRASS_BLOCK.defaultBlockState()
                            : depth <= 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState();
                    if (here != target) {
                        out.add(block(new BlockPos(x, y, z), target));
                    }
                }
            }
        }
        return capped(out);
    }

    /** {@code //smooth}:选区包围盒里每列的地表高度(蒙版里最高的一格,缺省挡得住移动的),Gaussian 核(5, 1.0)卷积 iterations 遍,列照新高度升或降。 */
    static List<BlockAt> smooth(LoadedBlocks w, BlockPos lo, BlockPos hi, int iterations, Mask mask, int reach) {
        w.require(lo.getX() - reach, lo.getZ() - reach, hi.getX() + reach, hi.getZ() + reach);
        int width = hi.getX() - lo.getX() + 1;
        int length = hi.getZ() - lo.getZ() + 1;
        int originY = lo.getY();
        int maxY = hi.getY();
        int[] heights = new int[width * length];
        for (int z = 0; z < length; z++) {
            for (int x = 0; x < width; x++) {
                heights[z * width + x] = highest(w, lo.getX() + x, lo.getZ() + z, originY, maxY, mask);
            }
        }
        int[] filtered = heights.clone();
        float[] kernel = gaussian(5, 1.0);
        for (int i = 0; i < iterations; i++) {
            filtered = convolve(filtered, width, length, kernel, 5, 0.5F);
        }
        List<BlockAt> out = new ArrayList<>();
        for (int z = 0; z < length; z++) {
            for (int x = 0; x < width; x++) {
                int index = z * width + x;
                int current = heights[index];
                int target = Math.min(maxY, filtered[index]);
                int xr = lo.getX() + x;
                int zr = lo.getZ() + z;
                double scale = (double) (current - originY) / (double) (target - originY);
                if (target > current) {
                    BlockState top = w.at(xr, current, zr);
                    if (liquid(top)) {
                        continue;
                    }
                    put(w, out, xr, target, zr, top);
                    for (int y = target - 1 - originY; y >= 0; y--) {
                        put(w, out, xr, originY + y, zr, w.at(xr, originY + (int) Math.floor(y * scale), zr));
                    }
                } else if (current > target) {
                    for (int y = 0; y < target - originY; y++) {
                        put(w, out, xr, originY + y, zr, w.at(xr, originY + (int) Math.floor(y * scale), zr));
                    }
                    put(w, out, xr, target, zr, w.at(xr, current, zr));
                    for (int y = target + 1; y <= current; y++) {
                        put(w, out, xr, y, zr, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        return capped(out);
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

    private static float[] gaussian(int radius, double sigma) {
        int diameter = radius * 2 + 1;
        float[] data = new float[diameter * diameter];
        double sigma22 = 2 * sigma * sigma;
        double constant = Math.PI * sigma22;
        float sum = 0;
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                float value = (float) (Math.exp(-(x * x + y * y) / sigma22) / constant);
                data[(y + radius) * diameter + x + radius] = value;
                sum += value;
            }
        }
        for (int i = 0; i < data.length; i++) {
            data[i] = data[i] / sum;
        }
        return data;
    }

    /** 核盖不到的(出了数据边)按这一格自己算;结果加 offset 再取整。 */
    private static int[] convolve(int[] in, int width, int height, float[] kernel, int radius, float offset) {
        float[] data = new float[in.length];
        for (int i = 0; i < in.length; i++) {
            data[i] = in[i];
        }
        float[] sums = convolve(data, width, height, kernel, radius);
        int[] out = new int[in.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (int) Math.floor(sums[i] + offset);
        }
        return out;
    }

    /** 核卷过一遍高度图,不取整(雪层的高度图是小数)。 */
    private static float[] convolve(float[] data, int width, int height, float[] kernel, int radius) {
        int diameter = radius * 2 + 1;
        float[] out = new float[data.length];
        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float sum = 0;
                for (int ky = 0; ky < diameter; ky++) {
                    int oy = y + ky - radius;
                    if (oy < 0 || oy >= height) {
                        oy = y;
                    }
                    for (int kx = 0; kx < diameter; kx++) {
                        float f = kernel[ky * diameter + kx];
                        if (f == 0) {
                            continue;
                        }
                        int ox = x + kx - radius;
                        if (ox < 0 || ox >= width) {
                            ox = x;
                        }
                        sum += f * data[oy * width + ox];
                    }
                }
                out[index++] = sum;
            }
        }
        return out;
    }

    /**
     * {@code //snowsmooth}:和 smooth 同一份高度图与高斯核,只是高度带小数(雪层一层是 1/8 格):每列的高度是地面上雪的高度,
     * 卷积后加半层,按新高度铺雪(下面 layerBlocks 格是雪块,再往下抄原来的)。
     */
    static List<BlockAt> snowSmooth(LoadedBlocks w, BlockPos lo, BlockPos hi, int iterations, Mask mask, int layerBlocks, int reach) {
        w.require(lo.getX() - reach, lo.getZ() - reach, hi.getX() + reach, hi.getZ() + reach);
        int width = hi.getX() - lo.getX() + 1;
        int length = hi.getZ() - lo.getZ() + 1;
        int originY = lo.getY();
        int maxY = hi.getY();
        float[] data = new float[width * length];
        for (int z = 0; z < length; z++) {
            for (int x = 0; x < width; x++) {
                int xr = lo.getX() + x;
                int zr = lo.getZ() + z;
                int top = highest(w, xr, zr, originY, maxY, mask);
                BlockState upper = w.at(xr, top + 1, zr);
                data[z * width + x] = upper.is(Blocks.SNOW)
                        ? top + 1 + ((float) upper.getValue(SnowLayerBlock.LAYERS) - 1) / 8
                        : w.at(xr, top, zr).isAir() ? top : top + 1;
            }
        }
        float[] filtered = data.clone();
        float[] kernel = gaussian(5, 1.0);
        for (int i = 0; i < iterations; i++) {
            filtered = convolve(filtered, width, length, kernel, 5);
        }
        for (int i = 0; i < filtered.length; i++) {
            filtered[i] += 0.0625F;
        }
        List<BlockAt> out = new ArrayList<>();
        BlockState snowBlock = Blocks.SNOW_BLOCK.defaultBlockState();
        for (int z = 0; z < length; z++) {
            for (int x = 0; x < width; x++) {
                int index = z * width + x;
                float current = data[index];
                if (current == originY) {
                    continue;
                }
                float target = Math.min(maxY, filtered[index]);
                int xr = lo.getX() + x;
                int zr = lo.getZ() + z;
                double scale = (double) (current - originY) / (double) (target - originY);
                if (target >= current) {
                    if (liquid(w.at(xr, (int) Math.floor(current), zr))) {
                        continue;
                    }
                    putSnowLayer(w, out, xr, zr, target);
                    for (int y = (int) Math.floor(target - 1 - originY); y >= 0; y--) {
                        put(w, out, xr, originY + y, zr, y >= Math.floor(target - 1 - originY - layerBlocks) ? snowBlock
                                : w.at(xr, originY + (int) Math.floor(y * scale), zr));
                    }
                } else {
                    for (int y = 0; y < (int) Math.floor(target - originY); y++) {
                        put(w, out, xr, originY + y, zr, y >= (int) Math.floor(target - originY - layerBlocks) ? snowBlock
                                : w.at(xr, originY + (int) Math.floor(y * scale), zr));
                    }
                    putSnowLayer(w, out, xr, zr, target);
                    for (int y = (int) Math.floor(target + 1); y <= Math.floor(current); y++) {
                        put(w, out, xr, y, zr, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        return capped(out);
    }

    private static void putSnowLayer(LoadedBlocks w, List<BlockAt> out, int x, int z, float height) {
        int y = (int) Math.floor(height);
        int layers = (int) ((height - y) * 8) + 1;
        put(w, out, x, y, z, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers));
    }

    /**
     * {@code //morph} 笔刷(erode 与 dilate 是它的两个预设):在 center 为心、半径 size 的球里,先侵蚀 erodeIterations 遍(实心方块露在外面的面
     * 不少于 minErodeFaces 个,就变成露出的那些面里最多的空气或液体),再膨胀 dilateIterations 遍(空气或液体贴着的实心面不少于 minDilateFaces 个,
     * 就变成贴着的面里最多的那种方块)。每一遍都读上一遍的结果。
     */
    static List<BlockAt> morph(LoadedBlocks w, BlockPos center, double size, int minErodeFaces, int erodeIterations,
                               int minDilateFaces, int dilateIterations) {
        int ceil = (int) Math.ceil(size);
        int buffer = ceil * 2 + 3;
        w.require(center.getX() - ceil - 1, center.getZ() - ceil - 1, center.getX() + ceil + 1, center.getZ() + ceil + 1);
        BlockState[][][] current = new BlockState[buffer][buffer][buffer];
        BlockState[][][] next = new BlockState[buffer][buffer][buffer];
        for (int x = 0; x < buffer; x++) {
            for (int y = 0; y < buffer; y++) {
                for (int z = 0; z < buffer; z++) {
                    BlockState state = w.at(center.getX() + x - ceil - 1, center.getY() + y - ceil - 1, center.getZ() + z - ceil - 1);
                    current[x][y][z] = state;
                    next[x][y][z] = state;
                }
            }
        }
        double sizeSq = size * size;
        for (int i = 0; i < erodeIterations; i++) {
            morphPass(current, next, ceil, sizeSq, true, minErodeFaces);
            BlockState[][][] swap = current;
            current = next;
            next = swap;
        }
        for (int i = 0; i < dilateIterations; i++) {
            morphPass(current, next, ceil, sizeSq, false, minDilateFaces);
            BlockState[][][] swap = current;
            current = next;
            next = swap;
        }
        List<BlockAt> out = new ArrayList<>();
        for (int x = 0; x < buffer; x++) {
            for (int y = 0; y < buffer; y++) {
                for (int z = 0; z < buffer; z++) {
                    put(w, out, center.getX() + x - ceil - 1, center.getY() + y - ceil - 1, center.getZ() + z - ceil - 1, current[x][y][z]);
                }
            }
        }
        return capped(out);
    }

    private static boolean emptyOrLiquid(BlockState s) {
        return s.isAir() || liquid(s);
    }

    /** 侵蚀(erode)看露出的面、改实心方块;膨胀看贴着的实心面、改空的格。 */
    private static void morphPass(BlockState[][][] from, BlockState[][][] to, int ceil, double sizeSq, boolean erode, int minFaces) {
        int span = ceil * 2;
        for (int x = 0; x <= span; x++) {
            for (int y = 0; y <= span; y++) {
                for (int z = 0; z <= span; z++) {
                    int rx = x - ceil;
                    int ry = y - ceil;
                    int rz = z - ceil;
                    if (rx * rx + ry * ry + rz * rz > sizeSq) {
                        continue;
                    }
                    BlockState state = from[x + 1][y + 1][z + 1];
                    to[x + 1][y + 1][z + 1] = state;
                    if (erode == emptyOrLiquid(state)) {
                        continue;
                    }
                    Map<BlockState, Integer> frequency = new LinkedHashMap<>();
                    int faces = 0;
                    int best = 0;
                    BlockState winner = state;
                    for (Direction d : SIX) {
                        BlockState adjacent = from[x + 1 + d.getStepX()][y + 1 + d.getStepY()][z + 1 + d.getStepZ()];
                        if (erode != emptyOrLiquid(adjacent)) {
                            continue;
                        }
                        faces++;
                        int n = frequency.merge(adjacent, 1, Integer::sum);
                        if (n > best) {
                            best = n;
                            winner = adjacent;
                        }
                    }
                    if (faces >= minFaces) {
                        to[x + 1][y + 1][z + 1] = winner;
                    }
                }
            }
        }
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
        requireAround(w, cells, reach);
        Map<BlockState, int[]> counts = new LinkedHashMap<>();
        for (BlockPos p : cells) {
            if (mask != null && !mask.test(w, p.getX(), p.getY(), p.getZ())) {
                continue;
            }
            BlockState state = w.at(p);
            counts.computeIfAbsent(separate ? state : state.getBlock().defaultBlockState(), k -> new int[1])[0]++;
        }
        List<Map.Entry<BlockState, int[]>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(java.util.Comparator.comparingInt(e -> e.getValue()[0]));
        java.util.Collections.reverse(sorted);
        List<Counted> out = new ArrayList<>();
        for (Map.Entry<BlockState, int[]> e : sorted) {
            out.add(new Counted(BuildStates.written(e.getKey()), e.getValue()[0]));
        }
        return out;
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
        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos s : starts) {
            if (seen.add(s.asLong())) {
                queue.add(s);
            }
        }
        List<BlockPos> out = new ArrayList<>();
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            out.add(current);
            capped(out);
            for (Direction d : directions) {
                BlockPos to = current.relative(d);
                if (seen.add(to.asLong()) && visitable.test(current, to)) {
                    queue.add(to);
                }
            }
        }
        return out;
    }

    /** {@code //fill} 与 {@code //fillr}:从 at 起,球里、不高于 at、不深于 depth 的空气;fill 只在 at 那一层横着走、再往下,fillr 六个方向都走。 */
    static List<BlockPos> fill(LoadedBlocks w, BlockPos at, double radius, int depth, boolean recursive) {
        int lower = Math.max(at.getY() - depth + 1 > at.getY() ? Integer.MIN_VALUE : at.getY() - depth + 1, w.minY());
        int upper = Math.min(w.maxY(), at.getY());
        Visitable inside = (from, to) -> inBall(at, radius, to.getX(), to.getY(), to.getZ()) && to.getY() >= lower
                && to.getY() <= upper && w.at(to).isAir();
        Visitable rule = recursive ? inside
                : (from, to) -> (from.getY() == at.getY() || to.getY() < from.getY()) && inside.test(from, to);
        return walk(List.of(at.immutable()), recursive ? SIX : WITHOUT_UP, rule);
    }

    /** {@code //drain}:at 周围 3×3×3 里水与岩浆(waterlogged 时加上含水的方块)连着的一片,球里;液体换空气,含水的方块去掉水。 */
    static List<BlockAt> drain(LoadedBlocks w, BlockPos at, double radius, boolean waterlogged) {
        Visitable mask = (from, to) -> to.getY() >= w.minY() && to.getY() <= w.maxY()
                && inBall(at, radius, to.getX(), to.getY(), to.getZ()) && drainable(w.at(to), waterlogged);
        List<BlockPos> starts = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos p = at.offset(x, y, z);
                    if (mask.test(at, p)) {
                        starts.add(p);
                    }
                }
            }
        }
        List<BlockAt> out = new ArrayList<>();
        for (BlockPos p : walk(starts, SIX, mask)) {
            BlockState s = w.at(p);
            BlockState dry = waterlogged && s.hasProperty(BlockStateProperties.WATERLOGGED)
                    ? s.setValue(BlockStateProperties.WATERLOGGED, false) : Blocks.AIR.defaultBlockState();
            if (dry != s) {
                out.add(block(p, dry));
            }
        }
        return out;
    }

    private static boolean drainable(BlockState s, boolean waterlogged) {
        return liquid(s) || (waterlogged && s.hasProperty(BlockStateProperties.WATERLOGGED)
                && s.getValue(BlockStateProperties.WATERLOGGED));
    }

    /** {@code //fixwater}、{@code //fixlava}:at 周围 3×3×3 里这种流体连着的一片,球里、不高于 at,只向下与横着走进空气或同种流体,都写成源头。 */
    static List<BlockAt> fixLiquid(LoadedBlocks w, BlockPos at, double radius, BlockState source) {
        Visitable mask = (from, to) -> to.getY() >= w.minY() && to.getY() <= Math.min(at.getY(), w.maxY())
                && inBall(at, radius, to.getX(), to.getY(), to.getZ())
                && (w.at(to).is(source.getBlock()) || w.at(to).isAir());
        List<BlockPos> starts = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos p = at.offset(x, y, z);
                    if (w.at(p).is(source.getBlock())) {
                        starts.add(p);
                    }
                }
            }
        }
        List<BlockAt> out = new ArrayList<>();
        for (BlockPos p : walk(starts, WITHOUT_UP, mask)) {
            if (w.at(p) != source) {
                out.add(block(p, source));
            }
        }
        return out;
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
        int minY = at.getY() - height;
        int maxY = at.getY() + height;
        List<BlockPos> columns = columnsOf(at, radius);
        requireAround(w, columns, 0);
        List<BlockAt> out = new ArrayList<>();
        for (BlockPos c : columns) {
            int x = c.getX();
            int z = c.getZ();
            if (snowGround(w.at(x, maxY + 1, z), stack)) {
                continue;
            }
            for (int y = maxY; y >= minY; y--) {
                if (!snowGround(w.at(x, y, z), stack)) {
                    continue;
                }
                snowOn(w, out, new BlockPos(x, y, z), stack);
                break;
            }
        }
        return capped(out);
    }

    private static boolean snowGround(BlockState s, boolean stack) {
        if (s.isAir() || (stack && s.is(Blocks.SNOW))) {
            return false;
        }
        return s.is(Blocks.WATER) || s.blocksMotion();
    }

    private static void snowOn(LoadedBlocks w, List<BlockAt> out, BlockPos pos, boolean stack) {
        BlockState ground = w.at(pos);
        if (ground.is(Blocks.WATER)) {
            if (ground.getValue(LiquidBlock.LEVEL) == 0) {
                out.add(block(pos, Blocks.ICE.defaultBlockState()));
            }
            return;
        }
        if (pos.getY() == w.maxY()) {
            return;
        }
        BlockPos above = pos.above();
        BlockState up = w.at(above);
        if (!up.isAir() && !(stack && up.is(Blocks.SNOW))) {
            return;
        }
        if (stack && up.is(Blocks.SNOW)) {
            int layers = up.getValue(SnowLayerBlock.LAYERS);
            out.add(block(above, layers + 2 > SnowLayerBlock.MAX_HEIGHT ? Blocks.SNOW_BLOCK.defaultBlockState()
                    : up.setValue(SnowLayerBlock.LAYERS, layers + 1)));
            return;
        }
        BlockState snow = Blocks.SNOW.defaultBlockState();
        if (snow.canSurvive(w.level(), above)) {
            out.add(block(above, snow));
        }
    }

    /** {@code //thaw}:圆柱里每列最上面不是空气的一格:冰化成水、雪层变空气。 */
    static List<BlockAt> thaw(LoadedBlocks w, BlockPos at, double radius, int height) {
        int centerY = Math.max(w.minY(), Math.min(w.maxY(), at.getY()));
        int minY = Math.max(w.minY(), centerY - height);
        int maxY = Math.min(w.maxY(), centerY + height);
        List<BlockPos> columns = columnsOf(at, radius);
        requireAround(w, columns, 0);
        List<BlockAt> out = new ArrayList<>();
        for (BlockPos c : columns) {
            for (int y = maxY; y > minY; y--) {
                BlockState s = w.at(c.getX(), y, c.getZ());
                BlockPos pos = new BlockPos(c.getX(), y, c.getZ());
                if (s.is(Blocks.ICE)) {
                    out.add(block(pos, Blocks.WATER.defaultBlockState()));
                } else if (s.is(Blocks.SNOW)) {
                    out.add(block(pos, Blocks.AIR.defaultBlockState()));
                } else if (s.isAir()) {
                    continue;
                }
                break;
            }
        }
        return capped(out);
    }

    /** {@code //green}:圆柱里每列从上往下遇到的第一个泥土(coarse 时含粗泥土)变草方块;先遇到液体或挡得住移动的就停。 */
    static List<BlockAt> green(LoadedBlocks w, BlockPos at, double radius, int height, boolean coarse) {
        int centerY = Math.max(w.minY(), Math.min(w.maxY(), at.getY()));
        int minY = Math.max(w.minY(), centerY - height);
        int maxY = Math.min(w.maxY(), centerY + height);
        List<BlockPos> columns = columnsOf(at, radius);
        requireAround(w, columns, 0);
        List<BlockAt> out = new ArrayList<>();
        for (BlockPos c : columns) {
            for (int y = maxY; y > minY; y--) {
                BlockState s = w.at(c.getX(), y, c.getZ());
                if (s.is(Blocks.DIRT) || (coarse && s.is(Blocks.COARSE_DIRT))) {
                    out.add(block(new BlockPos(c.getX(), y, c.getZ()), Blocks.GRASS_BLOCK.defaultBlockState()));
                    break;
                } else if (s.is(Blocks.WATER) || s.is(Blocks.LAVA) || s.blocksMotion()) {
                    break;
                }
            }
        }
        return capped(out);
    }

    // ---- 笔刷:gravity、heightmap ----

    /** 重力笔刷:radius 见方的每一列里,把悬空的方块落到它下面最低的空气上;落走的地方成空气。 */
    static List<BlockAt> gravity(LoadedBlocks w, BlockPos at, double radius, double height) {
        double yMax = Math.min(at.getY() + height, w.maxY());
        double yMin = Math.max(at.getY() - height, w.minY());
        w.require((int) Math.floor(at.getX() - radius), (int) Math.floor(at.getZ() - radius),
                (int) Math.floor(at.getX() + radius), (int) Math.floor(at.getZ() + radius));
        Map<BlockPos, BlockState> moved = new LinkedHashMap<>();
        for (double x = at.getX() - radius; x <= at.getX() + radius; x++) {
            for (double z = at.getZ() - radius; z <= at.getZ() + radius; z++) {
                int xi = (int) Math.floor(x);
                int zi = (int) Math.floor(z);
                Map<BlockPos, BlockState> column = new LinkedHashMap<>();
                Set<BlockPos> removed = new java.util.LinkedHashSet<>();
                BlockPos lowestAir = null;
                for (double y = yMin; y <= yMax; y++) {
                    BlockPos pt = new BlockPos(xi, (int) Math.floor(y), zi);
                    BlockState state = w.at(pt);
                    if (state.isAir()) {
                        if (lowestAir == null) {
                            lowestAir = pt;
                        }
                        continue;
                    }
                    if (lowestAir == null) {
                        continue;
                    }
                    BlockPos target = lowestAir;
                    lowestAir = lowestAir.above();
                    removed.remove(target);
                    column.put(target, state);
                    removed.add(pt);
                }
                moved.putAll(column);
                for (BlockPos gone : removed) {
                    moved.put(gone, Blocks.AIR.defaultBlockState());
                }
            }
        }
        List<BlockAt> out = new ArrayList<>();
        moved.forEach((pos, state) -> {
            if (w.at(pos) != state) {
                out.add(block(pos, state));
            }
        });
        return capped(out);
    }

    /**
     * 高度图笔刷:每列从最高的地面起,按 grid(0 到 1 的格子,重采样到 radius 取整后的 2 倍加 1 见方,取最近的)乘 intensity 的高度往上加(erase:
     * 往下挖;flatten:不越过 at 那一层)。
     */
    static List<BlockAt> heightmap(LoadedBlocks w, BlockPos at, double radius, double[][] grid, double intensity, boolean erase,
                                   boolean flatten, boolean randomize) {
        int size = (int) Math.ceil(radius);
        int side = size * 2 + 1;
        w.require(at.getX() - size, at.getZ() - size, at.getX() + size, at.getZ() + size);
        double random = randomize ? ThreadLocalRandom.current().nextDouble() : 0;
        Map<BlockPos, BlockState> writes = new LinkedHashMap<>();
        for (int offX = -size; offX <= size; offX++) {
            for (int offZ = -size; offZ <= size; offZ++) {
                int x = at.getX() + offX;
                int z = at.getZ() + offZ;
                int top = highest(w, x, z, w.minY(), w.maxY(), null);
                double height = sample(grid, offX + size, offZ + size, side) * intensity;
                if (randomize && random > 1 - height % 1) {
                    height += 1;
                }
                BlockState base = erase ? null : w.at(x, top, z);
                for (int y = 0; y < height; y++) {
                    if (erase) {
                        if (!flatten || top - y > at.getY()) {
                            writes.put(new BlockPos(x, top - y, z), Blocks.AIR.defaultBlockState());
                        }
                    } else if (!flatten || top + y <= at.getY()) {
                        writes.put(new BlockPos(x, top + y, z), base);
                    }
                }
            }
        }
        List<BlockAt> out = new ArrayList<>();
        writes.forEach((pos, state) -> {
            if (w.at(pos) != state) {
                out.add(block(pos, state));
            }
        });
        return capped(out);
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
