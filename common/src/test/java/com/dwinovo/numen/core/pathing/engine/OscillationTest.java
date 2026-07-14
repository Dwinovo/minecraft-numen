package com.dwinovo.numen.core.pathing.engine;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T8 — two-pocket wedging: a thick wall with two dead-end pockets carved into
 * it (pocket A slightly nearer the goal than pocket B) and the only real way
 * through far off-axis. Greedy h-commitment drives segment 1 into pocket A's
 * tip; a MEMORYLESS replanner (fresh table each segment) then finds nothing
 * committable within budget from there and re-fails from the SAME spot forever
 * (the "revisit" form of oscillation — the h-monotone commit rule makes an
 * A/B/A position ping-pong impossible, so being wedged manifests as repeated
 * identical NO_PATHs). With ONE shared table the wedge is non-repeating: no
 * committed end ever recurs, the chain escapes, reaches the goal, and the
 * final segment is far cheaper than the budget-capped early ones.
 */
class OscillationTest {

    private static final int GOAL_X = 22;

    private static GridWorld world() {
        GridWorld w = new GridWorld().bounds(0, 0, -20, 24, 0, 20);
        // thick wall: x in [12..17], full z range
        w.wallBox(12, 0, -20, 17, 0, 20);
        // pocket A: dead-end corridor into the wall at z=+3 (tip nearer the goal)
        w.carveBox(12, 0, 3, 16, 0, 3);
        // pocket B: dead-end corridor at z=-4, one cell shallower in h
        w.carveBox(12, 0, -4, 16, 0, -4);
        // the only true route: a gap clean through the wall at the far z edge
        w.carveBox(12, 0, 20, 17, 0, 20);
        return w;
    }

    private static Heuristic heuristic() {
        return GridWorld.weightedOctileHeuristic(GOAL_X, 0, 0, 1.5, 1.0, 1.0);
    }

    private static List<SearchResult<GridWorld.Step>> run(HLearningTable shared, int maxSegments) {
        return GridWorld.segments(world(), PackedPos.pack(0, 0, 0), heuristic(),
                GridWorld.exactGoal(GOAL_X, 0, 0), SearchBudget.of(100, 100), shared,
                PathSearch.Config.standard(), maxSegments, true);
    }

    @Test
    void memorylessReplanningWedgesInPocketA() {
        List<SearchResult<GridWorld.Step>> segments = run(null, 8); // fresh table per segment

        // segment 1 greedily commits into a pocket tip
        SearchResult<GridWorld.Step> first = segments.get(0);
        assertEquals(SearchResult.Kind.PARTIAL_COMMIT, first.kind);
        long pocketTip = first.end;
        assertTrue(PackedPos.x(pocketTip) >= 12, "committed into the wall pockets");

        // ... and every later attempt fails from that same spot: the ends REVISIT
        // (repeat) — never COMPLETE, never anywhere new.
        assertEquals(8, segments.size(), "memoryless never terminates the chain");
        for (int i = 1; i < segments.size(); i++) {
            SearchResult<GridWorld.Step> seg = segments.get(i);
            assertEquals(SearchResult.Kind.NO_PATH, seg.kind, "segment " + i);
            assertEquals(pocketTip, seg.end, "wedged at the same pocket tip (revisit)");
        }
    }

    @Test
    void sharedTableEscapesWithoutRepeatingACommit() {
        List<SearchResult<GridWorld.Step>> segments = run(new HLearningTable(), 60);

        SearchResult<GridWorld.Step> last = segments.get(segments.size() - 1);
        assertEquals(SearchResult.Kind.COMPLETE, last.kind,
                "learning must break the wedge and reach the goal, got " + last.kind
                        + " after " + segments.size() + " segments");
        assertEquals(PackedPos.pack(GOAL_X, 0, 0), last.end);

        // no committed end repeats a previous one
        LongOpenHashSet committedEnds = new LongOpenHashSet();
        for (SearchResult<GridWorld.Step> seg : segments) {
            if (seg.kind == SearchResult.Kind.PARTIAL_COMMIT) {
                assertTrue(committedEnds.add(seg.end),
                        "committed end repeated: " + PackedPos.x(seg.end) + ","
                                + PackedPos.z(seg.end));
            }
        }
        assertTrue(committedEnds.size() >= 2, "the chain genuinely moved between commits");

        // expansions drop once learning has paid for the flood: the final
        // (goal-reaching) segment is far cheaper than the budget-capped ones.
        int maxEarly = 0;
        for (int i = 0; i < segments.size() - 1; i++) {
            maxEarly = Math.max(maxEarly, segments.get(i).stats.expansions());
        }
        assertTrue(last.stats.expansions() < maxEarly,
                "final segment (" + last.stats.expansions()
                        + ") must be cheaper than the capped early ones (" + maxEarly + ")");
    }
}
