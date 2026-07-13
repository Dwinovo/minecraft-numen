package com.dwinovo.numen.core.pathing.calc;

import com.dwinovo.numen.core.pathing.movement.Movement;
import com.dwinovo.numen.core.pathing.movement.Moves;
import com.dwinovo.numen.core.pathing.util.ActionCosts;
import com.dwinovo.numen.core.pathing.util.PathSettings;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A resumable, <em>time-sliced</em> A* search over the {@link Moves} primitive
 * graph — the mineflayer-pathfinder model adapted to a single-threaded server,
 * with Baritone's open-set + heuristic machinery.
 *
 * <p>{@link #step(int)} expands at most {@code budget} nodes per call so a far
 * or complex target never hitches the server tick; the owning goal steps it
 * once per tick until {@link State#DONE}, then reads {@link #result()}. The
 * search stays on the tick thread and reads the live world directly.
 *
 * <h2>Open set &amp; heuristic</h2>
 * Nodes live in a {@link BinaryHeapOpenSet} (O(log n) decrease-key — no
 * duplicate-node churn) and are deduped by a {@link Long2ObjectOpenHashMap}
 * keyed by packed block position (no {@code BlockPos} boxing on the hot path).
 * Edge costs come from the movement primitives (ticks). The heuristic is octile
 * horizontal distance (× walk cost) plus an upward term, inflated by
 * {@link ActionCosts#COST_HEURISTIC} (weighted A*) so the search is greedier and
 * expands far fewer nodes for near-optimal paths.
 *
 * <h2>Bounded</h2>
 * Capped at {@code maxNodes} expansions across all ticks. If the goal isn't
 * reached, the closest node seen (by raw heuristic) is returned as a
 * {@link Path#partial} route; the executor walks it and replans from there.
 */
public final class AStarSearch {

    /** Terminal vs. still-running. {@link #result()} is valid only at {@code DONE}. */
    public enum State { COMPUTING, DONE }

    private final NavContext ctx;
    private final BlockPos start;
    private final NavGoal goal;
    private final int maxNodes;
    /** Baritone {@code Favoring}: packed positions of the PREVIOUS path. An edge into
     *  one of these is discounted (× {@link PathSettings#BACKTRACK_COST_FAVORING_COEFFICIENT})
     *  so a replan reuses the prior route instead of flip-flopping between near-equal
     *  paths. Empty for the first search (no previous path). */
    private final it.unimi.dsi.fastutil.longs.LongSet favored;

    private final Long2ObjectOpenHashMap<PathNode> nodes = new Long2ObjectOpenHashMap<>();
    private final BinaryHeapOpenSet open = new BinaryHeapOpenSet();

    // Baritone's 7-coefficient best-so-far: one candidate endpoint per coefficient,
    // scored h + g/coeff. A large coefficient ≈ ignores travel cost → greediest
    // (gets closest to the goal); a small one ≈ near-optimal. On failure we return
    // the LEAST-greedy candidate that still made real progress — the local-minima
    // escape. Replaces a single closest-by-heuristic node, which collapsed in
    // concave terrain.
    private final double[] bestHeuristicSoFar = new double[PathSettings.COEFFICIENTS.length];
    private final PathNode[] bestSoFar = new PathNode[PathSettings.COEFFICIENTS.length];
    private int expansions = 0;

    private State state = State.COMPUTING;
    private Path result;

    /** Set from another thread to abandon an in-flight search (replan / stop). The expansion loop
     *  checks it and bails; the owner has already discarded this search's future. */
    private volatile boolean cancelled;

    AStarSearch(NavContext ctx, BlockPos start, NavGoal goal, int maxNodes,
               it.unimi.dsi.fastutil.longs.LongSet favored) {
        this.ctx = ctx;
        this.start = start.immutable();
        this.goal = goal;
        this.maxNodes = maxNodes;
        this.favored = favored;

        PathNode startNode = new PathNode(this.start, heuristic(this.start));
        startNode.cost = 0;
        // h is already weighted (costHeuristic lives inside the goal's XZ term),
        // so the heap key is plain g + h — no second multiplier.
        startNode.combinedCost = startNode.estimatedCostToGoal;
        nodes.put(this.start.asLong(), startNode);
        open.insert(startNode);
        for (int i = 0; i < PathSettings.COEFFICIENTS.length; i++) {
            bestHeuristicSoFar[i] = startNode.estimatedCostToGoal;
            bestSoFar[i] = startNode;
        }
    }

    /** Abandon this search (thread-safe). The next loop check bails; {@link #result()} may be null. */
    public void cancel() {
        cancelled = true;
    }

    /** Current search state. */
    public State state() {
        return state;
    }

    /**
     * The computed route. {@code null} until {@link State#DONE}; thereafter a
     * complete path (goal reached), a {@link Path#partial} best-effort path, or
     * an empty path (no progress).
     */
    public Path result() {
        return result;
    }

    /**
     * Expand at most {@code budget} nodes this tick. Returns {@link State#COMPUTING}
     * if more work remains (call again next tick), or {@link State#DONE} once a
     * path — complete or partial — is available from {@link #result()}.
     */
    public State step(int budget) {
        if (state == State.DONE) {
            return state;
        }

        int budgetLeft = budget;
        while (!open.isEmpty() && expansions < maxNodes && budgetLeft-- > 0 && !cancelled) {
            PathNode current = open.removeLowest();
            expansions++;

            if (isAtGoal(current.pos)) {
                result = reconstruct(current, false);
                state = State.DONE;
                return state;
            }

            for (Movement mv : Moves.generate(ctx, current.pos)) {
                if (mv.cost >= ActionCosts.COST_INF) continue;
                // Plausibility (Baritone asserts this): a 0 / negative / NaN edge
                // cost would silently corrupt the search — drop it rather than trust it.
                if (mv.cost <= 0.0 || Double.isNaN(mv.cost)) continue;
                long key = mv.dest.asLong();
                // Baritone Favoring: discount an edge whose destination sits on the
                // previous path, so a replan reuses the old route (damps oscillation).
                double edgeCost = favored.contains(key)
                        ? mv.cost * PathSettings.BACKTRACK_COST_FAVORING_COEFFICIENT
                        : mv.cost;
                double tentativeG = current.cost + edgeCost;

                PathNode neighbor = nodes.get(key);
                if (neighbor == null) {
                    neighbor = new PathNode(mv.dest.immutable(), heuristic(mv.dest));
                    nodes.put(key, neighbor);
                }
                // Baritone repropagation margin: ignore sub-0.01-tick improvements
                // (FP noise from traverse/diagonal combos over flat ground) to avoid
                // pointless decrease-key churn. Same threshold as the best-so-far loop.
                if (neighbor.cost - tentativeG <= PathSettings.MIN_IMPROVEMENT) continue;

                neighbor.cost = tentativeG;
                neighbor.combinedCost = tentativeG + neighbor.estimatedCostToGoal;
                neighbor.previous = current;
                neighbor.via = mv;

                // Update each coefficient's best-so-far candidate (Baritone's
                // local-minima escape). Small coeff weights travel cost heavily
                // (near-optimal); large coeff almost ignores it (greedy-to-goal).
                for (int i = 0; i < PathSettings.COEFFICIENTS.length; i++) {
                    double scored = neighbor.estimatedCostToGoal
                            + neighbor.cost / PathSettings.COEFFICIENTS[i];
                    if (bestHeuristicSoFar[i] - scored > PathSettings.MIN_IMPROVEMENT) {
                        bestHeuristicSoFar[i] = scored;
                        bestSoFar[i] = neighbor;
                    }
                }

                if (neighbor.isOpen()) {
                    open.update(neighbor);
                } else {
                    open.insert(neighbor);
                }
            }
        }

        // Terminated (open exhausted or node cap hit) vs. just out of this tick's
        // budget. Only the former produces a result; otherwise resume next tick.
        if (open.isEmpty() || expansions >= maxNodes) {
            result = bestEffort();
            state = State.DONE;
        }
        return state;
    }

    /**
     * Baritone's {@code bestSoFar()}: walk the coefficients small→large and return
     * the first candidate that travelled farther than {@code MIN_DIST_PATH²} from
     * the start — the least-greedy partial that made genuine progress. An empty
     * path if none did (so the caller reports a clean "no path").
     */
    private Path bestEffort() {
        double minDistSq = PathSettings.MIN_DIST_PATH * PathSettings.MIN_DIST_PATH;
        for (int i = 0; i < PathSettings.COEFFICIENTS.length; i++) {
            PathNode candidate = bestSoFar[i];
            if (candidate != null && candidate.via != null
                    && distFromStartSq(candidate) > minDistSq) {
                return reconstruct(candidate, true);
            }
        }
        return new Path(start, start, Collections.emptyList(), true);
    }

    private double distFromStartSq(PathNode node) {
        double dx = node.pos.getX() - start.getX();
        double dy = node.pos.getY() - start.getY();
        double dz = node.pos.getZ() - start.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean isAtGoal(BlockPos pos) {
        return goal.isAt(pos);
    }

    private Path reconstruct(PathNode end, boolean partial) {
        ArrayDeque<Movement> stack = new ArrayDeque<>();
        PathNode cur = end;
        while (cur != null && cur.via != null) {
            stack.push(cur.via);
            cur = cur.previous;
        }
        List<Movement> moves = new ArrayList<>(stack);
        return new Path(start, end.pos, moves, partial);
    }

    /**
     * The goal's admissible lower bound on remaining cost. The search inflates
     * this by {@link ActionCosts#COST_HEURISTIC} at the heap-key level; the raw
     * value is kept per-node for "closest-to-goal" partial-path tracking.
     */
    private double heuristic(BlockPos from) {
        return goal.heuristic(from);
    }
}
