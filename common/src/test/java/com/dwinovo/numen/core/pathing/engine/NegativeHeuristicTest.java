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
 * whole mechanism relies on partial commits. Anything in the engine that
 * assumes h ≥ 0 (a default, a sentinel, a clamp) silently turns every flee
 * navigation into NO_PATH; this pins the commit-outward behaviour directly.
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

    private static SearchResult<long[]> flee(long from, long anchor) {
        Heuristic runAway = pos -> -Math.sqrt(PackedPos.distSq(anchor, pos));   // farther = better
        return new PathSearch<>(from, LATTICE, runAway, pos -> false,
                SearchBudget.of(200, 400), LongSets.emptySet(),
                PathSearch.Config.standard()).run();
    }

    @Test
    void negativeHeuristicCommitsAwayInsteadOfNoPath() {
        long anchor = PackedPos.pack(0, 0, 0);

        SearchResult<long[]> first = flee(anchor, anchor);

        assertEquals(SearchResult.Kind.PARTIAL_COMMIT, first.kind,
                "a run-away search must commit outward, not report no-path");
        assertNotEquals(first.start, first.end);
        assertTrue(PackedPos.distSq(first.start, first.end) >= 25.0,
                "committed end must clear the 5-block gate");
    }

    @Test
    void secondSegmentKeepsFleeing() {
        long anchor = PackedPos.pack(0, 0, 0);
        SearchResult<long[]> first = flee(anchor, anchor);
        assertEquals(SearchResult.Kind.PARTIAL_COMMIT, first.kind);

        SearchResult<long[]> second = flee(first.end, anchor);
        assertEquals(SearchResult.Kind.PARTIAL_COMMIT, second.kind,
                "fleeing must keep committing outward from the new position");
        assertTrue(PackedPos.distSq(anchor, second.end) > PackedPos.distSq(anchor, first.end),
                "segment 2 must end farther from the anchor than segment 1");
    }
}
