package com.dwinovo.numen.core.task.base;

import java.util.function.IntSupplier;

/**
 * A tiny "gather N more" counter — the delta-above-baseline progress model shared
 * by {@code mine} (items gathered above the count held at start) and
 * {@code melee_attack} (mobs killed). It snapshots a baseline reading at construction and
 * measures everything relative to it, so the tally is "how many MORE since we
 * began", never the absolute inventory/kill count.
 *
 * <p>Pure and Minecraft-free: the live reading is supplied as an {@link IntSupplier}
 * (e.g. {@code () -> inventoryMatchCount()}), which keeps the arithmetic
 * unit-testable and lets the source stay whatever the task already computes.
 */
public final class CountedProgress {

    private final int target;
    private final IntSupplier current;
    private final int baseline;

    /**
     * @param target  how many additional units count as done.
     * @param current live reading of the underlying total; sampled now for the
     *                baseline and again on every query.
     */
    public CountedProgress(int target, IntSupplier current) {
        this.target = target;
        this.current = current;
        this.baseline = current.getAsInt();
    }

    /** Units gained since construction (never negative, e.g. if the total shrank). */
    public int gained() {
        return Math.max(0, current.getAsInt() - baseline);
    }

    /** The requested number of additional units. */
    public int target() {
        return target;
    }

    /** Units still needed to reach {@link #target()} (never negative). */
    public int remaining() {
        return Math.max(0, target - gained());
    }

    /** Have we gained at least the target? */
    public boolean done() {
        return gained() >= target;
    }
}
