package com.dwinovo.numen.core.pathing.engine;

/**
 * Post-mortem telemetry for one search — feeds the caller's "no path" autopsy
 * (the model-facing failure reason) and the tuning watchlist.
 *
 * @param expansions         nodes actually expanded
 * @param frontierExhausted  the open set EMPTIED — every reachable cell explored
 *                           (a sealed region or the snapshot view boundary) —
 *                           vs. merely hitting the budget
 * @param bestProgressSq     max squared distance from the start over all
 *                           expanded nodes — how far the search truly got
 * @param stoppedAtPrimary   a committable candidate existed at the primary
 *                           budget (mid-journey fast path)
 * @param rejectedEdges      successor edges dropped for hygiene (non-positive /
 *                           NaN / infinite cost)
 */
public record SearchStats(
        int expansions,
        boolean frontierExhausted,
        double bestProgressSq,
        boolean stoppedAtPrimary,
        int rejectedEdges) {

    public static SearchStats empty() {
        return new SearchStats(0, false, 0.0, false, 0);
    }
}
