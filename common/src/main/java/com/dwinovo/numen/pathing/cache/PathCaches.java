package com.dwinovo.numen.pathing.cache;

import com.dwinovo.numen.entity.NumenPlayer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-level snapshots of the loaded chunks near companions, for the off-thread planner.
 * Once per tick (from both loaders' end-of-tick hook) we rebuild each
 * companion-level's {@link LoadedChunks} from the chunks currently loaded around its companions — a
 * cheap gather of live {@link LevelChunk} references via the non-blocking {@code getChunkNow}. The
 * planner then reads those refs LIVE (Baritone's {@code useTheRealWorld}); because the snapshot is
 * rebuilt fresh each tick and never mutated after, there is no cache to invalidate, no block-change
 * tracking, and no staleness beyond what Baritone itself tolerates — the server's own chunk
 * loading/unloading bounds it for free.
 */
public final class PathCaches {

    private PathCaches() {}

    /** How far around each companion to capture loaded chunks (radius in chunks). */
    private static final int RADIUS_CHUNKS = 8;
    /** Keep a companion-less level's snapshot this long before freeing it, so a brief roster gap
     *  doesn't churn. */
    private static final int IDLE_GRACE_TICKS = 600;

    private static final ConcurrentHashMap<ResourceKey<Level>, LoadedChunks> SNAPSHOTS = new ConcurrentHashMap<>();
    /** Consecutive ticks each level has had no companion (main-thread only). */
    private static final Map<ResourceKey<Level>, Integer> idleTicks = new HashMap<>();

    /** The current snapshot for {@code level}, or null if no companion is operating there. */
    public static LoadedChunks peek(Level level) {
        return SNAPSHOTS.get(level.dimension());
    }

    /**
     * The snapshot for {@code level}, building one around {@code around} on the spot if none exists yet.
     * Called on the main thread right before a search so the planner always gets a thread-safe view —
     * without this, a level's very first search (dispatched before {@link #serverTick} has run) would
     * fall back to a LIVE read-through, which is unsafe once the search runs off-thread.
     */
    public static LoadedChunks ensureSnapshot(ServerLevel level, BlockPos around) {
        LoadedChunks existing = SNAPSHOTS.get(level.dimension());
        if (existing != null) {
            return existing;
        }
        LoadedChunks built = snapshot(level, List.of(around));
        SNAPSHOTS.put(level.dimension(), built);
        return built;
    }

    public static void dropAll() {
        SNAPSHOTS.clear();
        idleTicks.clear();
    }

    /**
     * Rebuild every companion-level's loaded-chunk snapshot for this tick, and free levels that no
     * longer host a companion (after a grace period). Cheap when no companions exist. Dimension travel
     * is automatic — the old level falls out of the active set and ages out, the new one is rebuilt.
     */
    public static void serverTick(MinecraftServer server) {
        Map<ServerLevel, List<BlockPos>> byLevel = new HashMap<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer && p.level() instanceof ServerLevel sl) {
                byLevel.computeIfAbsent(sl, k -> new ArrayList<>()).add(p.blockPosition());
            }
        }

        Set<ResourceKey<Level>> active = new HashSet<>();
        for (ServerLevel sl : byLevel.keySet()) {
            active.add(sl.dimension());
        }
        for (ResourceKey<Level> key : new ArrayList<>(SNAPSHOTS.keySet())) {
            if (active.contains(key)) {
                idleTicks.remove(key);
            } else if (idleTicks.merge(key, 1, Integer::sum) > IDLE_GRACE_TICKS) {
                SNAPSHOTS.remove(key);
                idleTicks.remove(key);
            }
        }

        for (Map.Entry<ServerLevel, List<BlockPos>> e : byLevel.entrySet()) {
            SNAPSHOTS.put(e.getKey().dimension(), snapshot(e.getKey(), e.getValue()));
        }
    }

    /** Gather references to the chunks loaded within {@link #RADIUS_CHUNKS} of any companion in this
     *  level (non-blocking — unloaded chunks are simply absent → the reader sees AIR). */
    private static LoadedChunks snapshot(ServerLevel level, List<BlockPos> feet) {
        Long2ObjectOpenHashMap<LevelChunk> map = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet blockEntities = new LongOpenHashSet();
        for (BlockPos f : feet) {
            int ccx = SectionPos.blockToSectionCoord(f.getX());
            int ccz = SectionPos.blockToSectionCoord(f.getZ());
            for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
                for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                    int cx = ccx + dx;
                    int cz = ccz + dz;
                    long key = ChunkPos.asLong(cx, cz);
                    if (!map.containsKey(key)) {
                        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                        if (chunk != null) {
                            map.put(key, chunk);
                            for (BlockPos bePos : chunk.getBlockEntities().keySet()) {
                                blockEntities.add(bePos.asLong());
                            }
                        }
                    }
                }
            }
        }
        return new LoadedChunks(map, blockEntities);
    }
}
