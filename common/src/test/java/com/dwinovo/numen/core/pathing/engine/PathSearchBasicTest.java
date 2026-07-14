package com.dwinovo.numen.core.pathing.engine;

import it.unimi.dsi.fastutil.longs.LongSets;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3 (flat plain), T4 (wall with gap), T10 (cancellation), T12 (sealed box),
 * T13 (edge hygiene) + the instant-goal degenerate case.
 */
class PathSearchBasicTest {

    private static PathSearch<GridWorld.Step> search(SuccessorFunction<GridWorld.Step> world,
                                                     long start, Heuristic h, GoalPredicate goal,
                                                     SearchBudget budget, HLearningTable table) {
        return new PathSearch<>(start, world, h, goal, budget, table,
                LongSets.EMPTY_SET, PathSearch.Config.standard());
    }

    // ---- T3: flat plain, 2D ----

    @Test
    void flatPlainCompleteOptimalAndFocused() {
        GridWorld world = new GridWorld().bounds(-5, 64, -10, 30, 64, 10); // y pinned: 2D
        long start = PackedPos.pack(0, 64, 0);
        Heuristic h = GridWorld.weightedOctileHeuristic(20, 64, 0, 1.5, 1.0, 1.0);
        SearchResult<GridWorld.Step> result = search(world, start, h,
                GridWorld.exactGoal(20, 64, 0), SearchBudget.of(10_000, 10_000),
                new HLearningTable()).run();

        assertEquals(SearchResult.Kind.COMPLETE, result.kind);
        assertEquals(PackedPos.pack(20, 64, 0), result.end);
        assertEquals(20, result.edges.size(), "optimal length on a uniform grid");
        GridWorld.assertChain(result);
        assertTrue(result.stats.expansions() < 200,
                "weighted h must keep the search focused, got " + result.stats.expansions());
        assertEquals(0, result.stats.learnedUpdates(), "COMPLETE never writes learning");
        assertEquals(0, result.stats.rejectedEdges());
    }

    // ---- T4: wall with a gap ----

    @Test
    void wallWithGapRoutesThroughGap() {
        GridWorld world = new GridWorld()
                .bounds(-2, 0, -10, 12, 0, 10)
                .wallBox(5, 0, -10, 5, 0, 10)
                .carve(5, 0, 7); // the single gap
        long start = PackedPos.pack(0, 0, 0);
        Heuristic h = GridWorld.weightedOctileHeuristic(10, 0, 0, 1.5, 1.0, 1.0);
        SearchResult<GridWorld.Step> result = search(world, start, h,
                GridWorld.exactGoal(10, 0, 0), SearchBudget.of(10_000, 10_000),
                new HLearningTable()).run();

        assertEquals(SearchResult.Kind.COMPLETE, result.kind);
        GridWorld.assertChain(result);
        assertTrue(GridWorld.positions(result).contains(PackedPos.pack(5, 0, 7)),
                "the only way through is the gap cell");
    }

    // ---- T12: sealed box ----

    @Test
    void sealedBoxGoalOutsideIsNoPathExhaustedNoLearning() {
        // start CENTERED in the room: every cell is < minCommitDist away, so
        // nothing is ever committable and the outcome is a clean NO_PATH.
        GridWorld world = new GridWorld()
                .bounds(-20, 0, -20, 20, 0, 20)
                .defaultWall(true)
                .carveBox(-2, 0, -2, 2, 0, 2); // 25-cell sealed room
        long start = PackedPos.pack(0, 0, 0);
        Heuristic h = GridWorld.weightedOctileHeuristic(10, 0, 0, 1.5, 1.0, 1.0);
        HLearningTable table = new HLearningTable();
        SearchResult<GridWorld.Step> result = search(world, start, h,
                GridWorld.exactGoal(10, 0, 0), SearchBudget.of(1000, 1000), table).run();

        assertEquals(SearchResult.Kind.NO_PATH, result.kind);
        assertTrue(result.stats.frontierExhausted(), "every reachable cell explored");
        assertEquals(25, result.stats.expansions());
        assertTrue(result.edges.isEmpty());
        assertEquals(result.start, result.end);
        assertEquals(0, result.stats.learnedUpdates(),
                "frontier-exhausted terminations write NOTHING");
        assertEquals(0, table.size());
    }

    @Test
    void sealedBoxGoalInsideCompletes() {
        GridWorld world = new GridWorld()
                .bounds(-20, 0, -20, 20, 0, 20)
                .defaultWall(true)
                .carveBox(-2, 0, -2, 2, 0, 2);
        long start = PackedPos.pack(0, 0, 0);
        Heuristic h = GridWorld.weightedOctileHeuristic(2, 0, 2, 1.5, 1.0, 1.0);
        SearchResult<GridWorld.Step> result = search(world, start, h,
                GridWorld.exactGoal(2, 0, 2), SearchBudget.of(1000, 1000),
                new HLearningTable()).run();

        assertEquals(SearchResult.Kind.COMPLETE, result.kind);
        assertEquals(4, result.edges.size(), "manhattan-optimal inside the room");
        GridWorld.assertChain(result);
    }

    // ---- T13: edge hygiene ----

    @Test
    void garbageEdgesAreDroppedCountedAndSearchSucceeds() {
        // Line 0 -> 1 -> 2 -> 3(goal); every expansion also emits one edge each of
        // cost 0, NaN, -1 and +Inf to distinct dests that must never join the search.
        SuccessorFunction<String> line = (pos, sink) -> {
            int x = PackedPos.x(pos);
            sink.accept(PackedPos.pack(x + 1, 0, 0), 1.0, "good-" + x);
            sink.accept(PackedPos.pack(x, 1, 0), 0.0, "zero");
            sink.accept(PackedPos.pack(x, 2, 0), Double.NaN, "nan");
            sink.accept(PackedPos.pack(x, 3, 0), -1.0, "negative");
            sink.accept(PackedPos.pack(x, 4, 0), Double.POSITIVE_INFINITY, "infinite");
        };
        long start = PackedPos.pack(0, 0, 0);
        long goal = PackedPos.pack(3, 0, 0);
        PathSearch<String> search = new PathSearch<>(start, line,
                pos -> 3.0 - PackedPos.x(pos), pos -> pos == goal,
                SearchBudget.of(100, 100), new HLearningTable(),
                LongSets.EMPTY_SET, PathSearch.Config.standard());
        SearchResult<String> result = search.run();

        assertEquals(SearchResult.Kind.COMPLETE, result.kind);
        assertEquals(java.util.List.of("good-0", "good-1", "good-2"), result.edges);
        // expansions: 0, 1, 2 expanded (goal returns before expanding) -> 3 x 4 garbage
        assertEquals(4, result.stats.expansions());
        assertEquals(12, result.stats.rejectedEdges());
    }

    // ---- T10: cancellation from another thread ----

    @Test
    void cancelMidRunReturnsCancelledAndNeverWritesLearning() throws Exception {
        GridWorld world = new GridWorld().bounds(-500, 0, -500, 500, 0, 500);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        SuccessorFunction<GridWorld.Step> blocking = (pos, sink) -> {
            entered.countDown();
            try {
                proceed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            world.expand(pos, sink);
        };

        long start = PackedPos.pack(0, 0, 0);
        HLearningTable table = new HLearningTable();
        PathSearch<GridWorld.Step> search = new PathSearch<>(start, blocking,
                GridWorld.weightedOctileHeuristic(400, 0, 0, 1.5, 1.0, 1.0),
                GridWorld.exactGoal(400, 0, 0), SearchBudget.of(100_000, 100_000),
                table, LongSets.EMPTY_SET, PathSearch.Config.standard());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<SearchResult<GridWorld.Step>> future = pool.submit(search::run);
            assertTrue(entered.await(5, TimeUnit.SECONDS), "search never started expanding");
            search.cancel();          // from this (another) thread, mid-expansion
            proceed.countDown();      // let the blocked expansion finish
            SearchResult<GridWorld.Step> result = future.get(5, TimeUnit.SECONDS);

            assertEquals(SearchResult.Kind.CANCELLED, result.kind);
            assertTrue(result.edges.isEmpty());
            assertEquals(result.start, result.end);
            assertEquals(0, result.stats.learnedUpdates());
            assertEquals(0, table.size(), "a cancelled search never writes to the learning table");
            assertTrue(result.stats.expansions() >= 1, "it was genuinely mid-run");
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- degenerate: start satisfies the goal ----

    @Test
    void startAtGoalCompletesImmediately() {
        GridWorld world = new GridWorld().bounds(-5, 0, -5, 5, 0, 5);
        long start = PackedPos.pack(0, 0, 0);
        SearchResult<GridWorld.Step> result = search(world, start,
                GridWorld.weightedOctileHeuristic(0, 0, 0, 1.5, 1.0, 1.0),
                GridWorld.exactGoal(0, 0, 0), SearchBudget.of(10, 10),
                new HLearningTable()).run();

        assertEquals(SearchResult.Kind.COMPLETE, result.kind);
        assertTrue(result.edges.isEmpty());
        assertEquals(start, result.end);
        assertEquals(0, result.stats.expansions());
        assertFalse(result.stats.stoppedAtPrimary());
    }
}
