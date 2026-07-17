package com.dwinovo.numen.core.pathing.engine;

import it.unimi.dsi.fastutil.longs.LongSets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T9 — the pillar forest, memorylessly impossible: a vertical climb is
 * committed, the body EXECUTES part of it (the scaffold cells become real,
 * cheap terrain), then execution fails and the body lands one cell to the
 * side. The re-search must climb the built tower again — the world itself is
 * the only memory, and the built route wins on real cost.
 *
 * <p>History: the live incident (four abandoned 6-block towers in 3 seconds
 * around one crafting table) was driven by the since-retired learned-h table —
 * its only-ever-rising entries went stale once the body's own scaffolds
 * LOWERED true costs, steering every re-search onto a fresh unpenalised
 * column. With the table gone, this test pins the invariant that makes the
 * forest structurally impossible: deterministic re-pricing of the as-built
 * world resumes the same attempt instead of drifting sideways.
 */
class PillarForestTest {

    private static final int TOWER_X = 5;   // the column the first attempt builds
    private static final int GOAL_Y = 14;

    /** Open sky over flat ground; climbing costs 3× lateral (the place-and-jump price). */
    private static GridWorld world() {
        return new GridWorld()
                .bounds(0, 0, 0, 12, GOAL_Y, 0)
                .verticalCostMultiplier(3.0);
    }

    private static Heuristic heuristic() {
        return GridWorld.weightedOctileHeuristic(TOWER_X, GOAL_Y, 0, 1.5, 1.0, 1.3);
    }

    /** A budget small enough that the climb can't complete — the search must commit partway up. */
    private static SearchBudget budget() {
        return SearchBudget.of(8, 36);
    }

    private static SearchResult<GridWorld.Step> search(GridWorld w, long start) {
        return new PathSearch<>(start, w, heuristic(), GridWorld.exactGoal(TOWER_X, GOAL_Y, 0),
                budget(), LongSets.EMPTY_SET, PathSearch.Config.standard()).run();
    }

    /** The scaffold landed: climbing the built cells now costs ~a lateral step, not a place. */
    private static void buildTower(GridWorld w, int upTo) {
        for (int y = 1; y <= upTo; y++) {
            w.cost(TOWER_X, y, 0, 1.0 / 3.0);   // × the 3.0 vertical multiplier ≈ 1.0
        }
    }

    @Test
    void reSearchReclimbsTheBuiltTower() {
        GridWorld w = world();
        SearchResult<GridWorld.Step> first = search(w, PackedPos.pack(TOWER_X, 0, 0));
        assertEquals(SearchResult.Kind.PARTIAL_COMMIT, first.kind, "climb must be budget-split");
        assertEquals(TOWER_X, PackedPos.x(first.end), "attempt 1 commits up its own column");
        assertTrue(PackedPos.y(first.end) >= 5, "attempt 1 gains real height");

        buildTower(w, PackedPos.y(first.end));
        // Execution failed; the body fell and landed one cell to the side.
        SearchResult<GridWorld.Step> retry = search(w, PackedPos.pack(TOWER_X + 1, 0, 0));

        assertEquals(SearchResult.Kind.PARTIAL_COMMIT, retry.kind);
        GridWorld.assertChain(retry);
        boolean reusesTower = GridWorld.positions(retry).longStream()
                .anyMatch(p -> PackedPos.x(p) == TOWER_X && PackedPos.y(p) >= 2);
        assertTrue(reusesTower, "the retry must route up the built tower");
        assertEquals(TOWER_X, PackedPos.x(retry.end), "the commit itself climbs the tower column");
    }
}
