package com.dwinovo.numen.tools.shape;

import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.scan.BlockScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 形状要读的世界:世界里一格是什么,全仓读世界的形状操作只经这里。只读已加载的区块({@link BlockScanner#loadedChunk}):读不到的格整个调用
 * 失败并说出是哪一片,不读一半、不为了答这一问去加载区块。世界高度之外是空气。读不改世界。单测用没有世界的子类替掉读法。
 */
class LoadedBlocks {

    /** 一次调用读的水平范围上限(列数):1024 见方,主线程上一次读不被一个写错的半径拖住。 */
    static final long MAX_COLUMNS = 1024L * 1024L;

    private final ServerLevel level;
    private final BlockPos her;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private ChunkAccess chunk;
    private int chunkX = Integer.MIN_VALUE;
    private int chunkZ = Integer.MIN_VALUE;

    LoadedBlocks(ServerLevel level, BlockPos her) {
        this.level = level;
        this.her = her;
    }

    int minY() {
        return level.getMinBuildHeight();
    }

    /** 世界里最高的一格。 */
    int maxY() {
        return level.getMaxBuildHeight() - 1;
    }

    ServerLevel level() {
        return level;
    }

    BlockState at(int x, int y, int z) {
        if (y < minY() || y > maxY()) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        return chunkAt(x, z).getBlockState(cursor.set(x, y, z));
    }

    private ChunkAccess chunkAt(int x, int z) {
        return chunk(x >> 4, z >> 4);
    }

    /** 已加载的这个区块;没加载就整个调用失败。 */
    ChunkAccess chunk(int cx, int cz) {
        if (chunk == null || cx != chunkX || cz != chunkZ) {
            ChunkAccess loaded = BlockScanner.loadedChunk(level, cx, cz);
            if (loaded == null) {
                throw unloaded(List.of(new int[]{cx, cz}), 1);
            }
            chunk = loaded;
            chunkX = cx;
            chunkZ = cz;
        }
        return chunk;
    }

    /** 这一格所在的区块已加载(写之前问:往没加载的区块写,等于要读它)。 */
    void requireLoaded(BlockPos pos) {
        chunkAt(pos.getX(), pos.getZ());
    }

    /** 这一格的群系,读已加载的区块。 */
    net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome(BlockPos pos) {
        return chunkAt(pos.getX(), pos.getZ()).getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);
    }

    BlockState at(BlockPos pos) {
        return at(pos.getX(), pos.getY(), pos.getZ());
    }

    /** 这个方框(含边界)盖到的区块都已加载,否则整个调用失败:读之前先问一遍,免得读一半才发现。 */
    void require(int minX, int minZ, int maxX, int maxZ) {
        long columns = ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1);
        if (columns > MAX_COLUMNS) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "that reads " + columns + " columns of the world, more than the "
                    + MAX_COLUMNS + " one call reads; ask for a smaller area (or a part of it at a time)", null);
        }
        Set<Long> chunks = new LinkedHashSet<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                chunks.add(ChunkPos.asLong(cx, cz));
            }
        }
        requireChunks(chunks);
    }

    /** 这些区块({@link ChunkPos#asLong})都已加载,否则整个调用失败。散开的格只要它们各自盖到的区块,不按外包盒。 */
    void requireChunks(Set<Long> chunks) {
        List<int[]> missing = new ArrayList<>();
        int count = 0;
        for (long key : chunks) {
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            if (BlockScanner.loadedChunk(level, cx, cz) == null) {
                count++;
                if (missing.size() < 3) {
                    missing.add(new int[]{cx, cz});
                }
            }
        }
        if (count > 0) {
            throw unloaded(missing, count);
        }
    }

    private ApiError unloaded(List<int[]> chunks, int count) {
        StringBuilder where = new StringBuilder();
        for (int[] c : chunks) {
            where.append(where.isEmpty() ? "" : "; ").append("x ").append(c[0] * 16).append(" to ").append(c[0] * 16 + 15)
                    .append(", z ").append(c[1] * 16).append(" to ").append(c[1] * 16 + 15);
        }
        int[] first = chunks.getFirst();
        BlockPos middle = new BlockPos(first[0] * 16 + 8, her.getY(), first[1] * 16 + 8);
        return new ApiError(ErrorKind.NOT_FOUND, "the world there is not loaded, so it cannot be read: " + where
                + (count > chunks.size() ? " and " + (count - chunks.size()) + " more chunk(s)" : ""),
                Call.of("numen.move.to", middle));
    }
}
