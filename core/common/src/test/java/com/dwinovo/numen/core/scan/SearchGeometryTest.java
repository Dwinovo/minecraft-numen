package com.dwinovo.numen.core.scan;

import com.dwinovo.numen.core.scan.SearchGeometry.NearestBound;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchGeometryTest {

    // ==================== section 访问序 ====================

    @Test
    void theLayerYouAreStandingOnComesFirst() {
        assertEquals(4, SearchGeometry.sectionOrder(0, 8, 4)[0]);
    }

    @Test
    void itFansOutDownwardFirstThenUpward() {
        assertArrayEquals(new int[]{4, 3, 5, 2, 6, 1, 7, 0, 8},
                SearchGeometry.sectionOrder(0, 8, 4));
    }

    @Test
    void aCentreAboveTheRangeIsPulledToTheTopLayer() {
        assertArrayEquals(new int[]{3, 2, 1, 0}, SearchGeometry.sectionOrder(0, 3, 99));
    }

    @Test
    void aCentreBelowTheRangeIsPulledToTheBottomLayer() {
        assertArrayEquals(new int[]{-4, -3, -2}, SearchGeometry.sectionOrder(-4, -2, -99));
    }

    /** A reorder that drops or repeats a layer would silently lose terrain. */
    @Test
    void everyLayerIsVisitedExactlyOnce() {
        int[] order = SearchGeometry.sectionOrder(-4, 19, 4);
        int[] sorted = order.clone();
        Arrays.sort(sorted);
        assertArrayEquals(IntStream.rangeClosed(-4, 19).toArray(), sorted);
    }

    @Test
    void anEmptyRangeOrdersNothing() {
        assertEquals(0, SearchGeometry.sectionOrder(5, 4, 5).length);
    }

    @Test
    void aSingleLayerRangeIsJustThatLayer() {
        assertArrayEquals(new int[]{7}, SearchGeometry.sectionOrder(7, 7, 7));
    }

    // ==================== 环的距离下界 ====================

    @Test
    void theCentreRingHasNoFloor() {
        assertEquals(0.0, SearchGeometry.ringFloorDistance(0));
    }

    /** Centre can sit 15 blocks into its own chunk, so ring 1 can be one block away. */
    @Test
    void theNextRingCanBeAsCloseAsOneBlock() {
        assertEquals(1.0, SearchGeometry.ringFloorDistance(1));
    }

    @Test
    void theFloorGrowsByAChunkPerRing() {
        assertEquals(17.0, SearchGeometry.ringFloorDistance(2));
        assertEquals(177.0, SearchGeometry.ringFloorDistance(12));
    }

    // ==================== 收工判据 ====================

    @Test
    void aSearchThatHasNotFilledItsQuotaKeepsGoing() {
        NearestBound bound = new NearestBound(3);
        bound.offer(1.0);
        bound.offer(2.0);
        assertFalse(SearchGeometry.canStop(0, bound));
    }

    @Test
    void aFullQuotaStillKeepsGoingWhileTheNextRingCouldBeatIt() {
        NearestBound bound = new NearestBound(2);
        bound.offer(40.0);
        bound.offer(50.0);
        // ring 1 floor is 17 for the next ring — 50 is worse than that, keep walking.
        assertFalse(SearchGeometry.canStop(1, bound));
    }

    @Test
    void aFullQuotaCloserThanTheNextRingFloorStops() {
        NearestBound bound = new NearestBound(2);
        bound.offer(3.0);
        bound.offer(9.0);
        // Next ring (2) cannot produce anything nearer than 17.
        assertTrue(SearchGeometry.canStop(1, bound));
    }

    @Test
    void wantingNothingNeverStopsOnThisRule() {
        assertFalse(SearchGeometry.canStop(5, new NearestBound(0)));
    }

    // ==================== 距离上界本身 ====================

    @Test
    void theBoundKeepsTheNearestOnesAndReportsTheWorstOfThem() {
        NearestBound bound = new NearestBound(3);
        for (double d : new double[]{80.0, 5.0, 60.0, 1.0, 70.0, 2.0}) {
            bound.offer(d);
        }
        assertTrue(bound.full());
        assertEquals(5.0, bound.worst());   // nearest three are 1, 2, 5
    }

    @Test
    void anUnfilledBoundIsUnbounded() {
        NearestBound bound = new NearestBound(4);
        bound.offer(1.0);
        assertFalse(bound.full());
        assertEquals(Double.POSITIVE_INFINITY, bound.worst());
    }
}
