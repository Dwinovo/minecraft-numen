package com.dwinovo.numen.pathing.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwinovo.numen.pathing.search.SearchClock;

import org.junit.jupiter.api.Test;

/** 一趟的总预算与每次搜索的时限:总预算与估价成正比、用完收场、一次搜索再没有进展也要花预算;时限按首段、接续、加时重试定。不经 Driver。 */
class AllowanceTest {

    private static final SearchClock CLOCK = SearchClock.counting(10_000);

    @Test
    void theTotalGrowsWithTheEstimate() {
        Allowance near = new Allowance(0, CLOCK);
        near.begun(30);
        Allowance far = new Allowance(0, CLOCK);
        far.begun(1000);
        assertEquals(Allowance.SEARCH_NODES, drain(near), "近处的也至少够搜满一次");
        assertEquals(1000 * Allowance.NODES_PER_TICK, drain(far), "远的按估价给");
    }

    @Test
    void aSpecifiedTotalIsNotRescaledByTheEstimate() {
        Allowance fixed = new Allowance(500, CLOCK);
        fixed.begun(100_000);
        assertEquals(500, drain(fixed));
    }

    @Test
    void aSearchThatExpandedNothingStillCosts() {
        Allowance allowance = new Allowance(2, CLOCK);
        allowance.spend(0);
        assertFalse(allowance.spent());
        allowance.spend(0);
        assertTrue(allowance.spent(), "原地不动的搜索也要花预算,不然会一直搜下去");
    }

    @Test
    void aSearchNeverExpandsMoreThanWhatIsLeft() {
        Allowance allowance = new Allowance(Allowance.SEARCH_NODES + 10, CLOCK);
        assertEquals(Allowance.SEARCH_NODES, allowance.nodes());
        allowance.spend(Allowance.SEARCH_NODES);
        assertEquals(10, allowance.nodes());
    }

    @Test
    void restartingGivesTheNewGoalItsOwnTotal() {
        Allowance allowance = new Allowance(0, CLOCK);
        allowance.begun(1000);
        allowance.spend(5);
        allowance.restart();
        allowance.begun(30);
        assertEquals(Allowance.SEARCH_NODES, drain(allowance));
    }

    @Test
    void theNextSearchThinksAsLongAsTheBodyTakesToWalkWhatIsLeft() {
        Allowance allowance = new Allowance(0, CLOCK);
        assertEquals(Allowance.FIRST_NANOS, allowance.first().nanos());
        assertEquals(40 * Allowance.TICK_NANOS - Allowance.MARGIN_NANOS, allowance.next(40).nanos(), "走完剩下的路之前搜完");
        assertEquals(Allowance.NEXT_MAX_NANOS, allowance.next(10_000).nanos(), "封顶");
        assertEquals(Allowance.FIRST_NANOS, allowance.next(1).nanos(), "不低于首段");
    }

    @Test
    void aSearchThatHandsNothingOverMakesTheNextOneThinkLonger() {
        Allowance allowance = new Allowance(0, CLOCK);
        allowance.missed();
        assertEquals(2 * Allowance.FIRST_NANOS, allowance.first().nanos());
        allowance.missed();
        assertEquals(4 * Allowance.FIRST_NANOS, allowance.first().nanos());
        for (int i = 0; i < 30; i++) {
            allowance.missed();
        }
        assertEquals(Allowance.NEXT_MAX_NANOS, allowance.first().nanos(), "加时也有顶");
        allowance.found();
        assertEquals(Allowance.FIRST_NANOS, allowance.first().nanos(), "交出了路线,时限回到原样");
    }

    /** 一次花一个节点,数能花多少个才用完。 */
    private static int drain(Allowance allowance) {
        int n = 0;
        while (!allowance.spent() && n < 10_000_000) {
            allowance.spend(1);
            n++;
        }
        return n;
    }
}
