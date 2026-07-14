package com.dwinovo.numen.core.pathing.engine;

import it.unimi.dsi.fastutil.longs.LongSets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the run-away goal family ({@code NavGoal.runAway}: flee a
 * threat, branch-mine exploration): the heuristic is NEGATIVE and decreases
 * with distance from the anchor, and the goal predicate is always false — the
 * whole mechanism relies on partial commits.
 *
 * <p>The bug this pins down: a learning-table "no entry" sentinel of {@code 0.0}
 * beats every negative base h in {@code h_eff = max(base, learned)}, clamping
 * h_eff to 0 everywhere — the commitment rule's "nearer than the start" test
 * then never passes and every flee navigation returns NO_PATH. The sentinel
 * must be {@link Double#NEGATIVE_INFINITY}.
 */
class NegativeHeuristicTest {

    /** Flat 2D lattice: 4 lateral neighbours, uniform cost 1. */
    private static final SuccessorFunction<long[]> LATTICE = (pos, sink) -> {
        int x = PackedPos.x(pos);
        int z = PackedPos.z(pos);
        sink.accept(PackedPos.pack(x + 1, 0, z), 1.0, new long[]{pos, PackedPos.pack(x + 1, 0, z)});
        sink.accept(PackedPos.pack(x - 1, 0, z), 1.0, new long[]{pos, PackedPos.pack(x - 1, 0, z)});
        sink.accept(PackedPos.pack(x, 0, z + 1), 1.0, new long[]{pos, PackedPos.pack(x, 0, z + 1)});
        sink.accept(PackedPos.pack(x, 0, z - 1), 1.0, new long[]{pos, PackedPos.pack(x, 0, z - 1)});
    };

    private static SearchResult<long[]> flee(long from, long anchor, HLearningTable table) {
        Heuristic runAway = pos -> -Math.sqrt(PackedPos.distSq(anchor, pos));   // farther = better
        return new PathSearch<>(from, LATTICE, runAway, pos -> false,
                SearchBudget.of(200, 400), table, LongSets.emptySet(),
                PathSearch.Config.standard()).run();
    }

    @Test
    void negativeHeuristicCommitsAwayInsteadOfNoPath() {
        long anchor = PackedPos.pack(0, 0, 0);
        HLearningTable table = new HLearningTable();

        SearchResult<long[]> first = flee(anchor, anchor, table);

        // The CRITICAL regression: with a 0.0 sentinel this was NO_PATH every time.
        assertEquals(SearchResult.Kind.PARTIAL_COMMIT, first.kind,
                "a run-away search must commit outward, not report no-path");
        assertNotEquals(first.start, first.end);
        assertTrue(PackedPos.distSq(first.start, first.end) >= 25.0,
                "committed end must clear the 5-block gate");
        assertEquals(0, first.stats.learnedConsultHits(),
                "an EMPTY table must never win a consult — the sentinel must lose to any base h");
    }

    @Test
    void secondSegmentKeepsFleeingWithSharedTable() {
        long anchor = PackedPos.pack(0, 0, 0);
        HLearningTable table = new HLearningTable();

        SearchResult<long[]> first = flee(anchor, anchor, table);
        assertEquals(SearchResult.Kind.PARTIAL_COMMIT, first.kind);

        SearchResult<long[]> second = flee(first.end, anchor, table);
        assertEquals(SearchResult.Kind.PARTIAL_COMMIT, second.kind,
                "learning write-back from segment 1 must not wedge segment 2");
        assertTrue(PackedPos.distSq(anchor, second.end) > PackedPos.distSq(anchor, first.end),
                "the flee must keep gaining distance from the anchor");
    }
}
