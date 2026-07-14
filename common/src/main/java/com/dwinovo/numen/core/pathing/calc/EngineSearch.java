package com.dwinovo.numen.core.pathing.calc;

import com.dwinovo.numen.core.pathing.engine.GoalPredicate;
import com.dwinovo.numen.core.pathing.engine.HLearningTable;
import com.dwinovo.numen.core.pathing.engine.Heuristic;
import com.dwinovo.numen.core.pathing.engine.PackedPos;
import com.dwinovo.numen.core.pathing.engine.PathSearch;
import com.dwinovo.numen.core.pathing.engine.SearchBudget;
import com.dwinovo.numen.core.pathing.engine.SearchResult;
import com.dwinovo.numen.core.pathing.engine.SearchStats;
import com.dwinovo.numen.core.pathing.engine.SuccessorFunction;
import com.dwinovo.numen.core.pathing.movement.Movement;
import com.dwinovo.numen.core.pathing.movement.Moves;
import com.dwinovo.numen.core.pathing.util.ActionCosts;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * The Minecraft adapter for the v2 planning engine
 * ({@link com.dwinovo.numen.core.pathing.engine}): binds one
 * {@link PathSearch} to the {@link Moves} movement graph, a {@link NavGoal}
 * and a frozen {@link NavContext}, and translates the engine's
 * {@link SearchResult} back into the executor's {@link Path} shape.
 *
 * <p>This class is the ONLY place {@link BlockPos} ↔ {@link PackedPos}
 * conversion happens — the engine never sees a Minecraft type, and no caller
 * ever sees a packed long that wasn't packed here (in particular, the favoring
 * set fed back into the engine must come from {@link #favoring(Path)}, never
 * from {@code BlockPos.asLong()}).
 *
 * <h2>Threading</h2>
 * {@link #create} runs on the tick thread; {@link #run()} is called ONCE, on a
 * planner-pool worker (or synchronously on the tick thread for the rare
 * non-frozen-context fallback). The engine invokes the successor function,
 * heuristic and goal predicate only from inside {@code run()} — a single
 * worker thread per search — so the reused mutable cursors below are safe
 * without synchronization. {@link #cancel()} may be called from any thread
 * (delegates to the engine's cooperative cancel).
 */
public final class EngineSearch {

    private final NavContext ctx;
    private final BlockPos start;
    private final PathSearch<Movement> search;

    /** Cursor for unpacking heuristic/goal-predicate queries. Worker-thread only
     *  (see class javadoc); NavGoal implementations must not retain the argument
     *  (documented contract on {@link NavGoal#pointBound}). */
    private final BlockPos.MutableBlockPos goalCursor = new BlockPos.MutableBlockPos();

    /** Set exactly once, at the end of {@link #run()} — the autopsy surface below
     *  reads it afterwards (volatile: run() happens on a worker, the autopsy on
     *  the tick thread). */
    private volatile SearchResult<Movement> result;

    private EngineSearch(NavContext ctx, BlockPos start, NavGoal goal,
                         LongSet favoredPacked, HLearningTable learning,
                         SearchBudget budget) {
        this.ctx = ctx;
        this.start = start.immutable();
        long packedStart = PackedPos.pack(start.getX(), start.getY(), start.getZ());

        SuccessorFunction<Movement> successors = this::expand;
        Heuristic heuristic = pos -> goal.heuristic(
                goalCursor.set(PackedPos.x(pos), PackedPos.y(pos), PackedPos.z(pos)));
        GoalPredicate goalPredicate = pos -> goal.isAt(
                goalCursor.set(PackedPos.x(pos), PackedPos.y(pos), PackedPos.z(pos)));

        this.search = new PathSearch<>(packedStart, successors, heuristic, goalPredicate,
                budget, learning, favoredPacked, PathSearch.Config.standard());
    }

    /**
     * Build a search over the MC movement graph. Tick thread; the context must
     * be a frozen {@link NavContext#forSearch} snapshot when {@link #run()} will
     * execute on a worker. {@code favoredPacked} must be engine-packed (from
     * {@link #favoring(Path)}), never {@code BlockPos.asLong()} values.
     */
    public static EngineSearch create(NavContext ctx, BlockPos start, NavGoal goal,
                                      LongSet favoredPacked, HLearningTable learning,
                                      SearchBudget budget) {
        return new EngineSearch(ctx, start, goal, favoredPacked, learning, budget);
    }

    /**
     * The engine-packed favored set for the NEXT search: the path's start plus
     * every movement destination, packed via {@link PackedPos}. Replaces the v1
     * {@code pathHashes} (which used {@code BlockPos.asLong()} — same layout
     * today, but the engine deliberately has no equivalence contract with it).
     */
    public static LongSet favoring(Path path) {
        LongOpenHashSet set = new LongOpenHashSet(path.movements.size() + 1);
        set.add(PackedPos.pack(path.start.getX(), path.start.getY(), path.start.getZ()));
        for (Movement m : path.movements) {
            set.add(PackedPos.pack(m.dest.getX(), m.dest.getY(), m.dest.getZ()));
        }
        return set;
    }

    /** Cooperative cancel, any thread; a cancelled {@link #run()} yields the empty path. */
    public void cancel() {
        search.cancel();
    }

    /**
     * Run the search to termination and translate the result. Call ONCE, on a
     * worker thread (or synchronously for the non-frozen-context fallback).
     *
     * <p>Result mapping: COMPLETE → full {@link Path}; PARTIAL_COMMIT → the same
     * with {@code partial = true}; NO_PATH / CANCELLED → v1's empty-path shape
     * ({@code start == end}, no movements, {@code partial = true}) so the
     * caller's {@code path.isEmpty()} handling behaves identically to v1.
     */
    public Path run() {
        SearchResult<Movement> r = search.run();
        this.result = r;
        return switch (r.kind) {
            case COMPLETE -> new Path(start, lastDest(r.edges), r.edges, false);
            case PARTIAL_COMMIT -> new Path(start, lastDest(r.edges), r.edges, true);
            case NO_PATH, CANCELLED -> new Path(start, start, List.of(), true);
        };
    }

    /** End of the route as a BlockPos: the LAST movement's dest (already a
     *  BlockPos — no unpack round-trip); the start for empty edge lists. */
    private BlockPos lastDest(List<Movement> edges) {
        return edges.isEmpty() ? start : edges.get(edges.size() - 1).dest;
    }

    /**
     * The domain seam: unpack the engine's position, expand the {@link Moves}
     * graph, veto {@code COST_INF} edges (the domain's "impossible" filter — the
     * engine's own hygiene backstop handles non-positive/NaN), and sink each
     * movement under its packed destination.
     *
     * <p>The source is materialized as an immutable {@code new BlockPos} per
     * expansion rather than a reused mutable cursor: {@link Movement} retains its
     * {@code src} argument (via {@code immutable()}, which on a mutable cursor
     * copies per-Movement anyway — one copy per expansion is both correct and
     * cheaper), and {@link Moves#generate} derives all neighbors from it.
     * Correctness over micro-optimization.
     */
    private void expand(long pos, com.dwinovo.numen.core.pathing.engine.EdgeSink<Movement> sink) {
        BlockPos src = new BlockPos(PackedPos.x(pos), PackedPos.y(pos), PackedPos.z(pos));
        for (Movement mv : Moves.generate(ctx, src)) {
            if (mv.cost >= ActionCosts.COST_INF) continue;   // domain veto
            sink.accept(PackedPos.pack(mv.dest.getX(), mv.dest.getY(), mv.dest.getZ()),
                    mv.cost, mv);
        }
    }

    // ---- autopsy surface (valid after run(); safe zero-defaults before) ----

    /** Nodes actually expanded by this search. */
    public int expansionsDone() {
        return stats().expansions();
    }

    /** True when the frontier EMPTIED — every reachable cell explored — vs. budget. */
    public boolean frontierExhausted() {
        return stats().frontierExhausted();
    }

    /** Max squared distance from the start over all expanded nodes. */
    public double bestProgressSq() {
        return stats().bestProgressSq();
    }

    /** Full engine telemetry for the run ({@link SearchStats#empty()} before {@link #run()}). */
    public SearchStats stats() {
        SearchResult<Movement> r = result;
        return r == null ? SearchStats.empty() : r.stats;
    }
}
