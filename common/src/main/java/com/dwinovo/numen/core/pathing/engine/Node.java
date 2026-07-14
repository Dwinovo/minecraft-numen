package com.dwinovo.numen.core.pathing.engine;

/**
 * One search node, keyed by a packed position. Mirrors the legacy
 * {@code calc.PathNode} shape (cached heap index for O(log n) decrease-key)
 * with the v2 change that the effective heuristic
 * {@code h_eff = max(h_base, learned)} is fixed at CREATION — the learning
 * table is only written between searches from this search's perspective, so
 * heap order is never invalidated mid-run.
 *
 * @param <E> the opaque edge payload (see {@link SuccessorFunction}).
 */
final class Node<E> {

    /** Packed position this node represents. */
    final long pos;
    /** Effective heuristic: {@code max(heuristic.estimate(pos), learning.learned(pos))}, frozen at creation. */
    final double hEff;

    /** Cost from start (g). Infinite until first relaxed. */
    double g = Double.POSITIVE_INFINITY;
    /** Heap key: {@code g + hEff}. Kept in sync with {@link #g} on every relax. */
    double f;

    /** Parent on the cheapest known route, for path reconstruction. */
    Node<E> parent;
    /** Edge payload that led {@link #parent} -> this. */
    E via;

    /** Index in the open-set heap array, or {@code -1} when not in the open set. */
    int heapIndex = -1;
    /** True once expanded (popped from the open set). */
    boolean closed;

    Node(long pos, double hEff) {
        this.pos = pos;
        this.hEff = hEff;
    }

    boolean isOpen() {
        return heapIndex != -1;
    }
}
