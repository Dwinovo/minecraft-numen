package com.dwinovo.numen.core.pathing.cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * An immutable snapshot of the loaded chunks near a level's companions — a thread-safe copy of the
 * chunk provider's "what is loaded right now". Built on
 * the main thread once per tick ({@link PathCaches#serverTick}) and read by the planner (off-thread
 * from P-C). It holds live {@link LevelChunk} references, so a lookup reads the LIVE section
 * palette — exact for loaded terrain. We tolerate the rare race of reading
 * a palette the main thread is concurrently resizing (the reader catches it and yields AIR; the
 * executor re-costs live and replans) — a deliberate exactness-for-cheapness trade.
 *
 * <p>Never mutated after construction, so a worker reading the map structure can't race a writer — only
 * the shared chunk CONTENTS are live. A fresh snapshot is published (via {@link PathCaches}'s
 * {@link java.util.concurrent.ConcurrentHashMap}) each tick; an in-flight search keeps the snapshot it
 * started with.
 */
public final class LoadedChunks {

    private final Long2ObjectMap<LevelChunk> chunks;
    /** The chunks this snapshot was gathered around (each companion's, as chunk keys). */
    private final LongSet centers;

    LoadedChunks(Long2ObjectMap<LevelChunk> chunks, LongSet centers) {
        this.chunks = chunks;
        this.centers = centers;
    }

    /** Was this snapshot gathered around the chunk {@code chunkKey} ({@link ChunkPos#asLong})? */
    boolean centeredOn(long chunkKey) {
        return centers.contains(chunkKey);
    }

    LongSet centers() {
        return centers;
    }

    /** The loaded chunk at the given chunk coordinates, or {@code null} if it wasn't loaded when this
     *  snapshot was taken (→ the reader treats it as unknown / AIR). */
    public LevelChunk at(int chunkX, int chunkZ) {
        return chunks.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    /** Number of chunks captured — for debug / memory accounting. */
    public int size() {
        return chunks.size();
    }
}
