package com.dwinovo.numen.core.mining;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.mining.MiningDropTarget;
import com.dwinovo.numen.core.mining.MiningDropTarget.Mode;
import com.dwinovo.numen.core.mining.MiningGeometry.Point;
import java.util.List;

public final class MiningDropTargetTest {
    @Test
    void verifiedRuntimeBehavior() {
        MiningDropTarget pickup = new MiningDropTarget();
        require(!pickup.attempted(), "fresh pickup must not look like a failed search");
        require(pickup.beginAttempt() == Mode.BROAD, "first pickup attempt must stay broad");
        require(pickup.attempted(), "starting broad pickup must record that search was attempted");
        require(pickup.beginAttempt() == Mode.EXACT, "a missed broad attempt must become exact");
        require(pickup.beginAttempt() == Mode.EXHAUSTED, "pickup must stop after the exact attempt");
        require(pickup.beginAttempt() == Mode.EXHAUSTED, "exhausted pickup must stay stopped");
        require(pickup.exhausted(), "exhausted pickup must not create another navigation loop");
        pickup.reset();
        require(!pickup.attempted(), "a newly mined drop must clear the previous search state");
        require(pickup.beginAttempt() == Mode.BROAD, "a newly mined drop must start broad again");

        Point playerFeet = new Point(410, 68, 484);
        Point pickupFeet = MiningDropTarget.selectPickupFeet(
            List.of(
                new Point(411, 68, 486),
                new Point(420, 68, 490)
            ),
            playerFeet
        );

        require(
            pickupFeet.equals(new Point(411, 68, 486)),
            "drop collection must target the nearest item's exact block: " + pickupFeet
        );
        require(
            !pickupFeet.equals(playerFeet),
            "being about two blocks from an item must not count as pickup arrival"
        );
        require(
            MiningDropTarget.failureReason(List.of()).contains("no remaining drop could be found"),
            "a vanished drop must have an explicit terminal reason"
        );
        require(
            MiningDropTarget.failureReason(List.of(pickupFeet)).contains("411,68,486"),
            "an uncollected exact drop must report its last known coordinate"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
