package com.dwinovo.numen.core.pathing.engine;

/**
 * Receiver for the edges a {@link SuccessorFunction} emits — a sink rather than
 * a returned collection so synthetic test worlds are zero-allocation and the
 * engine imposes no collection shape on the domain.
 *
 * @param <E> the opaque edge payload (see {@link SuccessorFunction}).
 */
@FunctionalInterface
public interface EdgeSink<E> {

    /**
     * One edge: {@code dest} is the packed destination position, {@code cost}
     * its traversal cost in domain units (ticks, for the MC adapter),
     * {@code edge} the opaque payload returned in the final path.
     */
    void accept(long dest, double cost, E edge);
}
