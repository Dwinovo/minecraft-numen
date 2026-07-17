package com.dwinovo.numen.core.pathing.engine;

import it.unimi.dsi.fastutil.longs.LongSets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T5 (budget-exhaust commit) and T6 (primary/failure two-phase semantics). */
class CommitmentTest {

    private static SearchResult<GridWorld.Step> run(SuccessorFunction<GridWorld.Step> world,
                                                    long start, Heuristic h, GoalPredicate goal,
                                                    SearchBudget budget) {
        return new PathSearch<>(start, world, h, goal, budget, LongSets.EMPTY_SET, PathSearch.Config.standard()).run();
    }

    // ---- T5: far goal, tiny failure budget -> PARTIAL_COMMIT toward the goal ----

    @Test
    void budgetExhaustCommitsGenuineProgress() {
        GridWorld world = new GridWorld().bounds(-50, 0, -50, 250, 0, 50);
        long start = PackedPos.pack(0, 0, 0);
        Heuristic h = GridWorld.weightedOctileHeuristic(200, 0, 0, 1.5, 1.0, 1.0);
        SearchResult<GridWorld.Step> result = run(world, start, h,
                GridWorld.exactGoal(200, 0, 0), SearchBudget.of(60, 60));

        assertEquals(SearchResult.Kind.PARTIAL_COMMIT, result.kind);
        assertTrue(PackedPos.distSq(result.start, result.end) >= 25.0,
                "commit-distance gate: >= minCommitDist from start");
        assertTrue(h.estimate(result.end) < h.estimate(result.start),
                "the committed end must be genuinely nearer the goal by h");
        GridWorld.assertChain(result);
        assertEquals(60, result.stats.expansions(), "failure budget fully spent");
        assertTrue(result.stats.bestProgressSq() >= 25.0);
    }

    // ---- T6a: open terrain, candidate found early -> stops AT primary ----

    @Test
    void primaryStopsEarlyOnceCandidateExists() {
        GridWorld world = new GridWorld().bounds(-50, 0, -50, 150, 0, 50);
        long start = PackedPos.pack(0, 0, 0);
        Heuristic h = GridWorld.weightedOctileHeuristic(100, 0, 0, 1.5, 1.0, 1.0);
        SearchResult<GridWorld.Step> result = run(world, start, h,
                GridWorld.exactGoal(100, 0, 0), SearchBudget.of(100, 10_000));

        assertEquals(SearchResult.Kind.PARTIAL_COMMIT, result.kind);
        assertTrue(result.stats.stoppedAtPrimary(),
                "candidate existed at the primary budget -> mid-journey fast path");
        assertEquals(100, result.stats.expansions(),
                "stops exactly at the primary check, far under failure");
    }

    // ---- T6b: start boxed, nothing committable within 5 blocks -> runs PAST primary ----

    @Test
    void searchRunsPastPrimaryUntilSomethingIsCommittable() {
        // Walled ring at radius 4 (interior |x|,|z| <= 3, all closer than
        // minCommitDist=5), single gap on the side FACING AWAY from the goal, so
        // the earliest committable cell is found only after flooding the interior
        // and wrapping around outside — well past the primary budget.
        GridWorld world = new GridWorld().bounds(-10, 0, -10, 60, 0, 10);
        world.wallBox(-4, 0, -4, -4, 0, 4);
        world.wallBox(4, 0, -4, 4, 0, 4);
        world.wallBox(-4, 0, -4, 4, 0, -4);
        world.wallBox(-4, 0, 4, 4, 0, 4);
        world.carve(-4, 0, 0); // the rear gap
        long start = PackedPos.pack(0, 0, 0);
        Heuristic h = GridWorld.weightedOctileHeuristic(50, 0, 0, 1.5, 1.0, 1.0);
        SearchResult<GridWorld.Step> result = run(world, start, h,
                GridWorld.exactGoal(50, 0, 0), SearchBudget.of(50, 300));

        assertEquals(SearchResult.Kind.PARTIAL_COMMIT, result.kind);
        assertTrue(result.stats.expansions() > 50,
                "no candidate at primary -> keeps expanding, got " + result.stats.expansions());
        assertTrue(result.stats.expansions() < 300,
                "stops as soon as a candidate exists past primary");
        assertTrue(result.stats.stoppedAtPrimary(),
                "the eventual stop is still the primary-with-candidate condition");
        assertTrue(PackedPos.distSq(result.start, result.end) >= 25.0);
        assertTrue(h.estimate(result.end) < h.estimate(result.start));
        GridWorld.assertChain(result);
    }
}
