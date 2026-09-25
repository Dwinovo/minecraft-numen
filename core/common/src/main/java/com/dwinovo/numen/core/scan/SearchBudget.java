package com.dwinovo.numen.core.scan;

import net.minecraft.server.MinecraftServer;

/**
 * GLOBAL per-tick budget for every sliced world search on the server —
 * structure locating ({@link LocateStructureCompanionTask}), biome locating
 * ({@link LocateBiomeCompanionTask}) and long-range block scans
 * ({@link BlockSearch}). The Explorer's Compass {@code WorldWorkerManager}
 * model: total search cost per tick is a server constant, independent of how
 * many companions are searching at once — per-task budgets would stack
 * linearly with pet count.
 *
 * <h2>Fairness</h2>
 * First-come-first-served within a tick (entities tick in a stable order), so
 * concurrent searches effectively serialize: the first finishes in a few
 * ticks, then the next drains the pool. For companion-scale concurrency
 * that's strictly better than splitting the pool — total latency is the same
 * and the implementation stays trivial. Revisit with round-robin only if
 * dozens of simultaneous searches ever become real.
 *
 * <h2>The wall-clock lid counts search time only</h2>
 * Every consumer does its work inside a {@link #slice}; the 4ms lid is the sum of
 * this tick's slices, not the time since the first search of the tick. The world
 * ticks between the searches (locators run in the entity tick, block searches at
 * the end of it); counting that time too would hand whichever search runs later a
 * lid the world already spent, and a slow world tick would stall it for as long as
 * an earlier search kept running. The lid only paces: it decides where this tick's
 * work stops, never what a search concludes — every search ends on its own work
 * bound.
 *
 * <h2>Threading</h2>
 * Server main thread only, like everything in the task layer. The tick stamp
 * uses {@link MinecraftServer#getTickCount()} (monotonic, unaffected by
 * {@code /tick freeze}) to reset the pool exactly once per server tick.
 */
public final class SearchBudget {

    /**
     * Cached presence checks are cheap; this caps loop work across ALL searches.
     *
     * <p>查询一律只读已加载的东西,没有"为了查而加载"这档额度——限流限不住它:名额是<b>发起前</b>
     * 检查的,一旦进了同步加载就再也收不回来,而一次冷区块的世界生成足以让单 tick 超过看门狗的
     * 六十秒。所以那条路是删掉的,不是限住的。
     */
    private static final int MAX_CHECKS_PER_TICK = 128;
    /**
     * Biome locator samples (pure climate-noise lookups, no chunk access; one
     * "sample" = one x/z column across all its Y probes). Cheaper than a
     * structure check, hence the larger pool — still under the shared 4ms lid.
     */
    private static final int MAX_BIOME_SAMPLES_PER_TICK = 256;
    /**
     * Block-search section reads (one permit = one 16³ chunk section actually read:
     * building its index entry — a count pass plus a collect pass — or walking a
     * section where a target is too abundant to index). Sections the palette rules
     * out and sections with a fresh index entry cost no permit, only search time.
     *
     * <p>实测一次构建约 50µs:单刻 64 次时峰值 ~3.1ms,48 次压进 ~2.5ms——取 48,冷区域在几刻内
     * 渐进变热,读地形本身不把一刻吃到 4ms 的上限。
     */
    private static final int MAX_SECTION_READS_PER_TICK = 48;
    /**
     * Wall-clock lid on the search slices of one tick. The count caps bound the
     * common case; this makes the "never stalls the server" promise unconditional
     * even when every check goes cold to disk. 4ms ≈ 8% of a 50ms tick.
     */
    private static final long MAX_NANOS_PER_TICK = 4_000_000L;
    /** {@link #sliceStart} when no slice is open. */
    private static final long CLOSED = Long.MIN_VALUE;

    private static int stampTick = Integer.MIN_VALUE;
    private static int checksLeft;
    private static int biomeSamplesLeft;
    private static int sectionReadsLeft;
    /** Nanos the closed slices of this tick have spent. */
    private static long spentNanos;
    /** When the open slice began; {@link #CLOSED} when none is open. */
    private static long sliceStart = CLOSED;

    private static final Slice SLICE = new Slice();

    private SearchBudget() {}

    /**
     * Open this consumer's slice of the tick's pool (resetting the pool when the
     * server tick has advanced); close it when the consumer stops searching this
     * tick. Every permit and {@link #withinTime} check happens inside one.
     */
    public static Slice slice(MinecraftServer server) {
        return slice(server.getTickCount());
    }

    /** {@link #slice(MinecraftServer)} by tick number; also the test seam. */
    static Slice slice(int now) {
        if (now != stampTick) {
            resetForTick(now);
        }
        if (sliceStart != CLOSED) {
            throw new IllegalStateException("a search slice is already open");
        }
        sliceStart = System.nanoTime();
        return SLICE;
    }

    /** The actual pool reset. */
    private static void resetForTick(int tick) {
        stampTick = tick;
        checksLeft = MAX_CHECKS_PER_TICK;
        biomeSamplesLeft = MAX_BIOME_SAMPLES_PER_TICK;
        sectionReadsLeft = MAX_SECTION_READS_PER_TICK;
        spentNanos = 0;
    }

    /** Take one section-read permit (one 16³ section read); false = resume next tick. */
    public static boolean trySectionRead() {
        if (sectionReadsLeft <= 0 || !withinTime()) return false;
        sectionReadsLeft--;
        return true;
    }

    /**
     * Search time left in this tick's pool — the lid over work that needs no permit
     * (visiting sections the index or palette already answers); false = resume next tick.
     */
    public static boolean withinTime() {
        if (sliceStart == CLOSED) {
            throw new IllegalStateException("search work outside a slice");
        }
        return spentNanos + (System.nanoTime() - sliceStart) < MAX_NANOS_PER_TICK;
    }

    /** Take one biome-sample permit; false = pool drained, resume next tick. */
    public static boolean tryBiomeSample() {
        if (biomeSamplesLeft <= 0 || !withinTime()) return false;
        biomeSamplesLeft--;
        return true;
    }

    /** Take one candidate-check permit; false = pool drained, resume next tick. */
    public static boolean tryCheck() {
        if (checksLeft <= 0 || !withinTime()) return false;
        checksLeft--;
        return true;
    }

    /** One consumer's share of a tick, charged to the pool when closed. */
    public static final class Slice implements AutoCloseable {

        private Slice() {}

        @Override
        public void close() {
            spentNanos += System.nanoTime() - sliceStart;
            sliceStart = CLOSED;
        }
    }
}
