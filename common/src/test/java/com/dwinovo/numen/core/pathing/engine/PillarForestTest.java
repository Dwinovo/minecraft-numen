package com.dwinovo.numen.core.pathing.engine;

import it.unimi.dsi.fastutil.longs.LongSets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T9 — the pillar forest: a vertical climb is committed, the body EXECUTES part
 * of it (the scaffold cells become real, cheap terrain), then execution fails
 * and the body lands one cell to the side. What must the re-search do?
 *
 * <p>The learned table only ever RAISES h, but the half-built tower LOWERED the
 * true cost of exactly those cells — a re-search still consulting the old table
 * sees the built column as poisoned and commits up a fresh, unpenalised column
 * instead. Every failed attempt then repeats this one column over, planting a
 * forest of abandoned towers (observed live: four 6-block towers in 3 seconds
 * around one crafting table). A re-search on a FRESH table re-prices the world
 * as it now is and climbs the cheap, already-built tower again.
 *
 * <p>This is the engine-level contract behind {@code PlayerNav}'s restart
 * policy: EXEC_FAILURE swaps in a fresh table; only healthy segment chains
 * (SEGMENT_DONE) keep it. The companion tests ({@link OscillationTest},
 * {@link DeepWellTest}) pin why those chains MUST keep it.
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

    private static GoalPredicate goal() {
        return GridWorld.exactGoal(TOWER_X, GOAL_Y, 0);
    }

    /** A budget small enough that the climb can't complete — the search must commit partway up. */
    private static SearchBudget budget() {
        return SearchBudget.of(8, 36);
    }

    private static SearchResult<GridWorld.Step> search(GridWorld w, long start, HLearningTable table) {
        return new PathSearch<>(start, w, heuristic(), goal(), budget(), table,
                LongSets.EMPTY_SET, PathSearch.Config.standard()).run();
    }

    /** Attempt 1 commits up the tower column and (being partial) writes its pessimism back. */
    private static SearchResult<GridWorld.Step> firstAttempt(GridWorld w, HLearningTable table) {
        SearchResult<GridWorld.Step> first = search(w, PackedPos.pack(TOWER_X, 0, 0), table);
        assertEquals(SearchResult.Kind.PARTIAL_COMMIT, first.kind, "climb must be budget-split");
        assertEquals(TOWER_X, PackedPos.x(first.end), "attempt 1 commits up its own column");
        assertTrue(PackedPos.y(first.end) >= 5, "attempt 1 gains real height");
        return first;
    }

    /** The scaffold landed: climbing the built cells now costs ~a lateral step, not a place. */
    private static void buildTower(GridWorld w, int upTo) {
        for (int y = 1; y <= upTo; y++) {
            w.cost(TOWER_X, y, 0, 1.0 / 3.0);   // × the 3.0 vertical multiplier ≈ 1.0
        }
    }

    /**
     * The staleness itself: attempt 1's write-back priced the climb against the
     * UNBUILT world, and the table can only hold or raise those values — so once
     * the scaffold lands, every learned entry on the tower over-estimates the
     * now-cheap cells. HOW FAR that pessimism repels the next commit scales with
     * how widely the failed search flooded (huge in the live incident, mild in a
     * toy grid) — {@link OscillationTest} pins the repulsion mechanism itself —
     * but the over-estimate is unconditional, and it is why an EXEC_FAILURE
     * restart must not consult this table.
     */
    @Test
    void retainedTableOverpricesTheBuiltTower() {
        GridWorld w = world();
        HLearningTable table = new HLearningTable();
        SearchResult<GridWorld.Step> first = firstAttempt(w, table);

        buildTower(w, PackedPos.y(first.end));

        Heuristic base = heuristic();
        int poisoned = 0;
        for (int y = 1; y <= PackedPos.y(first.end); y++) {
            long cell = PackedPos.pack(TOWER_X, y, 0);
            double learned = table.learned(cell);
            if (learned == Double.NEGATIVE_INFINITY) continue;   // no entry for this cell
            assertTrue(learned > base.estimate(cell),
                    "a write-back entry only exists when it raises h (y=" + y + ")");
            poisoned++;
        }
        assertTrue(poisoned > 0, "attempt 1's write-back must have priced the tower column — "
                + "if it no longer does, the EXEC_FAILURE table swap in PlayerNav.restartFresh "
                + "may be obsolete; re-derive before deleting");
    }

    @Test
    void freshTableReclimbsTheBuiltTower() {
        GridWorld w = world();
        HLearningTable table = new HLearningTable();
        SearchResult<GridWorld.Step> first = firstAttempt(w, table);

        buildTower(w, PackedPos.y(first.end));
        long drifted = PackedPos.pack(TOWER_X + 1, 0, 0);

        SearchResult<GridWorld.Step> retry = search(w, drifted, new HLearningTable());   // FRESH
        assertEquals(SearchResult.Kind.PARTIAL_COMMIT, retry.kind);
        GridWorld.assertChain(retry);
        // Deterministic re-pricing of the real world: the built tower is the cheap
        // route, so the same attempt resumes instead of drifting sideways.
        boolean reusesTower = GridWorld.positions(retry).longStream()
                .anyMatch(p -> PackedPos.x(p) == TOWER_X && PackedPos.y(p) >= 2);
        assertTrue(reusesTower, "fresh table must route the retry up the built tower");
        assertEquals(TOWER_X, PackedPos.x(retry.end), "the commit itself climbs the tower column");
    }
}
