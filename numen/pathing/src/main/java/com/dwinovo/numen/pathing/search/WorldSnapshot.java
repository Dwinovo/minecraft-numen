package com.dwinovo.numen.pathing.search;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.dwinovo.numen.pathing.world.CellFlags;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;

/**
 * 搜索视图:派发搜索时在世界所在的线程上拷贝一块区域里已加载区块的方块段,工作线程只读这份拷贝,不碰活世界。
 * 拷贝的是每个非空区段的方块调色板({@code PalettedContainer.copy}),全空的区段记为空气;世界边界与建筑高度一并抄下。
 * 区域是以一个区块为中心、边长 {@code 2·radius+1} 的正方形,没加载的区块留空,{@link #isLoaded} 答否。
 *
 * <p>拷贝之后只读,不再变;多个线程同时读是安全的。方块实体不拷,读出来都是 null——一格有没有方块实体是方块状态的事,
 * 里面装着什么只在世界所在的线程上读。
 *
 * <p>读一格方块要经过调色板和位压缩的存储,一次搜索要读几亿次、而且反复读同一片格子,所以读到的那一层(一个区段里水平 16×16 格)
 * 第一次被读时整层解开,放进 {@code 层表},之后读一格只是数组下标。只解读到的层,搜索没碰过的区段与层一格不解。几个线程同时第一次读到
 * 同一层各解一份,内容一样,谁的先放进去都行(放进去用释放语义,读出来用获取语义,看到数组时数组里的内容一定写完了)。
 *
 * <p>搜索的快判断读的是每格的标志字({@link CellFlags}):一层的标志字在第一次被问到时由那一层的方块状态推出,放进另一张同样下标的层表,
 * 同样是几个线程各推一份、谁的先放进去都行。
 */
public final class WorldSnapshot implements SearchView {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** 全是空气的一层,全空的区段的每一层共用。 */
    private static final BlockState[] AIR_LAYER = airLayer();
    /** 全是空气的一层的标志字,全空的区段的每一层共用。 */
    private static final byte[] AIR_FLAGS = airFlags();
    private static final VarHandle LAYER = MethodHandles.arrayElementVarHandle(BlockState[][].class);
    private static final VarHandle FLAG_LAYER = MethodHandles.arrayElementVarHandle(byte[][].class);

    private static byte[] airFlags() {
        byte[] layer = new byte[256];
        java.util.Arrays.fill(layer, (byte) CellFlags.AIR);
        return layer;
    }

    private static BlockState[] airLayer() {
        BlockState[] layer = new BlockState[256];
        java.util.Arrays.fill(layer, AIR);
        return layer;
    }

    /** 一个区块的方块段从哪来:给出那个区块的全部区段,没加载为 null。只在世界所在的线程上调用。 */
    @FunctionalInterface
    public interface Sections {
        LevelChunkSection[] of(int chunkX, int chunkZ);
    }

    private final int minChunkX;
    private final int minChunkZ;
    private final int side;
    /** [区块][区段]:区块没加载为 null,区段全空为 null。 */
    private final PalettedContainer<BlockState>[][] chunks;
    /** [区块][区段 × 16 + 区段内的层]:解开过的层,行优先 {@code z * 16 + x};还没解的为 null;区块没加载为 null。 */
    private final BlockState[][][] layers;
    /** 同上的下标,每层每格一个标志字({@link CellFlags}):读到某一层的标志时由那一层的方块状态推出,放进来。 */
    private final byte[][][] flagLayers;
    private final int minBuildHeight;
    private final int height;
    private final int minSection;
    private final WorldBorder border;
    private final boolean ultraWarm;

    @SuppressWarnings("unchecked")
    private WorldSnapshot(LevelHeightAccessor heights, WorldBorder border, boolean ultraWarm, Sections source,
                          int centerX, int centerZ, int radius) {
        this.minChunkX = centerX - radius;
        this.minChunkZ = centerZ - radius;
        this.side = 2 * radius + 1;
        this.chunks = new PalettedContainer[side * side][];
        this.layers = new BlockState[side * side][][];
        this.flagLayers = new byte[side * side][][];
        this.minBuildHeight = heights.getMinBuildHeight();
        this.height = heights.getHeight();
        this.minSection = heights.getMinSection();
        this.border = copy(border);
        this.ultraWarm = ultraWarm;
        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                LevelChunkSection[] sections = source.of(minChunkX + i, minChunkZ + j);
                if (sections == null) {
                    continue;
                }
                PalettedContainer<BlockState>[] states = new PalettedContainer[sections.length];
                for (int s = 0; s < sections.length; s++) {
                    if (sections[s] != null && !sections[s].hasOnlyAir()) {
                        states[s] = sections[s].getStates().copy();
                    }
                }
                chunks[i * side + j] = states;
                layers[i * side + j] = new BlockState[states.length * 16][];
                flagLayers[i * side + j] = new byte[states.length * 16][];
            }
        }
    }

    /**
     * 派发一次搜索时拷贝的半径(区块数):以起点所在区块为中心,边长 19 个区块,水平一百五十格以内的去处都在里面——一百二十八格以内的
     * 路一次搜索就能搜到终点,不碰快照的边。拷贝是调色板拷贝加按层懒解,靶场上主线程这一刻拷贝耗时中位 2.3 毫秒、最多 8.6 毫秒。
     */
    public static final int SEARCH_RADIUS = 9;

    /** 为从 {@code start} 出发的一次搜索拷贝快照:以它所在的区块为中心、半径 {@code radius} 个区块。必须在世界所在的线程上调用。 */
    public static WorldSnapshot around(Level level, BlockPos start, int radius) {
        return capture(level, SectionPos.blockToSectionCoord(start.getX()), SectionPos.blockToSectionCoord(start.getZ()),
                radius);
    }

    /**
     * 拷贝 {@code level} 里以区块 {@code (centerX, centerZ)} 为中心、半径 {@code radius} 个区块的已加载区块。必须在
     * {@code level} 所在的线程上调用;不加载任何区块。
     */
    public static WorldSnapshot capture(Level level, int centerX, int centerZ, int radius) {
        return capture(level, level.getWorldBorder(), level.dimensionType().ultraWarm(), (cx, cz) -> {
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            return chunk == null ? null : chunk.getSections();
        }, centerX, centerZ, radius);
    }

    /** 同上,方块段、建筑高度、世界边界与维度倒不倒得出水由调用方给出。 */
    public static WorldSnapshot capture(LevelHeightAccessor heights, WorldBorder border, boolean ultraWarm,
                                        Sections source, int centerX, int centerZ, int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("半径不能为负:" + radius);
        }
        return new WorldSnapshot(heights, border, ultraWarm, source, centerX, centerZ, radius);
    }

    /** 世界边界抄一份静止的:正在缩放的边界按此刻的大小。 */
    private static WorldBorder copy(WorldBorder live) {
        WorldBorder copy = new WorldBorder();
        copy.setCenter(live.getCenterX(), live.getCenterZ());
        copy.setSize(live.getSize());
        return copy;
    }

    private PalettedContainer<BlockState>[] chunk(int x, int z) {
        int i = SectionPos.blockToSectionCoord(x) - minChunkX;
        int j = SectionPos.blockToSectionCoord(z) - minChunkZ;
        if (i < 0 || j < 0 || i >= side || j >= side) {
            return null;
        }
        return chunks[i * side + j];
    }

    @Override
    public boolean isLoaded(int x, int z) {
        return chunk(x, z) != null;
    }

    /**
     * 先问每个方块段的调色板里有没有 {@code wanted} 答是的状态,有的段才逐格找(段全空的不算,那是空气)。段里状态太多、用上了
     * 全局调色板时调色板答"也许有",逐格找一遍。只看 {@code [fromY, toY)} 里的层。
     */
    @Override
    public void forEachBlock(int fromY, int toY, Predicate<BlockState> wanted, BlockVisitor visitor) {
        List<BlockState> hits = new ArrayList<>(4);
        BlockState[] scratch = new BlockState[256];
        int firstSection = Math.max(0, (fromY >> 4) - minSection);
        for (int c = 0; c < chunks.length; c++) {
            PalettedContainer<BlockState>[] states = chunks[c];
            if (states == null) {
                continue;
            }
            int lastSection = Math.min(states.length - 1, ((toY - 1) >> 4) - minSection);
            for (int s = firstSection; s <= lastSection; s++) {
                PalettedContainer<BlockState> section = states[s];
                if (section == null) {
                    continue;
                }
                // 调色板里要的状态逐个记下来(谓词一律答否,调色板就把每一种都问一遍);用上全局调色板时它不问、直接答"也许有",
                // 就逐格问谓词
                hits.clear();
                boolean everything = section.maybeHas(state -> {
                    if (wanted.test(state)) {
                        hits.add(state);
                    }
                    return false;
                });
                if (!everything && hits.isEmpty()) {
                    continue;
                }
                int bottom = (minSection + s) << 4;
                for (int y = Math.max(0, fromY - bottom); y < Math.min(16, toY - bottom); y++) {
                    // 搜索读过的层现成的就用;没读过的解到一块共用的缓冲里,扫完就丢(估价扫的段大多搜索一辈子也不会读)
                    BlockState[] known = (BlockState[]) LAYER.getAcquire(layers[c], s << 4 | y);
                    BlockState[] layer = known != null ? known : decode(section, y, scratch);
                    for (int cell = 0; cell < 256; cell++) {
                        BlockState state = layer[cell];
                        if (everything ? wanted.test(state) : hits.contains(state)) {
                            visitor.accept(state, ((minChunkX + c / side) << 4) + (cell & 15), bottom + y,
                                    ((minChunkZ + c % side) << 4) + (cell >> 4));
                        }
                    }
                }
            }
        }
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState state = loadedBlockState(pos);
        return state != null ? state : AIR;
    }

    /** 没加载的区块读出来是 null;建筑高度之外、全空的区段是空气。 */
    @Override
    public BlockState loadedBlockState(BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        int i = (x >> 4) - minChunkX;
        int j = (z >> 4) - minChunkZ;
        if (i < 0 || j < 0 || i >= side || j >= side) {
            return null;
        }
        BlockState[][] rows = layers[i * side + j];
        if (rows == null) {
            return null;
        }
        int row = ((y >> 4) - minSection) << 4 | (y & 15);
        if (row < 0 || row >= rows.length) {
            return AIR;
        }
        return layer(i * side + j, row)[(z & 15) << 4 | (x & 15)];
    }

    /** 没加载的区块的格带 {@link CellFlags#UNLOADED};建筑高度之外、全空的区段是空气。读一格是两次数组下标。 */
    @Override
    public int cellFlags(int x, int y, int z) {
        int i = (x >> 4) - minChunkX;
        int j = (z >> 4) - minChunkZ;
        if (i < 0 || j < 0 || i >= side || j >= side) {
            return CellFlags.UNLOADED;
        }
        byte[][] rows = flagLayers[i * side + j];
        if (rows == null) {
            return CellFlags.UNLOADED;
        }
        int row = ((y >> 4) - minSection) << 4 | (y & 15);
        if (row < 0 || row >= rows.length) {
            return CellFlags.AIR;
        }
        return flagLayer(i * side + j, rows, row)[(z & 15) << 4 | (x & 15)] & 0xFF;
    }

    /** 读同一列从 {@code y} 起往上 {@code n} 格的标志字:查区块只查一次。 */
    @Override
    public int cellFlags(int x, int y, int z, int n, byte[] into, int at) {
        int i = (x >> 4) - minChunkX;
        int j = (z >> 4) - minChunkZ;
        byte[][] rows = i < 0 || j < 0 || i >= side || j >= side ? null : flagLayers[i * side + j];
        if (rows == null) {
            java.util.Arrays.fill(into, at, at + n, (byte) CellFlags.UNLOADED);
            return CellFlags.UNLOADED;
        }
        int cell = (z & 15) << 4 | (x & 15);
        int all = 0;
        for (int k = 0; k < n; k++) {
            int row = (((y + k) >> 4) - minSection) << 4 | ((y + k) & 15);
            int flags = row < 0 || row >= rows.length ? CellFlags.AIR : flagLayer(i * side + j, rows, row)[cell] & 0xFF;
            into[at + k] = (byte) flags;
            all |= flags;
        }
        return all;
    }

    /** 区块 {@code chunk}(下标)的第 {@code row} 层的标志字,没推过就推出来放进去。 */
    private byte[] flagLayer(int chunk, byte[][] rows, int row) {
        byte[] layer = (byte[]) FLAG_LAYER.getAcquire(rows, row);
        if (layer == null) {
            layer = flags(layer(chunk, row));
            FLAG_LAYER.setRelease(rows, row, layer);
        }
        return layer;
    }

    private static byte[] flags(BlockState[] states) {
        if (states == AIR_LAYER) {
            return AIR_FLAGS;
        }
        byte[] out = new byte[256];
        for (int k = 0; k < 256; k++) {
            out[k] = (byte) CellFlags.of(states[k]);
        }
        return out;
    }

    /** 区块 {@code chunk}(下标)的第 {@code row} 层(区段 × 16 + 区段内的层),没解过就解开。 */
    private BlockState[] layer(int chunk, int row) {
        BlockState[][] rows = layers[chunk];
        BlockState[] layer = (BlockState[]) LAYER.getAcquire(rows, row);
        if (layer == null) {
            layer = decode(chunks[chunk][row >> 4], row & 15);
            LAYER.setRelease(rows, row, layer);
        }
        return layer;
    }

    /** 一个区段里第 {@code y} 层(区段内的高度)的 16×16 格解出来;区段全空的是共用的空气层。 */
    private static BlockState[] decode(PalettedContainer<BlockState> section, int y) {
        return section == null ? AIR_LAYER : decode(section, y, new BlockState[256]);
    }

    /** 同上,解进 {@code into}(256 格,行优先 {@code z * 16 + x})。 */
    private static BlockState[] decode(PalettedContainer<BlockState> section, int y, BlockState[] into) {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                into[z << 4 | x] = section.get(x, y, z);
            }
        }
        return into;
    }

    /** 没加载的区块、全空的区段(拷贝时记为空)、建筑高度之外,读出来都是空气。 */
    @Override
    public boolean airSection(int x, int y, int z) {
        PalettedContainer<BlockState>[] states = chunk(x, z);
        if (states == null) {
            return true;
        }
        int index = SectionPos.blockToSectionCoord(y) - minSection;
        return index < 0 || index >= states.length || states[index] == null;
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
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinBuildHeight() {
        return minBuildHeight;
    }

    @Override
    public WorldBorder border() {
        return border;
    }

    @Override
    public boolean ultraWarm() {
        return ultraWarm;
    }
}
