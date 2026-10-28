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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

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
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : cells) {
            region.add(p.asLong());
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        // 外面从包围盒表面上通的格起;只经选区里的格往外蔓延,所以只有选区格自己或贴着它的表面格会影响结果,只看这些,
        // 代价随格数走而不随包围盒的面积走
        Set<Long> outside = new HashSet<>();
        ArrayDeque<BlockPos> spreading = new ArrayDeque<>();
        for (BlockPos p : cells) {
            for (int i = -1; i < SIX.length; i++) {
                BlockPos q = i < 0 ? p : p.relative(SIX[i]);
                boolean onFace = q.getX() >= minX && q.getX() <= maxX && q.getY() >= minY && q.getY() <= maxY
                        && q.getZ() >= minZ && q.getZ() <= maxZ
                        && (q.getX() == minX || q.getX() == maxX || q.getY() == minY || q.getY() == maxY
                        || q.getZ() == minZ || q.getZ() == maxZ);
                if (onFace && !w.at(q).blocksMotion() && outside.add(q.asLong()) && region.contains(q.asLong())) {
                    spreading.add(q);
                }
            }
        }
        while (!spreading.isEmpty()) {
            BlockPos p = spreading.poll();
            for (Direction d : SIX) {
                BlockPos q = p.relative(d);
                if (!w.at(q).blocksMotion() && outside.add(q.asLong()) && region.contains(q.asLong())) {
                    spreading.add(q);
                }
            }
        }
        // 每加厚一层,把贴着外面的选区格同时并进外面(按这一轮开始时的外面判)
        for (int round = 1; round < thickness; round++) {
            List<Long> grown = new ArrayList<>();
            for (long key : region) {
                if (!outside.contains(key) && touches(outside, BlockPos.of(key))) {
                    grown.add(key);
                }
            }
            outside.addAll(grown);
        }
        List<BlockPos> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (BlockPos p : cells) {
            if (seen.add(p.asLong()) && !touches(outside, p)) {
                out.add(p.immutable());
            }
        }
        return out;
    }

    /** p 的六邻里有没有在 set 里的。 */
    private static boolean touches(Set<Long> set, BlockPos p) {
        for (Direction d : SIX) {
            if (set.contains(p.relative(d).asLong())) {
                return true;
            }
        }
        return false;
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
            int top = maxY;
            while (top >= minY && !natural(w.at(x, top, z))) {
                top--;
            }
            // 深度按高度差算:夹在中间的别的方块不动,但也算进深度
            for (int y = minY; y <= top; y++) {
                if (!natural(w.at(x, y, z))) {
                    continue;
                }
                int depth = top - y;
                BlockState want = depth == 0 ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : depth <= 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState();
                put(w, out, x, y, z, want);
            }
        }
        return capped(out);
    }

    /** {@code //smooth}:选区包围盒里每列的地表高度(蒙版里最高的一格,缺省挡得住移动的),Gaussian 核(5, 1.0)卷积 iterations 遍,列照新高度升或降。 */
    static List<BlockAt> smooth(LoadedBlocks w, BlockPos lo, BlockPos hi, int iterations, Mask mask, int reach) {
        w.require(lo.getX() - reach, lo.getZ() - reach, hi.getX() + reach, hi.getZ() + reach);
        int y0 = lo.getY();
        int y1 = hi.getY();
        int width = hi.getX() - lo.getX() + 1;
        int length = hi.getZ() - lo.getZ() + 1;
        int[] ground = new int[width * length];
        float[] heights = new float[ground.length];
        for (int dz = 0; dz < length; dz++) {
            for (int dx = 0; dx < width; dx++) {
                ground[dz * width + dx] = highest(w, lo.getX() + dx, lo.getZ() + dz, y0, y1, mask);
                heights[dz * width + dx] = ground[dz * width + dx];
            }
        }
        // 每遍都读上一遍取整后的高度
        for (int pass = 0; pass < iterations; pass++) {
            heights = blur(heights, width, length);
            for (int i = 0; i < heights.length; i++) {
                heights[i] = (float) Math.floor(heights[i] + 0.5);
            }
        }
        List<BlockAt> out = new ArrayList<>();
        for (int dz = 0; dz < length; dz++) {
            for (int dx = 0; dx < width; dx++) {
                int x = lo.getX() + dx;
                int z = lo.getZ() + dz;
                int was = ground[dz * width + dx];
                int target = Math.min(y1, (int) heights[dz * width + dx]);
                if (target == was) {
                    continue;
                }
                BlockState surface = w.at(x, was, z);
                if (target > was && liquid(surface)) {
                    continue;
                }
                // 列从 y0 起按比例伸缩:新的第 y 格抄原来按比例落到的那一格;顶上一格是原来的地表
                double scale = (double) (was - y0) / (target - y0);
                for (int y = y0; y < target; y++) {
                    put(w, out, x, y, z, w.at(x, y0 + (int) Math.floor((y - y0) * scale), z));
                }
                put(w, out, x, target, z, surface);
                for (int y = target + 1; y <= was; y++) {
                    put(w, out, x, y, z, Blocks.AIR.defaultBlockState());
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

    private static final int BLUR_RADIUS = 5;
    private static final float[] BLUR_KERNEL = gaussian(BLUR_RADIUS, 1.0);

    /**
     * 高度图(下标 z × width + x)用高斯核卷一遍,不取整:smooth 每遍自己取整,snowsmooth 留小数。偏移落到图外的那个方向改用这一格自己的
     * 行或列(两个方向各自换)。单精度累加,先行(z)后列(x)。
     */
    private static float[] blur(float[] heights, int width, int length) {
        int side = 2 * BLUR_RADIUS + 1;
        float[] out = new float[heights.length];
        for (int z = 0; z < length; z++) {
            for (int x = 0; x < width; x++) {
                float sum = 0;
                for (int oz = -BLUR_RADIUS; oz <= BLUR_RADIUS; oz++) {
                    int rz = z + oz < 0 || z + oz >= length ? z : z + oz;
                    for (int ox = -BLUR_RADIUS; ox <= BLUR_RADIUS; ox++) {
                        int rx = x + ox < 0 || x + ox >= width ? x : x + ox;
                        sum += BLUR_KERNEL[(oz + BLUR_RADIUS) * side + ox + BLUR_RADIUS] * heights[rz * width + rx];
                    }
                }
                out[z * width + x] = sum;
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
        int y0 = lo.getY();
        int y1 = hi.getY();
        int width = hi.getX() - lo.getX() + 1;
        int length = hi.getZ() - lo.getZ() + 1;
        float[] ground = new float[width * length];
        for (int dz = 0; dz < length; dz++) {
            for (int dx = 0; dx < width; dx++) {
                int x = lo.getX() + dx;
                int z = lo.getZ() + dz;
                int top = highest(w, x, z, y0, y1, mask);
                BlockState above = w.at(x, top + 1, z);
                float height;
                if (above.is(Blocks.SNOW)) {
                    height = top + 1 + (above.getValue(SnowLayerBlock.LAYERS) - 1) / 8f;
                } else if (w.at(x, top, z).isAir()) {
                    height = top;
                } else {
                    height = top + 1;
                }
                ground[dz * width + dx] = height;
            }
        }
        float[] heights = ground;
        for (int pass = 0; pass < iterations; pass++) {
            heights = blur(heights, width, length);
        }
        BlockState snowBlock = Blocks.SNOW_BLOCK.defaultBlockState();
        List<BlockAt> out = new ArrayList<>();
        for (int dz = 0; dz < length; dz++) {
            for (int dx = 0; dx < width; dx++) {
                int x = lo.getX() + dx;
                int z = lo.getZ() + dz;
                float was = ground[dz * width + dx];
                if (was == y0) {
                    continue;
                }
                // 加半层,平地上也落得下一层雪
                float target = Math.min(y1, heights[dz * width + dx] + 0.0625f);
                double scale = (was - y0) / (double) (target - y0);
                if (target >= was) {
                    if (liquid(w.at(x, (int) Math.floor(was), z))) {
                        continue;
                    }
                    putSnowLayer(w, out, x, z, target);
                    int last = (int) Math.floor(target - 1 - y0);
                    int firstSnow = (int) Math.floor(target - 1 - y0 - layerBlocks);
                    for (int k = 0; k <= last; k++) {
                        put(w, out, x, y0 + k, z, k >= firstSnow ? snowBlock : w.at(x, y0 + (int) Math.floor(k * scale), z));
                    }
                } else {
                    int last = (int) Math.floor(target - y0) - 1;
                    int firstSnow = (int) Math.floor(target - y0 - layerBlocks);
                    for (int k = 0; k <= last; k++) {
                        put(w, out, x, y0 + k, z, k >= firstSnow ? snowBlock : w.at(x, y0 + (int) Math.floor(k * scale), z));
                    }
                    putSnowLayer(w, out, x, z, target);
                    for (int y = (int) Math.floor(target + 1); y <= (int) Math.floor(was); y++) {
                        put(w, out, x, y, z, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        return capped(out);
    }

    /** 雪面高 height:整数部分那一格放雪层,小数部分每 1/8 多一层。 */
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
        int reach = (int) Math.ceil(size);
        w.require(center.getX() - reach - 1, center.getZ() - reach - 1, center.getX() + reach + 1, center.getZ() + reach + 1);
        // 球外再多一圈:那一圈始终是世界原样,只当邻格读
        int side = 2 * reach + 3;
        int origin = reach + 1;
        BlockState[][][] states = new BlockState[side][side][side];
        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                for (int k = 0; k < side; k++) {
                    states[i][j][k] = w.at(center.getX() + i - origin, center.getY() + j - origin, center.getZ() + k - origin);
                }
            }
        }
        double sizeSq = size * size;
        for (int pass = 0; pass < erodeIterations; pass++) {
            states = morphPass(states, reach, sizeSq, true, minErodeFaces);
        }
        for (int pass = 0; pass < dilateIterations; pass++) {
            states = morphPass(states, reach, sizeSq, false, minDilateFaces);
        }
        List<BlockAt> out = new ArrayList<>();
        for (int i = -reach; i <= reach; i++) {
            for (int j = -reach; j <= reach; j++) {
                for (int k = -reach; k <= reach; k++) {
                    if (i * i + j * j + k * k <= sizeSq) {
                        put(w, out, center.getX() + i, center.getY() + j, center.getZ() + k, states[i + origin][j + origin][k + origin]);
                    }
                }
            }
        }
        return capped(out);
    }

    private static boolean emptyOrLiquid(BlockState s) {
        return s.isAir() || liquid(s);
    }

    /** 侵蚀(erode)看露出的面、改实心方块;膨胀看贴着的实心面、改空的格。 */
    private static BlockState[][][] morphPass(BlockState[][][] from, int reach, double sizeSq, boolean erode, int minFaces) {
        int origin = reach + 1;
        BlockState[][][] to = new BlockState[from.length][from.length][];
        for (int i = 0; i < from.length; i++) {
            for (int j = 0; j < from.length; j++) {
                to[i][j] = from[i][j].clone();
            }
        }
        for (int i = -reach; i <= reach; i++) {
            for (int j = -reach; j <= reach; j++) {
                for (int k = -reach; k <= reach; k++) {
                    if (i * i + j * j + k * k > sizeSq) {
                        continue;
                    }
                    int x = i + origin;
                    int y = j + origin;
                    int z = k + origin;
                    // 侵蚀只改实心格、数空的邻面;膨胀只改空格、数实心的邻面
                    if (emptyOrLiquid(from[x][y][z]) == erode) {
                        continue;
                    }
                    BlockState winner = null;
                    int best = 0;
                    int faces = 0;
                    Map<BlockState, Integer> counts = new HashMap<>();
                    for (Direction d : SIX) {
                        BlockState n = from[x + d.getStepX()][y + d.getStepY()][z + d.getStepZ()];
                        if (emptyOrLiquid(n) != erode) {
                            continue;
                        }
                        faces++;
                        int c = counts.merge(n, 1, Integer::sum);
                        // 严格大于才换:并列时留下先凑满这个数的那一个
                        if (c > best) {
                            best = c;
                            winner = n;
                        }
                    }
                    if (faces >= minFaces && winner != null) {
                        to[x][y][z] = winner;
                    }
                }
            }
        }
        return to;
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
        Map<BlockState, Integer> counts = new LinkedHashMap<>();
        for (BlockPos p : cells) {
            if (mask != null && !mask.test(w, p.getX(), p.getY(), p.getZ())) {
                continue;
            }
            BlockState s = w.at(p);
            counts.merge(separate ? s : s.getBlock().defaultBlockState(), 1, Integer::sum);
        }
        // 先倒成"首次出现越晚越靠前",再按数量稳定排序:数量相同的保持这个次序
        List<Map.Entry<BlockState, Integer>> entries = new ArrayList<>(counts.entrySet());
        Collections.reverse(entries);
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<Counted> out = new ArrayList<>(entries.size());
        for (Map.Entry<BlockState, Integer> e : entries) {
            out.add(new Counted(BuildStates.written(e.getKey()), e.getValue()));
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

    /** 一格只在第一次被当作邻格试的时候判一次:那次走不进,之后从别处来也不再收。 */
    private static List<BlockPos> walk(List<BlockPos> starts, Direction[] directions, Visitable visitable) {
        List<BlockPos> out = new ArrayList<>();
        Set<Long> tried = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos s : starts) {
            if (tried.add(s.asLong())) {
                out.add(s.immutable());
                queue.add(s.immutable());
            }
        }
        capped(out);
        while (!queue.isEmpty()) {
            BlockPos from = queue.poll();
            for (Direction d : directions) {
                BlockPos to = from.relative(d);
                if (tried.add(to.asLong()) && visitable.test(from, to)) {
                    out.add(to);
                    capped(out);
                    queue.add(to);
                }
            }
        }
        return out;
    }

    /** {@code //fill} 与 {@code //fillr}:从 at 起,球里、不高于 at、不深于 depth 的空气;fill 只在 at 那一层横着走、再往下,fillr 六个方向都走。 */
    static List<BlockPos> fill(LoadedBlocks w, BlockPos at, double radius, int depth, boolean recursive) {
        int top = Math.min(w.maxY(), at.getY());
        int deepest = at.getY() - depth + 1;
        // depth 极大时减法会溢出成比 at 还高的数:那就是没有下界
        int bottom = deepest > at.getY() ? w.minY() : Math.max(w.minY(), deepest);
        Visitable visitable = (from, to) -> to.getY() >= bottom && to.getY() <= top
                && inBall(at, radius, to.getX(), to.getY(), to.getZ()) && w.at(to).isAir()
                && (recursive || to.getY() < from.getY() || from.getY() == at.getY());
        return walk(List.of(at), recursive ? SIX : WITHOUT_UP, visitable);
    }

    /** {@code //drain}:at 周围 3×3×3 里水与岩浆(waterlogged 时加上含水的方块)连着的一片,球里;液体换空气,含水的方块去掉水。 */
    static List<BlockAt> drain(LoadedBlocks w, BlockPos at, double radius, boolean waterlogged) {
        Predicate<BlockPos> wet = p -> p.getY() >= w.minY() && p.getY() <= w.maxY()
                && inBall(at, radius, p.getX(), p.getY(), p.getZ()) && drainable(w.at(p), waterlogged);
        List<BlockPos> starts = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1))) {
            if (wet.test(p)) {
                starts.add(p.immutable());
            }
        }
        List<BlockAt> out = new ArrayList<>();
        for (BlockPos p : walk(starts, SIX, (from, to) -> wet.test(to))) {
            BlockState s = w.at(p);
            BlockState dry = waterlogged && s.hasProperty(BlockStateProperties.WATERLOGGED)
                    ? s.setValue(BlockStateProperties.WATERLOGGED, false) : Blocks.AIR.defaultBlockState();
            put(w, out, p.getX(), p.getY(), p.getZ(), dry);
        }
        return out;
    }

    private static boolean drainable(BlockState s, boolean waterlogged) {
        return liquid(s) || (waterlogged && s.hasProperty(BlockStateProperties.WATERLOGGED)
                && s.getValue(BlockStateProperties.WATERLOGGED));
    }

    /** {@code //fixwater}、{@code //fixlava}:at 周围 3×3×3 里这种流体连着的一片,球里、不高于 at,只向下与横着走进空气或同种流体,都写成源头。 */
    static List<BlockAt> fixLiquid(LoadedBlocks w, BlockPos at, double radius, BlockState source) {
        int top = Math.min(at.getY(), w.maxY());
        // 起点只看是不是这种流体:at 周围高出它一格的流体也算起点
        List<BlockPos> starts = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1))) {
            if (w.at(p).is(source.getBlock())) {
                starts.add(p.immutable());
            }
        }
        Visitable visitable = (from, to) -> {
            if (to.getY() < w.minY() || to.getY() > top || !inBall(at, radius, to.getX(), to.getY(), to.getZ())) {
                return false;
            }
            BlockState s = w.at(to);
            return s.is(source.getBlock()) || s.isAir();
        };
        List<BlockAt> out = new ArrayList<>();
        for (BlockPos p : walk(starts, WITHOUT_UP, visitable)) {
            put(w, out, p.getX(), p.getY(), p.getZ(), source);
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
        List<BlockPos> columns = columnsOf(at, radius);
        requireAround(w, columns, 0);
        int lo = at.getY() - height;
        int hi = at.getY() + height;
        List<BlockAt> out = new ArrayList<>();
        for (BlockPos c : columns) {
            int x = c.getX();
            int z = c.getZ();
            if (snowGround(w.at(x, hi + 1, z), stack)) {
                continue;
            }
            int y = hi;
            while (y >= lo && !snowGround(w.at(x, y, z), stack)) {
                y--;
            }
            if (y >= lo) {
                snowOn(w, out, new BlockPos(x, y, z), stack);
            }
        }
        return capped(out);
    }

    /** 托得住雪的地面:水(任何等级,静水结冰用)或挡得住移动的方块;stack 时雪层不算地面,雪落在它里面。 */
    private static boolean snowGround(BlockState s, boolean stack) {
        if (s.isAir() || (stack && s.is(Blocks.SNOW))) {
            return false;
        }
        return s.is(Blocks.WATER) || s.blocksMotion();
    }

    /** 地面 ground 上落雪:静水结冰;头上是空气就放一层(原版判得住的地方),stack 时头上的雪层再加一层、满七层成雪块。 */
    private static void snowOn(LoadedBlocks w, List<BlockAt> out, BlockPos ground, boolean stack) {
        BlockState s = w.at(ground);
        if (s.is(Blocks.WATER)) {
            if (s.getValue(LiquidBlock.LEVEL) == 0) {
                put(w, out, ground.getX(), ground.getY(), ground.getZ(), Blocks.ICE.defaultBlockState());
            }
            return;
        }
        if (ground.getY() >= w.maxY()) {
            return;
        }
        BlockPos above = ground.above();
        BlockState a = w.at(above);
        if (stack && a.is(Blocks.SNOW)) {
            int layers = a.getValue(SnowLayerBlock.LAYERS);
            put(w, out, above.getX(), above.getY(), above.getZ(),
                    layers >= 7 ? Blocks.SNOW_BLOCK.defaultBlockState() : a.setValue(SnowLayerBlock.LAYERS, layers + 1));
        } else if (a.isAir() && Blocks.SNOW.defaultBlockState().canSurvive(w.level(), above)) {
            put(w, out, above.getX(), above.getY(), above.getZ(), Blocks.SNOW.defaultBlockState());
        }
    }

    /** {@code //thaw}:圆柱里每列最上面不是空气的一格:冰化成水、雪层变空气。 */
    static List<BlockAt> thaw(LoadedBlocks w, BlockPos at, double radius, int height) {
        List<BlockPos> columns = columnsOf(at, radius);
        requireAround(w, columns, 0);
        int c = Math.max(w.minY(), Math.min(w.maxY(), at.getY()));
        int lo = Math.max(w.minY(), c - height);
        int hi = Math.min(w.maxY(), c + height);
        List<BlockAt> out = new ArrayList<>();
        for (BlockPos column : columns) {
            int x = column.getX();
            int z = column.getZ();
            for (int y = hi; y > lo; y--) {
                BlockState s = w.at(x, y, z);
                if (s.isAir()) {
                    continue;
                }
                if (s.is(Blocks.ICE)) {
                    put(w, out, x, y, z, Blocks.WATER.defaultBlockState());
                } else if (s.is(Blocks.SNOW)) {
                    put(w, out, x, y, z, Blocks.AIR.defaultBlockState());
                }
                break;
            }
        }
        return capped(out);
    }

    /** {@code //green}:圆柱里每列从上往下遇到的第一个泥土(coarse 时含粗泥土)变草方块;先遇到液体或挡得住移动的就停。 */
    static List<BlockAt> green(LoadedBlocks w, BlockPos at, double radius, int height, boolean coarse) {
        List<BlockPos> columns = columnsOf(at, radius);
        requireAround(w, columns, 0);
        int c = Math.max(w.minY(), Math.min(w.maxY(), at.getY()));
        int lo = Math.max(w.minY(), c - height);
        int hi = Math.min(w.maxY(), c + height);
        List<BlockAt> out = new ArrayList<>();
        for (BlockPos column : columns) {
            int x = column.getX();
            int z = column.getZ();
            for (int y = hi; y > lo; y--) {
                BlockState s = w.at(x, y, z);
                // 泥土本身挡得住移动,所以先认泥土
                if (s.is(Blocks.DIRT) || (coarse && s.is(Blocks.COARSE_DIRT))) {
                    put(w, out, x, y, z, Blocks.GRASS_BLOCK.defaultBlockState());
                    break;
                }
                if (liquid(s) || s.blocksMotion()) {
                    break;
                }
            }
        }
        return capped(out);
    }

    // ---- 笔刷:gravity、heightmap ----

    /** 重力笔刷:radius 见方的每一列里,把悬空的方块落到它下面最低的空气上;落走的地方成空气。 */
    static List<BlockAt> gravity(LoadedBlocks w, BlockPos at, double radius, double height) {
        w.require((int) Math.floor(at.getX() - radius), (int) Math.floor(at.getZ() - radius),
                (int) Math.floor(at.getX() + radius), (int) Math.floor(at.getZ() + radius));
        int lo = (int) Math.max(at.getY() - height, w.minY());
        int hi = (int) Math.min(at.getY() + height, w.maxY());
        // 偏移从 -radius 起一格一格数到不超过 radius,各自向下取整:半径带小数时两边不对称
        List<Integer> offsets = new ArrayList<>();
        for (int k = 0; -radius + k <= radius; k++) {
            offsets.add((int) Math.floor(-radius + k));
        }
        List<BlockAt> out = new ArrayList<>();
        for (int ox : offsets) {
            for (int oz : offsets) {
                int x = at.getX() + ox;
                int z = at.getZ() + oz;
                int air = lo;
                while (air <= hi && !w.at(x, air, z).isAir()) {
                    air++;
                }
                if (air > hi) {
                    continue;
                }
                List<BlockState> falling = new ArrayList<>();
                for (int y = air + 1; y <= hi; y++) {
                    BlockState s = w.at(x, y, z);
                    if (!s.isAir()) {
                        falling.add(s);
                    }
                }
                for (int y = air; y <= hi; y++) {
                    int i = y - air;
                    if (i < falling.size()) {
                        put(w, out, x, y, z, falling.get(i));
                    } else if (!w.at(x, y, z).isAir()) {
                        put(w, out, x, y, z, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        return capped(out);
    }

    /**
     * 高度图笔刷:每列从最高的地面起,按 grid(0 到 1 的格子,重采样到 radius 取整后的 2 倍加 1 见方,取最近的)乘 intensity 的高度往上加(erase:
     * 往下挖;flatten:不越过 at 那一层)。
     */
    static List<BlockAt> heightmap(LoadedBlocks w, BlockPos at, double radius, double[][] grid, double intensity, boolean erase,
                                   boolean flatten, boolean randomize) {
        int n = (int) Math.ceil(radius);
        int side = 2 * n + 1;
        w.require(at.getX() - n, at.getZ() - n, at.getX() + n, at.getZ() + n);
        // 一次调用只抽一个数:各列的小数部分按同一个门槛进位
        double chance = randomize ? ThreadLocalRandom.current().nextDouble() : 0;
        List<BlockAt> out = new ArrayList<>();
        for (int ox = -n; ox <= n; ox++) {
            for (int oz = -n; oz <= n; oz++) {
                int x = at.getX() + ox;
                int z = at.getZ() + oz;
                int top = highest(w, x, z, w.minY(), w.maxY(), null);
                double raise = sample(grid, ox + n, oz + n, side) * intensity;
                if (randomize && chance > 1 - raise % 1) {
                    raise += 1;
                }
                // 满足 0 ≤ k < raise 的整数 k 的个数
                int layers = raise <= 0 ? 0 : (int) Math.ceil(raise);
                BlockState ground = w.at(x, top, z);
                for (int k = 0; k < layers; k++) {
                    if (erase) {
                        if (!flatten || top - k > at.getY()) {
                            put(w, out, x, top - k, z, Blocks.AIR.defaultBlockState());
                        }
                    } else if (!flatten || top + k <= at.getY()) {
                        put(w, out, x, top + k, z, ground);
                    }
                }
            }
        }
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
