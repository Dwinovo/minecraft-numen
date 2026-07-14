package com.dwinovo.numen.core.pathing.engine;

import it.unimi.dsi.fastutil.longs.LongSets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T9 — HLearningTable lifecycle: max-merge, clear() restoring fresh behavior,
 * and the MAX_ENTRIES self-clear cap.
 *
 * <p>Cap-test choice (documented per the plan): the 1M-entry direct loop runs
 * in well under a second with fastutil, so the cap is exercised FOR REAL — no
 * reflection seam, no subclass.
 */
class LearningLifecycleTest {

    // ---- max-merge ----

    @Test
    void updateIsMaxMerge() {
        HLearningTable table = new HLearningTable();
        long pos = PackedPos.pack(1, 2, 3);

        table.update(pos, 5.0);
        assertEquals(5.0, table.learned(pos));

        table.update(pos, 3.0); // lower write is a no-op
        assertEquals(5.0, table.learned(pos));

        table.update(pos, 7.0); // higher write raises
        assertEquals(7.0, table.learned(pos));
        assertEquals(1, table.size());
    }

    @Test
    void unknownPositionHasNoLearnedValue() {
        // NEGATIVE_INFINITY, not 0: a zero sentinel would beat every negative base
        // heuristic (run-away goals) in max(base, learned) — see NegativeHeuristicTest.
        assertEquals(Double.NEGATIVE_INFINITY,
                new HLearningTable().learned(PackedPos.pack(9, 9, 9)));
    }

    // ---- clear() restores fresh behavior (engine-level) ----

    @Test
    void clearRestoresFreshSearchBehavior() {
        // A heuristic DEPRESSION is required for learning to accumulate: on a
        // healthy open plain the weighted heuristic keeps the frontier's f below
        // every closed node's f, so the no-op filter correctly writes nothing.
        // A wall in front of a far goal makes the budget-capped flood pool up.
        GridWorld world = new GridWorld()
                .bounds(-20, 0, -50, 220, 0, 50)
                .wallBox(5, 0, -50, 5, 0, 50); // sealed within any small budget's reach
        long start = PackedPos.pack(0, 0, 0);
        Heuristic h = GridWorld.weightedOctileHeuristic(200, 0, 0, 1.5, 1.0, 1.0);
        GoalPredicate goal = GridWorld.exactGoal(200, 0, 0);
        HLearningTable table = new HLearningTable();

        SearchResult<GridWorld.Step> first = new PathSearch<>(start, world, h, goal,
                SearchBudget.of(100, 100), table, LongSets.EMPTY_SET,
                PathSearch.Config.standard()).run();
        assertEquals(0, first.stats.learnedConsultHits(), "fresh table: nothing to consult");
        assertTrue(first.stats.learnedUpdates() > 0, "the pooled flood wrote learning");
        assertTrue(table.size() > 0);

        // rerun from the SAME start with the now-populated table: it consults
        SearchResult<GridWorld.Step> second = new PathSearch<>(start, world, h, goal,
                SearchBudget.of(100, 100), table, LongSets.EMPTY_SET,
                PathSearch.Config.standard()).run();
        assertTrue(second.stats.learnedConsultHits() > 0, "populated table gets consulted");

        // clear() -> identical-to-first behavior
        table.clear();
        assertEquals(0, table.size());
        SearchResult<GridWorld.Step> third = new PathSearch<>(start, world, h, goal,
                SearchBudget.of(100, 100), table, LongSets.EMPTY_SET,
                PathSearch.Config.standard()).run();
        assertEquals(0, third.stats.learnedConsultHits());
        assertEquals(first.kind, third.kind);
        assertEquals(first.end, third.end);
        assertEquals(first.stats.expansions(), third.stats.expansions());
        assertEquals(first.stats.learnedUpdates(), third.stats.learnedUpdates());
    }

    // ---- the MAX_ENTRIES cap self-clears ----

    @Test
    void capForcesSelfClearThenKeepsWorking() {
        HLearningTable table = new HLearningTable();
        assertEquals(0, table.capClears());

        // Fill to exactly the 1M cap with distinct positions (fast with fastutil).
        for (int i = 0; i < 1_000_000; i++) {
            table.update(PackedPos.pack(i % 30_000_000, (i / 30_000_000) % 4096 - 2048,
                    i / 30_000_000 / 4096), 1.0);
        }
        assertEquals(1_000_000, table.size());
        assertEquals(0, table.capClears(), "at the cap, not past it");

        // the next write trips the cap: full self-clear, then the write lands
        long straw = PackedPos.pack(-123, 45, -678);
        table.update(straw, 9.5);
        assertEquals(1, table.capClears());
        assertEquals(1, table.size(), "degraded to no-learning, then re-learns");
        assertEquals(9.5, table.learned(straw));
        assertEquals(Double.NEGATIVE_INFINITY, table.learned(PackedPos.pack(0, -2048, 0)),
                "pre-cap entries are gone");
    }
}
