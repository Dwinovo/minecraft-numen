package com.dwinovo.numen.core.pathing.engine;

import it.unimi.dsi.fastutil.longs.LongSets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T10 — the budget-vs-depression audit that retired the learning table.
 *
 * <p>The canonical depression worlds (deep well, two-pocket wall) are toy-sized
 * by design: their companion tests starve the search with sub-world budgets to
 * force segmentation, and the learning table was the mechanism that made those
 * starved segment chains converge. This test asks the production question
 * instead: does a single MEMORYLESS search swallow the whole depression within
 * a small deterministic FRACTION of the live budget? (The live policy is
 * wall-clock — 500ms/2s with a 500k-expansion fuse; the 10k-expansion stand-in
 * here is far below what any machine explores in 500ms.) It does — with two
 * orders of magnitude to spare — and even a 3× scaled-up well changes nothing.
 *
 * <p>Consequences, per the stability roadmap (docs/pathing-stability-roadmap.md):
 * a depression the failure budget covers is solved deterministically in ONE
 * search (no memory needed); a depression it can't cover (≳ the 500k fuse —
 * vaster than natural terrain) ends in a typed BOXED_IN report to the
 * task layer, which is the DESIGNED outcome (principle 5: escalate, don't
 * crawl). Cross-segment memory buys nothing at either scale.
 */
class ProductionBudgetAuditTest {

    /** Deterministic stand-in far below any machine's 500ms exploration. */
    private static final SearchBudget FLOOR = SearchBudget.of(10_000, 10_000);

    // ---- the deep well, exactly as DeepWellTest builds it ----

    private static final int SURFACE_Y = 20;
    private static final int GOAL_X = 10;

    private static GridWorld deepWell() {
        return new GridWorld()
                .bounds(-30, 0, -30, 30, SURFACE_Y, 30)
                .defaultWall(true)
                .carveBox(-10, 0, -10, 10, 0, 10)
                .carveBox(0, 0, 0, 0, SURFACE_Y, 0)
                .carveBox(0, SURFACE_Y, 0, GOAL_X, SURFACE_Y, 0)
                .verticalCostMultiplier(100.0);
    }

    @Test
    void deepWellCompletesMemorylessAtProductionBudget() {
        long start = PackedPos.pack(0, 0, 0);
        SearchResult<GridWorld.Step> result = new PathSearch<>(start, deepWell(),
                GridWorld.weightedOctileHeuristic(GOAL_X, SURFACE_Y, 0, 1.5, 3.0, 1.0),
                GridWorld.exactGoal(GOAL_X, SURFACE_Y, 0),
                FLOOR, LongSets.EMPTY_SET, PathSearch.Config.standard()).run();

        assertEquals(SearchResult.Kind.COMPLETE, result.kind,
                "production budget must swallow the depression in one deterministic search");
        assertEquals(PackedPos.pack(GOAL_X, SURFACE_Y, 0), result.end);
        GridWorld.assertChain(result);
    }

    /** 3× the canonical well (60 deep, 31×31 cavern) — still two orders inside budget. */
    @Test
    void tripleScaleWellCompletesMemorylessAtProductionBudget() {
        int surface = 60, goalX = 30;
        GridWorld w = new GridWorld()
                .bounds(-40, 0, -40, 40, surface, 40)
                .defaultWall(true)
                .carveBox(-15, 0, -15, 15, 0, 15)
                .carveBox(0, 0, 0, 0, surface, 0)
                .carveBox(0, surface, 0, goalX, surface, 0)
                .verticalCostMultiplier(100.0);
        long start = PackedPos.pack(0, 0, 0);

        SearchResult<GridWorld.Step> result = new PathSearch<>(start, w,
                GridWorld.weightedOctileHeuristic(goalX, surface, 0, 1.5, 3.0, 1.0),
                GridWorld.exactGoal(goalX, surface, 0),
                FLOOR, LongSets.EMPTY_SET, PathSearch.Config.standard()).run();

        assertEquals(SearchResult.Kind.COMPLETE, result.kind);
        assertEquals(PackedPos.pack(goalX, surface, 0), result.end);
    }

    // ---- the two-pocket wall, exactly as OscillationTest builds it ----

    @Test
    void twoPocketWallCompletesMemorylessAtProductionBudget() {
        int goalX = 22;
        GridWorld w = new GridWorld().bounds(0, 0, -20, 24, 0, 20);
        w.wallBox(12, 0, -20, 17, 0, 20);
        w.carveBox(12, 0, 3, 16, 0, 3);
        w.carveBox(12, 0, -4, 16, 0, -4);
        w.carveBox(12, 0, 20, 17, 0, 20);
        long start = PackedPos.pack(0, 0, 0);

        SearchResult<GridWorld.Step> result = new PathSearch<>(start, w,
                GridWorld.weightedOctileHeuristic(goalX, 0, 0, 1.5, 1.0, 1.0),
                GridWorld.exactGoal(goalX, 0, 0),
                FLOOR, LongSets.EMPTY_SET, PathSearch.Config.standard()).run();

        assertEquals(SearchResult.Kind.COMPLETE, result.kind,
                "the wedge the table used to escape must not exist at production budget");
        assertEquals(PackedPos.pack(goalX, 0, 0), result.end);
    }
}
