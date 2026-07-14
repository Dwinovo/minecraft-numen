package com.dwinovo.numen.core.pathing.engine;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;

import java.util.ArrayList;
import java.util.List;

/**
 * Synthetic 3D-lattice world for engine tests: edges to the 4 lateral
 * neighbors + up + down, with a per-CELL enter cost (default 1.0), walls,
 * bounds, and a global vertical cost multiplier (models MC's expensive
 * climb/dig-vertical moves).
 *
 * <p>Two authoring modes:
 * <ul>
 *   <li>open-by-default (the default): every in-bounds cell is passable unless
 *       {@link #wall}/{@link #wallBox} marks it;</li>
 *   <li>walled-by-default ({@link #defaultWall}): nothing is passable until
 *       {@link #carve}/{@link #carveBox} opens it (cave-like scenarios).</li>
 * </ul>
 * {@link #carve} also removes explicit wall marks, so gaps can be cut into a
 * {@link #wallBox} in either mode.
 */
final class GridWorld implements SuccessorFunction<GridWorld.Step> {

    /** Opaque edge payload: the traversal this edge represents. */
    record Step(long from, long to) {}

    private final Long2DoubleOpenHashMap cellCost = new Long2DoubleOpenHashMap();
    private final LongOpenHashSet walls = new LongOpenHashSet();
    private final LongOpenHashSet carved = new LongOpenHashSet();
    private boolean defaultWall = false;
    private double verticalCostMultiplier = 1.0;

    private int minX = -64, maxX = 64;
    private int minY = -64, maxY = 64;
    private int minZ = -64, maxZ = 64;

    GridWorld() {
        cellCost.defaultReturnValue(1.0);
    }

    // ---- authoring ----

    GridWorld bounds(int x0, int y0, int z0, int x1, int y1, int z1) {
        minX = x0; minY = y0; minZ = z0;
        maxX = x1; maxY = y1; maxZ = z1;
        return this;
    }

    /** Walled-by-default mode: only {@link #carve}d cells are passable. */
    GridWorld defaultWall(boolean value) {
        defaultWall = value;
        return this;
    }

    GridWorld wall(int x, int y, int z) {
        long p = PackedPos.pack(x, y, z);
        walls.add(p);
        carved.remove(p);
        return this;
    }

    GridWorld wallBox(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    wall(x, y, z);
                }
            }
        }
        return this;
    }

    /** Open a cell: clears any wall mark; in walled-by-default mode, makes it passable. */
    GridWorld carve(int x, int y, int z) {
        long p = PackedPos.pack(x, y, z);
        walls.remove(p);
        carved.add(p);
        return this;
    }

    GridWorld carveBox(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    carve(x, y, z);
                }
            }
        }
        return this;
    }

    /** Per-cell enter cost (lateral; vertical additionally scaled by the multiplier). */
    GridWorld cost(int x, int y, int z, double cost) {
        cellCost.put(PackedPos.pack(x, y, z), cost);
        return this;
    }

    /** Scale every vertical (up/down) edge's cost — models expensive climbs/digs. */
    GridWorld verticalCostMultiplier(double multiplier) {
        verticalCostMultiplier = multiplier;
        return this;
    }

    // ---- SuccessorFunction ----

    boolean passable(int x, int y, int z) {
        if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
            return false;
        }
        long p = PackedPos.pack(x, y, z);
        if (walls.contains(p)) {
            return false;
        }
        return !defaultWall || carved.contains(p);
    }

    @Override
    public void expand(long pos, EdgeSink<Step> sink) {
        int x = PackedPos.x(pos);
        int y = PackedPos.y(pos);
        int z = PackedPos.z(pos);
        lateral(pos, x + 1, y, z, sink);
        lateral(pos, x - 1, y, z, sink);
        lateral(pos, x, y, z + 1, sink);
        lateral(pos, x, y, z - 1, sink);
        vertical(pos, x, y + 1, z, sink);
        vertical(pos, x, y - 1, z, sink);
    }

    private void lateral(long from, int x, int y, int z, EdgeSink<Step> sink) {
        if (passable(x, y, z)) {
            long dest = PackedPos.pack(x, y, z);
            sink.accept(dest, cellCost.get(dest), new Step(from, dest));
        }
    }

    private void vertical(long from, int x, int y, int z, EdgeSink<Step> sink) {
        if (passable(x, y, z)) {
            long dest = PackedPos.pack(x, y, z);
            sink.accept(dest, cellCost.get(dest) * verticalCostMultiplier, new Step(from, dest));
        }
    }

    // ---- heuristic / goal helpers (mirror the production shape) ----

    private static final double SQRT_2_MINUS_1 = Math.sqrt(2) - 1;

    /**
     * Horizontal octile distance × weight, plus per-block vertical constants —
     * the same shape as the MC adapter's Baritone-weighted base heuristic.
     */
    static Heuristic weightedOctileHeuristic(int gx, int gy, int gz,
                                             double wHoriz, double upCost, double downCost) {
        return pos -> {
            double dx = Math.abs(PackedPos.x(pos) - gx);
            double dz = Math.abs(PackedPos.z(pos) - gz);
            double octile = Math.max(dx, dz) + SQRT_2_MINUS_1 * Math.min(dx, dz);
            int dy = gy - PackedPos.y(pos);
            double vertical = dy > 0 ? dy * upCost : -dy * downCost;
            return octile * wHoriz + vertical;
        };
    }

    static GoalPredicate exactGoal(int x, int y, int z) {
        long goal = PackedPos.pack(x, y, z);
        return pos -> pos == goal;
    }

    // ---- multi-segment driver ----

    /**
     * Run search → if PARTIAL_COMMIT teleport the start to {@code result.end}
     * → rerun, up to {@code maxSegments}, collecting per-segment results.
     *
     * @param shared        the learning table shared across segments, or
     *                      {@code null} for a FRESH table per segment (the
     *                      memoryless baseline).
     * @param retryOnNoPath NO_PATH → retry from the same position (models the
     *                      caller's stall-retry); otherwise NO_PATH ends the run.
     */
    static List<SearchResult<Step>> segments(SuccessorFunction<Step> world, long start,
                                             Heuristic heuristic, GoalPredicate goal,
                                             SearchBudget budget, HLearningTable shared,
                                             PathSearch.Config config, int maxSegments,
                                             boolean retryOnNoPath) {
        List<SearchResult<Step>> results = new ArrayList<>();
        long current = start;
        for (int i = 0; i < maxSegments; i++) {
            HLearningTable table = shared != null ? shared : new HLearningTable();
            PathSearch<Step> search = new PathSearch<>(current, world, heuristic, goal,
                    budget, table, LongSets.EMPTY_SET, config);
            SearchResult<Step> result = search.run();
            results.add(result);
            if (result.kind == SearchResult.Kind.PARTIAL_COMMIT) {
                current = result.end;
            } else if (result.kind != SearchResult.Kind.NO_PATH || !retryOnNoPath) {
                break;
            }
        }
        return results;
    }

    // ---- assertions support ----

    /** The sequence of positions a result's edges visit: start, then each step's destination. */
    static LongList positions(SearchResult<Step> result) {
        LongList out = new LongArrayList();
        out.add(result.start);
        for (Step step : result.edges) {
            out.add(step.to());
        }
        return out;
    }

    /** Verify the edge chain is contiguous from start to end. */
    static void assertChain(SearchResult<Step> result) {
        long at = result.start;
        for (Step step : result.edges) {
            if (step.from() != at) {
                throw new AssertionError("broken chain: expected step from "
                        + PackedPos.x(at) + "," + PackedPos.y(at) + "," + PackedPos.z(at)
                        + " but step.from was "
                        + PackedPos.x(step.from()) + "," + PackedPos.y(step.from()) + "," + PackedPos.z(step.from()));
            }
            at = step.to();
        }
        if (at != result.end) {
            throw new AssertionError("chain does not terminate at result.end");
        }
    }
}
