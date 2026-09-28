package com.dwinovo.numen.pathing.search;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.pathing.plan.Threat;

import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 环形站位与远离一组生物这两个目标的估价往哪边引:太近往外、太远往里;离生物越近越贵、几只相加、半径越大越贵。
 * 到达本身由 {@code SearchTest} 在搜索里验。
 */
class GoalsTest {

    private static final BlockPos CENTER = new BlockPos(0, 64, 0);

    @Test
    void aRingLeadsOutwardWhenTooCloseAndInwardWhenTooFar() {
        Goal ring = Goals.ring(CENTER, 3, 5);
        assertTrue(ring.estimate(1, 64, 0) > ring.estimate(2, 64, 0), "太近:往外更便宜");
        assertTrue(ring.estimate(9, 64, 0) > ring.estimate(8, 64, 0), "太远:往里更便宜");
        assertEquals(0, ring.estimate(4, 64, 0));
    }

    @Test
    void aRingIsHorizontalOnly() {
        Goal ring = Goals.ring(CENTER, 3, 5);
        assertTrue(ring.contains(4, 90, 0, null));
        assertEquals(ring.estimate(4, 64, 0), ring.estimate(4, 90, 0));
    }

    @Test
    void aRingWhoseInnerEdgeIsOutsideItsOuterEdgeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Goals.ring(CENTER, 5, 3));
    }

    @Test
    void closerToACreatureIsAlwaysDearer() {
        Goal away = Goals.awayFrom(List.of(new Threat(0.5, 64, 0.5, 3)));
        double previous = Double.POSITIVE_INFINITY;
        for (int x = 1; x <= 10; x++) {
            double here = away.estimate(x, 64, 0);
            assertTrue(here < previous, "x=" + x);
            previous = here;
        }
    }

    @Test
    void theDangerOfSeveralCreaturesAddsUp() {
        Threat left = new Threat(-3.5, 64, 0.5, 3);
        Threat right = new Threat(4.5, 64, 0.5, 3);
        double one = Goals.awayFrom(List.of(left)).estimate(0, 64, 0);
        double both = Goals.awayFrom(List.of(left, right)).estimate(0, 64, 0);
        assertTrue(both > one, "两只一左一右,直穿哪一只都不便宜");
    }

    @Test
    void aWiderDangerRadiusIsDearerAtTheSameDistance() {
        double small = Goals.awayFrom(List.of(new Threat(0.5, 64, 0.5, 2))).estimate(5, 64, 0);
        double wide = Goals.awayFrom(List.of(new Threat(0.5, 64, 0.5, 6))).estimate(5, 64, 0);
        assertTrue(wide > small);
    }

    @Test
    void getAwayMeansOutsideEveryRadiusAndHeightIsNoSafety() {
        Goal away = Goals.awayFrom(List.of(new Threat(0.5, 64, 0.5, 3), new Threat(10.5, 64, 0.5, 5)));
        assertFalse(away.contains(2, 64, 0, null), "在第一只的半径里");
        assertFalse(away.contains(7, 64, 0, null), "出了第一只的,还在第二只的半径里");
        assertTrue(away.contains(4, 64, -6, null));
        assertFalse(away.contains(1, 90, 0, null), "站高了也不算安全");
    }

    @Test
    void getAwayNeedsSomethingToGetAwayFromAndRadiiAreNotNegative() {
        assertThrows(IllegalArgumentException.class, () -> Goals.awayFrom(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Threat(0, 64, 0, -1));
    }
}
