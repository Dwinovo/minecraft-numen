package com.dwinovo.numen.core.pathing.engine;

/**
 * The engine's world model: everything the search knows about the domain comes
 * through this one seam. The MC adapter wraps {@code Moves.generate} behind it;
 * unit tests inject synthetic worlds ({@code GridWorld}) — which is the whole
 * point of the engine being Minecraft-free.
 *
 * @param <E> the opaque edge payload carried through to the result (the MC side
 *            uses {@code Movement}; tests use whatever they like). The engine
 *            never inspects it.
 */
@FunctionalInterface
public interface SuccessorFunction<E> {

    /**
     * Emit every feasible edge out of {@code pos} into {@code sink}.
     *
     * <p>Contract: emit only finite, positive costs — the engine drops (and
     * counts, see {@code SearchStats.rejectedEdges}) non-positive, NaN or
     * infinite costs as a hygiene backstop, but the domain's own "impossible"
     * filtering (e.g. {@code COST_INF} vetoes) belongs on the emitting side.
     * Called on the search's worker thread.
     */
    void expand(long pos, EdgeSink<E> sink);
}
