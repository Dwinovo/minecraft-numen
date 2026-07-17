package com.dwinovo.numen.core.pathing.hier;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * A short-TTL cache of section summaries, so consecutive long-range searches
 * don't re-scan the same terrain every dispatch. TTL, not invalidation: the
 * snapshot substrate rebuilds per tick with no change tracking to hook, so
 * summaries simply expire ({@code ttlTicks}) — our own digs and third-party
 * edits are at most that stale, and the coarse layer is guidance, never
 * correctness (the fine search and execution re-costing read the real world).
 *
 * <p>Tick-thread only (built and read at search dispatch).
 */
public final class SummaryCache {

    private record Timed(SectionSummary summary, long tick) {}

    private final Long2ObjectOpenHashMap<Timed> map = new Long2ObjectOpenHashMap<>();
    private final int ttlTicks;
    private long nowTick;

    public SummaryCache(int ttlTicks) {
        this.ttlTicks = ttlTicks;
    }

    /** Advance the clock (call once per dispatch with the level's game time). */
    public void tick(long gameTime) {
        this.nowTick = gameTime;
        // Opportunistic pruning keeps the map from growing across a long session.
        if (map.size() > 8192) {
            map.values().removeIf(t -> nowTick - t.tick() > ttlTicks);
        }
    }

    SectionSummary get(long key) {
        Timed t = map.get(key);
        if (t == null || nowTick - t.tick() > ttlTicks) {
            return null;
        }
        return t.summary();
    }

    void put(long key, SectionSummary summary) {
        map.put(key, new Timed(summary, nowTick));
    }
}
