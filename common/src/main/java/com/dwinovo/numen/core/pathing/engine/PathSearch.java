package com.dwinovo.numen.core.pathing.engine;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The planning core: DETERMINISTIC weighted A* over an injected domain
 * ({@link SuccessorFunction} / {@link Heuristic} / {@link GoalPredicate}) with
 * a two-phase {@link SearchBudget}, previous-route favoring, and a single
 * principled partial-commitment rule. Same world + start + goal → same result;
 * the only memory a search consults is the world itself.
 *
 * <p>History: v2 carried an RTAA*-style learned-h table shared across segments.
 * It was retired after the production-budget audit ({@code
 * ProductionBudgetAuditTest}): the failure budget covers depression-class
 * terrain two orders of magnitude over, so one search resolves what the table
 * existed to remember across segments — while its only-ever-rising entries
 * went stale against the body's own terrain edits and steered re-searches off
 * freshly built scaffolds (the pillar forest). Depressions beyond the budget
 * (≳200k reachable cells) end in a typed BOXED_IN report to the task layer by
 * design — escalate, don't crawl.
 *
 * <h2>Commitment rule</h2>
 * A node becomes THE candidate iff (1) it lies ≥ {@code config.minCommitDist}
 * from the start, (2) its h beats the start's h by more than
 * {@code minImprovement} (genuinely nearer the goal than where we stand), and
 * (3) it beats the current candidate by h (ties: lower g). On budget
 * exhaustion: candidate present → {@link SearchResult.Kind#PARTIAL_COMMIT},
 * else {@link SearchResult.Kind#NO_PATH}. Deliberately h-based rather than
 * argmin-f: with a base heuristic that under-prices dig-through 15–100×,
 * f-commitment re-selects cheap lateral flooding in exactly the sealed-shaft
 * scenario the budget must instead flood through.
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
    private final LongSet favored;
    private final Config config;

    private volatile boolean cancelled;

    public PathSearch(long start,
                      SuccessorFunction<E> successors,
                      Heuristic heuristic,
                      GoalPredicate goal,
                      SearchBudget budget,
                      LongSet favored,
                      Config config) {
        this.start = start;
        this.successors = successors;
        this.heuristic = heuristic;
        this.goal = goal;
        this.budget = budget;
        this.favored = favored;
        this.config = config;
    }

    /** Cooperative cancel from any thread; {@link #run()} returns CANCELLED soon after. */
    public void cancel() {
        cancelled = true;
    }

    /** Run to termination (budget-bounded). Call once, on a worker thread. */
    public SearchResult<E> run() {
        return new Loop().execute();
    }

    /**
     * The single-use expansion loop. A non-static inner class so the successor
     * sink (this) can carry the mutable per-run state without boxing tricks;
     * {@link #run()} is called once, so one instance per search.
     */
    private final class Loop implements EdgeSink<E> {

        /** Dedup: every node ever created, keyed by packed position. */
        private final Long2ObjectOpenHashMap<Node<E>> nodes = new Long2ObjectOpenHashMap<>();
        private final OpenSet<E> open = new OpenSet<>();
        private final double minCommitDistSq = config.minCommitDist() * config.minCommitDist();

        private int expansions;
        private int rejectedEdges;
        private double bestProgressSq;

        /** h of the start node — the commitment rule's improvement baseline. */
        private double startHEff;
        /** Deadline flags, refreshed every 256 expansions (timed budgets only). */
        private boolean primaryTimeUp;
        private boolean failureTimeUp;
        /** THE partial-commitment candidate (see class javadoc), or null. */
        private Node<E> candidate;
        /** Node currently being expanded — parent for edges arriving at the sink. */
        private Node<E> current;

        SearchResult<E> execute() {
            if (isCancelled()) {
                return SearchResult.cancelled(start, SearchStats.empty());
            }
            if (goal.isGoal(start)) {
                return SearchResult.complete(start, start, List.of(), stats(false));
            }

            Node<E> startNode = createNode(start);
            startNode.g = 0.0;
            startNode.f = startNode.hEff;
            startHEff = startNode.hEff;
            open.insert(startNode);

            int primary = budget.primaryExpansions();
            int failure = budget.failureExpansions();
            long t0 = budget.timedFlavour() ? System.nanoTime() : 0L;

            // Two-phase budget: past `primary` (expansions OR deadline), stop as
            // soon as a committable candidate exists; otherwise keep expanding to
            // `failure`. Deadlines are polled every 256 expansions — cheap enough
            // for the hot loop, fine-grained enough for millisecond budgets.
            while (!open.isEmpty() && !isCancelled()
                    && expansions < failure && !failureTimeUp
                    && !((expansions >= primary || primaryTimeUp) && candidate != null)) {
                Node<E> node = open.removeLowest();
                node.closed = true;
                expansions++;
                if (t0 != 0L && (expansions & 255) == 0) {
                    long elapsed = System.nanoTime() - t0;
                    primaryTimeUp = elapsed >= budget.primaryNanos();
                    failureTimeUp = elapsed >= budget.failureNanos();
                }

                double progressSq = PackedPos.distSq(start, node.pos);
                if (progressSq > bestProgressSq) {
                    bestProgressSq = progressSq;
                }

                if (goal.isGoal(node.pos)) {
                    return SearchResult.complete(start, node.pos, reconstruct(node), stats(false));
                }

                current = node;
                successors.expand(node.pos, this);
            }

            if (isCancelled()) {
                return SearchResult.cancelled(start, stats(false));
            }

            boolean stoppedAtPrimary = candidate != null
                    && (expansions >= primary || primaryTimeUp)
                    && expansions < failure && !failureTimeUp
                    && !open.isEmpty();

            if (candidate != null) {
                return SearchResult.partialCommit(start, candidate.pos,
                        reconstruct(candidate), stats(stoppedAtPrimary));
            }
            return SearchResult.noPath(start, stats(stoppedAtPrimary));
        }

        /** Successor sink: hygiene filter, favoring discount, relaxation, candidate tracking. */
        @Override
        public void accept(long dest, double cost, E edge) {
            // Hygiene backstop: a 0 / negative / NaN / infinite edge cost would
            // silently corrupt the search — drop and count it rather than trust it.
            if (!(cost > 0.0) || Double.isInfinite(cost)) {
                rejectedEdges++;
                return;
            }
            // Favoring: discount an edge whose destination sits on the previous
            // path, so a replan reuses the old route (damps oscillation).
            double edgeCost = favored.contains(dest) ? cost * config.favoringCoefficient() : cost;
            double tentativeG = current.g + edgeCost;

            Node<E> node = nodes.get(dest);
            if (node == null) {
                node = createNode(dest);
            }
            // Re-propagation margin: ignore sub-minImprovement improvements (FP
            // noise) to avoid pointless decrease-key churn. Also rejects every
            // non-improving relaxation, including into closed nodes.
            if (node.g - tentativeG <= config.minImprovement()) {
                return;
            }

            node.g = tentativeG;
            node.f = tentativeG + node.hEff;
            node.parent = current;
            node.via = edge;

            if (node.isOpen()) {
                open.update(node);
            } else {
                node.closed = false; // re-open: a genuinely cheaper route to a closed node
                open.insert(node);
            }
            considerCandidate(node);
        }

        /** Create (and register) a node; h is fixed at creation. */
        private Node<E> createNode(long pos) {
            Node<E> node = new Node<>(pos, heuristic.estimate(pos));
            nodes.put(pos, node);
            return node;
        }

        /**
         * Commitment rule (class javadoc): candidate iff (1) ≥ minCommitDist from
         * the start, (2) h_eff beats the START's h_eff by more than minImprovement,
         * (3) beats the current candidate by h_eff beyond minImprovement, or —
         * within minImprovement — by lower g. Tracked incrementally at relaxation.
         */
        private void considerCandidate(Node<E> node) {
            if (node.hEff >= startHEff - config.minImprovement()) {
                return;
            }
            if (PackedPos.distSq(start, node.pos) < minCommitDistSq) {
                return;
            }
            if (candidate == null || candidate == node) {
                candidate = node;
                return;
            }
            double dh = candidate.hEff - node.hEff;
            if (dh > config.minImprovement()
                    || (Math.abs(dh) <= config.minImprovement() && node.g < candidate.g)) {
                candidate = node;
            }
        }

        /** Walk the parent chain collecting {@code via} edges, then reverse to start → end order. */
        private List<E> reconstruct(Node<E> end) {
            List<E> edges = new ArrayList<>();
            for (Node<E> node = end; node.via != null; node = node.parent) {
                edges.add(node.via);
            }
            Collections.reverse(edges);
            return edges;
        }

        private SearchStats stats(boolean stoppedAtPrimary) {
            return new SearchStats(expansions, open.isEmpty(), bestProgressSq,
                    stoppedAtPrimary, rejectedEdges);
        }
    }

    // ---- package-private accessors for the core implementation (Lane A) ----

    long startPos()                  { return start; }
    SuccessorFunction<E> successors(){ return successors; }
    Heuristic heuristicFn()          { return heuristic; }
    GoalPredicate goalFn()           { return goal; }
    SearchBudget budgetSpec()        { return budget; }
    LongSet favoredSet()             { return favored; }
    Config configSpec()              { return config; }
    boolean isCancelled()            { return cancelled; }
}
