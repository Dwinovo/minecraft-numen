package com.dwinovo.numen.core.pathing.engine;

/**
 * Estimate of the remaining cost from a position to the goal, in the same
 * units as edge costs. May be inadmissible (the MC adapter's base heuristic is
 * inflation-weighted for weighted A*) and may be negative (run-away goals reward distance).
 * Called on the search's worker thread, once per node creation.
 */
@FunctionalInterface
public interface Heuristic {

    double estimate(long pos);
}
