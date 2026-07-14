package com.dwinovo.numen.core.pathing.engine;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;

/**
 * RTAA*-style learned-heuristic store: per-position lower bounds on remaining
 * cost, discovered by earlier search segments and consulted by later ones so a
 * heuristic depression (a cave system, a sealed pocket, an expensive dig) is
 * flooded ONCE, not once per segment.
 *
 * <h2>Semantics</h2>
 * Values are consulted as {@code h_eff = max(h_base, learned(pos))} — learning
 * only ever makes a position look WORSE (more expensive), never better, so the
 * residual failure mode is excess pessimism (caught by the caller's stall
 * detector), never a false "reachable". {@link #update} is a max-merge for the
 * same reason.
 *
 * <h2>Lifecycle (owned by the caller — PlayerNav)</h2>
 * One table per navigation intent (per PlayerNav), shared across its segments.
 * Cleared when the goal moves/re-roots (learned values are goal-relative) and
 * when dig capability IMPROVES (old values may now over-estimate); kept when it
 * worsens (still valid lower bounds).
 *
 * <h2>Threading</h2>
 * Reads/writes happen on planner-pool workers; {@link #clear()} on the tick
 * thread. A cancelled search may still be running when its successor starts, so
 * every access is {@code synchronized} — consults are one hash get per node
 * CREATION (≈25ns uncontended), and the only contention window is a stale
 * cancelled worker overlapping a live one for a few ms. Cancelled searches
 * never write (enforced in {@code PathSearch}), which keeps stale results out.
 */
public final class HLearningTable {

    /** Hard cap: beyond this the table clears itself (degrade to no-learning, never worse). */
    private static final int MAX_ENTRIES = 1_000_000;

    private final Long2DoubleOpenHashMap learned = new Long2DoubleOpenHashMap();
    /** How many times the size cap forced a full clear (telemetry). */
    private int capClears;

    public HLearningTable() {
        learned.defaultReturnValue(0.0);
    }

    /** The learned lower bound for {@code pos}, or {@code 0.0} if none. */
    public synchronized double learned(long pos) {
        return learned.get(pos);
    }

    /** Max-merge {@code h} into the table: raises the stored value, never lowers it. */
    public synchronized void update(long pos, double h) {
        if (learned.size() >= MAX_ENTRIES) {
            learned.clear();
            capClears++;
        }
        double current = learned.get(pos);
        if (h > current) {
            learned.put(pos, h);
        }
    }

    /** Drop everything (goal moved / re-rooted / dig capability improved). Tick thread. */
    public synchronized void clear() {
        learned.clear();
    }

    public synchronized int size() {
        return learned.size();
    }

    /** Times the size cap forced a self-clear (should be ~always 0; telemetry). */
    public synchronized int capClears() {
        return capClears;
    }
}
