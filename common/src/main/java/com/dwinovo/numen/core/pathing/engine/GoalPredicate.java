package com.dwinovo.numen.core.pathing.engine;

/**
 * "May a path end here?" — the goal membership test, evaluated when a node is
 * expanded. Node-domain only: it sees a packed position, never the live world.
 * Called on the search's worker thread.
 */
@FunctionalInterface
public interface GoalPredicate {

    boolean isGoal(long pos);
}
