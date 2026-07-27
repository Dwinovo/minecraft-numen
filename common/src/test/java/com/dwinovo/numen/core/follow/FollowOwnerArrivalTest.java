package com.dwinovo.numen.core.follow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowOwnerArrivalTest {

    @Test
    void arrivesWithinFourBlocksOnTheSameFloor() {
        assertTrue(FollowOwnerArrival.hasArrived(0.0, 64, 0.0, 4.0, 64, 0.0));
        assertTrue(FollowOwnerArrival.hasArrived(0.0, 64, 0.0, 3.0, 65, 0.0));
    }

    @Test
    void keepsFollowingOutsideTheHorizontalRadiusOrOnAnotherFloor() {
        assertFalse(FollowOwnerArrival.hasArrived(0.0, 64, 0.0, 4.01, 64, 0.0));
        assertFalse(FollowOwnerArrival.hasArrived(0.0, 64, 0.0, 1.0, 66, 0.0));
    }
}
