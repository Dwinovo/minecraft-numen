package com.dwinovo.numen.core.pathing.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T7 — the mini deep well: a 20-deep vertical shaft whose climb the heuristic
 * under-prices 33x (vertical edge cost 100, heuristic up-cost 3), with a big
 * cheap lateral cavern at the bottom and the goal on the surface. Pins the
 * h-based commitment rule under a STARVED budget (sub-world, forces
 * segmentation): the v1 failure mode was committing to cheap lateral flooding
 * forever; the commitment rule must commit UP on segment 1.
 *
 * <p>At production budgets this world resolves in ONE complete search — see
 * {@link ProductionBudgetAuditTest}; the starved budget here isolates the
 * commitment rule, nothing else.
 */
class DeepWellTest {

    private static final int SURFACE_Y = 20;
    private static final int GOAL_X = 10;

    private static GridWorld world() {
        return new GridWorld()
                .bounds(-30, 0, -30, 30, SURFACE_Y, 30)
                .defaultWall(true)
                // big cheap lateral cavern at the bottom (y = 0)
                .carveBox(-10, 0, -10, 10, 0, 10)
                // the vertical shaft, 20 deep, at (0, *, 0)
                .carveBox(0, 0, 0, 0, SURFACE_Y, 0)
                // surface corridor from the shaft head to the goal
                .carveBox(0, SURFACE_Y, 0, GOAL_X, SURFACE_Y, 0)
                // vertical movement is 100x lateral — the heuristic prices it at 3
                .verticalCostMultiplier(100.0);
    }

    private static Heuristic heuristic() {
        return GridWorld.weightedOctileHeuristic(GOAL_X, SURFACE_Y, 0, 1.5, 3.0, 1.0);
    }

    @Test
    void firstSegmentCommitsUpNotLateral() {
        long start = PackedPos.pack(0, 0, 0);
        List<SearchResult<GridWorld.Step>> segments = GridWorld.segments(
                world(), start, heuristic(), GridWorld.exactGoal(GOAL_X, SURFACE_Y, 0),
                SearchBudget.of(460, 460),
                PathSearch.Config.standard(), 10, false);

        SearchResult<GridWorld.Step> first = segments.get(0);
        assertEquals(SearchResult.Kind.PARTIAL_COMMIT, first.kind);
        assertTrue(PackedPos.y(first.end) > PackedPos.y(first.start),
                "must commit UP the shaft, not into the lateral flood; ended at y="
                        + PackedPos.y(first.end));
        GridWorld.assertChain(first);
    }
}
