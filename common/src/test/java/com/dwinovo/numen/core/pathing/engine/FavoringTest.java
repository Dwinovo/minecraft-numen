package com.dwinovo.numen.core.pathing.engine;

import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T11 — previous-route favoring: an H-shaped map with two exactly equal-cost
 * corridors (north at z=+2, south at z=-2). A favored set covering one
 * corridor discounts edges into it (x favoringCoefficient), so the returned
 * path must take THAT corridor — run both ways to prove it is the favoring,
 * not tie-breaking accident.
 */
class FavoringTest {

    private static final int GOAL_X = 10;

    private static GridWorld world() {
        GridWorld w = new GridWorld().bounds(-2, 0, -4, 12, 0, 4).defaultWall(true);
        // vertical connectors at both ends
        w.carveBox(0, 0, -2, 0, 0, 2);
        w.carveBox(GOAL_X, 0, -2, GOAL_X, 0, 2);
        // north corridor (z=+2) and south corridor (z=-2), equal length
        w.carveBox(0, 0, 2, GOAL_X, 0, 2);
        w.carveBox(0, 0, -2, GOAL_X, 0, -2);
        return w;
    }

    private static LongSet corridor(int z) {
        LongOpenHashSet favored = new LongOpenHashSet();
        for (int x = 0; x <= GOAL_X; x++) {
            favored.add(PackedPos.pack(x, 0, z));
        }
        // include the connector cells leading into/out of the corridor
        favored.add(PackedPos.pack(0, 0, z / 2));
        favored.add(PackedPos.pack(GOAL_X, 0, z / 2));
        return favored;
    }

    private static SearchResult<GridWorld.Step> run(LongSet favored) {
        return new PathSearch<>(PackedPos.pack(0, 0, 0), world(),
                GridWorld.weightedOctileHeuristic(GOAL_X, 0, 0, 1.5, 1.0, 1.0),
                GridWorld.exactGoal(GOAL_X, 0, 0), SearchBudget.of(10_000, 10_000), favored, PathSearch.Config.standard()).run();
    }

    private static void assertUsesCorridor(SearchResult<GridWorld.Step> result, int z) {
        assertEquals(SearchResult.Kind.COMPLETE, result.kind);
        GridWorld.assertChain(result);
        assertEquals(14, result.edges.size(), "both corridors are 14 steps");
        LongList positions = GridWorld.positions(result);
        assertTrue(positions.contains(PackedPos.pack(5, 0, z)),
                "must travel the favored corridor z=" + z);
        assertFalse(positions.contains(PackedPos.pack(5, 0, -z)),
                "must not touch the unfavored corridor z=" + (-z));
    }

    @Test
    void favoringNorthCorridorRoutesNorth() {
        assertUsesCorridor(run(corridor(2)), 2);
    }

    @Test
    void favoringSouthCorridorRoutesSouth() {
        assertUsesCorridor(run(corridor(-2)), -2);
    }
}
