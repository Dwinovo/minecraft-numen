package com.dwinovo.numen.core.pathing.cache;

import com.dwinovo.numen.entity.NumenPlayer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
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
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-level snapshots of the loaded chunks near companions, for the off-thread planner.
 * Once per tick (from both loaders' end-of-tick hook) we rebuild each
 * companion-level's {@link LoadedChunks} from the chunks currently loaded around its companions — a
 * cheap gather of live {@link LevelChunk} references via the non-blocking {@code getChunkNow}. The
 * planner then reads those refs LIVE; because the snapshot is
 * rebuilt fresh each tick and never mutated after, there is no cache to invalidate, no block-change
 * tracking, and no staleness beyond a single tick of drift — the server's own chunk
 * loading/unloading bounds it for free. A search starting away from where the companions stood at the
 * last rebuild gets the snapshot rebuilt around it on the spot ({@link #ensureSnapshot}).
 */
public final class PathCaches {

    private PathCaches() {}

    static {
        // 这两份都描述一个具体的世界，世界没了就得跟着没。报到写在这里而不是
        // 各 loader 的启动代码里：清理跟状态同居，就不会再出现「清单上漏了一项」。见 ServerLifecycle。
        com.dwinovo.numen.platform.ServerLifecycle.onStopped(PathCaches::dropAll);
    }

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
     * The snapshot for {@code level}, gathered around {@code around}'s chunk. Called on the main thread
     * right before a search so the planner always gets a thread-safe view. The per-tick rebuild
     * ({@link #serverTick}) only knows where the companions stood at the end of the last tick, so a
     * search from anywhere else — a companion summoned or teleported this tick, or a level's very
     * first search — rebuilds it on the spot with {@code around}'s chunk added to the centres; otherwise
     * the chunks around her would read as unloaded AIR and the search could only return a stub.
     */
    public static LoadedChunks ensureSnapshot(ServerLevel level, BlockPos around) {
        LoadedChunks existing = SNAPSHOTS.get(level.dimension());
        long center = chunkOf(around);
        if (existing != null && existing.centeredOn(center)) {
            return existing;
        }
        LongSet centers = existing == null ? new LongOpenHashSet() : new LongOpenHashSet(existing.centers());
        centers.add(center);
        LoadedChunks built = snapshot(level, centers);
        SNAPSHOTS.put(level.dimension(), built);
        return built;
    }

    /** The chunk {@code pos} stands in, as a {@link ChunkPos#asLong} key. */
    private static long chunkOf(BlockPos pos) {
        return ChunkPos.asLong(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
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
        // Tick-interval pulse for the profiler: this hook runs EVERY server tick on every loader,
        // so gap measurements never mistake a task-idle stretch for a slow tick.
        com.dwinovo.numen.core.pathing.util.NavProfiler.serverTickPulse();
        Map<ServerLevel, LongSet> byLevel = new HashMap<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer && p.level() instanceof ServerLevel sl) {
                byLevel.computeIfAbsent(sl, k -> new LongOpenHashSet()).add(chunkOf(p.blockPosition()));
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

        for (Map.Entry<ServerLevel, LongSet> e : byLevel.entrySet()) {
            SNAPSHOTS.put(e.getKey().dimension(), snapshot(e.getKey(), e.getValue()));
        }
    }

    /** Gather references to the chunks loaded within {@link #RADIUS_CHUNKS} of any of the {@code centers}
     *  chunks (non-blocking — unloaded chunks are simply absent → the reader sees AIR). */
    private static LoadedChunks snapshot(ServerLevel level, LongSet centers) {
        Long2ObjectOpenHashMap<LevelChunk> map = new Long2ObjectOpenHashMap<>();
        for (long c : centers) {
            int ccx = ChunkPos.getX(c);
            int ccz = ChunkPos.getZ(c);
            for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
                for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                    int cx = ccx + dx;
                    int cz = ccz + dz;
                    long key = ChunkPos.asLong(cx, cz);
                    if (!map.containsKey(key)) {
                        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                        if (chunk != null) {
                            map.put(key, chunk);
                        }
                    }
                }
            }
        }
        return new LoadedChunks(map, centers);
    }
}
