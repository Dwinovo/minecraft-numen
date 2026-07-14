package com.dwinovo.numen.core.pathing.engine;

import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * The v2 planning core: weighted A* over an injected domain
 * ({@link SuccessorFunction} / {@link Heuristic} / {@link GoalPredicate}) with
 * a two-phase {@link SearchBudget}, RTAA*-style {@link HLearningTable}
 * consultation and write-back, previous-route favoring, and a single principled
 * partial-commitment rule — replacing the legacy 7-coefficient bestSoFar
 * ledgers + h-only patch.
 *
 * <h2>Effective heuristic</h2>
 * {@code h_eff(pos) = max(heuristic.estimate(pos), learning.learned(pos))},
 * fixed at node CREATION (the table is only written between searches from this
 * search's perspective, so heap order is never invalidated mid-run).
 *
 * <h2>Commitment rule</h2>
 * A node becomes THE candidate iff (1) it lies ≥ {@code config.minCommitDist}
 * from the start, (2) its h_eff beats the start's h_eff by more than
 * {@code minImprovement} (genuinely nearer the goal than where we stand), and
 * (3) it beats the current candidate by h_eff (ties: lower g). On budget
 * exhaustion: candidate present → {@link SearchResult.Kind#PARTIAL_COMMIT},
 * else {@link SearchResult.Kind#NO_PATH}. Deliberately h-based rather than
 * LSS-LRTA*'s argmin-f: with a base heuristic that under-prices dig-through
 * 15–100×, f-commitment re-selects cheap lateral flooding in exactly the
 * sealed-shaft scenario this engine exists to fix; h-commitment escapes on the
 * first segment, and the learning table makes any bad commitment non-repeating.
 *
 * <h2>Learning write-back</h2>
 * On termination — only for PARTIAL_COMMIT / NO_PATH, only if the frontier is
 * NON-empty (an emptied frontier can mean the snapshot view boundary, not true
 * sealing), and never when cancelled (thread-safety half of the shared-table
 * contract): {@code fFrontier = min over open of (g + h_eff)}; for every
 * closed node s: {@code learning.update(s.pos, fFrontier − s.g)}.
 *
 * <h2>Threading</h2>
 * {@link #run()} is called once, on a planner-pool worker (or synchronously on
 * the tick thread for the rare non-frozen-context fallback). {@link #cancel()}
 * may be called from any thread; the expansion loop polls it cooperatively.
 *
 * @param <E> the opaque edge payload (MC side: {@code Movement}).
 */
public final class PathSearch<E> {

    /** All engine knobs, explicit — the engine imports no settings class. */
    public record Config(double favoringCoefficient,
                         double minCommitDist,
                         double minImprovement) {

        /** The production values (carried over from the verified v1 constants). */
        public static Config standard() {
            return new Config(0.5, 5.0, 0.01);
        }
    }

    private final long start;
    private final SuccessorFunction<E> successors;
    private final Heuristic heuristic;
    private final GoalPredicate goal;
    private final SearchBudget budget;
    private final HLearningTable learning;
    private final LongSet favored;
    private final Config config;

    private volatile boolean cancelled;

    public PathSearch(long start,
                      SuccessorFunction<E> successors,
                      Heuristic heuristic,
                      GoalPredicate goal,
                      SearchBudget budget,
                      HLearningTable learning,
                      LongSet favored,
                      Config config) {
        this.start = start;
        this.successors = successors;
        this.heuristic = heuristic;
        this.goal = goal;
        this.budget = budget;
        this.learning = learning;
        this.favored = favored;
        this.config = config;
    }

    /** Cooperative cancel from any thread; {@link #run()} returns CANCELLED soon after. */
    public void cancel() {
        cancelled = true;
    }

    /** Run to termination (budget-bounded). Call once, on a worker thread. */
    public SearchResult<E> run() {
        // STAGE-0 STUB — Lane A replaces this with the real expansion loop.
        // A NO_PATH-always engine is deliberately harmless: the caller reports
        // a clean autopsy and the stall detector ends the attempt.
        if (cancelled) {
            return SearchResult.cancelled(start, SearchStats.empty());
        }
        return SearchResult.noPath(start, SearchStats.empty());
    }

    // ---- package-private accessors for the core implementation (Lane A) ----

    long startPos()                  { return start; }
    SuccessorFunction<E> successors(){ return successors; }
    Heuristic heuristicFn()          { return heuristic; }
    GoalPredicate goalFn()           { return goal; }
    SearchBudget budgetSpec()        { return budget; }
    HLearningTable learningTable()   { return learning; }
    LongSet favoredSet()             { return favored; }
    Config configSpec()              { return config; }
    boolean isCancelled()            { return cancelled; }
}
