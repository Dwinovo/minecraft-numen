package com.dwinovo.numen.api.permission;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 世界生成时记下的结构件范围,一份不可变的快照:一个格子在不在某个生成结构的某一件里(村庄的房子与路、废弃矿井的巷道、
 * 沉船),以及那是哪种结构。结构记录随区块存档({@code StructureManager} 读的就是它),装模组之前生成的结构也在。
 *
 * <p>主线程按她身边的已加载区块捕获一次({@link #capture}),之后任何线程只读——寻路工作线程给每条边定价、主线程判一下动作,
 * 问的是同一份。离她超出捕获半径的格子认不出结构,等她走近、下一份快照里就有了。
 */
public record Structures(List<Piece> pieces) {

    /** 一件结构件:它的包围盒,和它属于哪种结构。 */
    public record Piece(BoundingBox box, Holder<Structure> structure) {}

    public static final Structures NONE = new Structures(List.of());

    /** 捕获半径(区块):结构起点的区块离被问的格子不会比这更远。 */
    public static final int CAPTURE_RADIUS_CHUNKS = 10;

    public Structures {
        pieces = List.copyOf(pieces);
    }

    /** {@code center} 周围 {@code chunkRadius} 个区块内、已加载区块里的结构件。主线程。 */
    public static Structures capture(ServerLevel level, BlockPos center, int chunkRadius) {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        ChunkPos at = new ChunkPos(center);
        List<Piece> pieces = new ArrayList<>();
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                ChunkAccess chunk = level.getChunkSource().getChunkNow(at.x + dx, at.z + dz);
                if (chunk == null) {
                    continue;
                }
                for (Map.Entry<Structure, StructureStart> start : chunk.getAllStarts().entrySet()) {
                    if (!start.getValue().isValid()) {
                        continue;
                    }
                    Holder<Structure> kind = registry.wrapAsHolder(start.getKey());
                    start.getValue().getPieces().forEach(piece -> pieces.add(new Piece(piece.getBoundingBox(), kind)));
                }
            }
        }
        return new Structures(pieces);
    }

    /** 盖住这一格的结构件;不在任何结构里为 null。 */
    public Piece pieceAt(BlockPos pos) {
        for (Piece piece : pieces) {
            if (piece.box().isInside(pos)) {
                return piece;
            }
        }
        return null;
    }
}
