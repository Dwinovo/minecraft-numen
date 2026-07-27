package com.dwinovo.numen.core.mining;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.mining.MiningGeometry.Point;
import com.dwinovo.numen.core.mining.MiningTargetOrder;
import java.util.List;

public final class MiningTargetOrderTest {
    @Test
    void verifiedRuntimeBehavior() {
        Point selected = MiningTargetOrder.select(
            List.of(
                new Point(10, 73, 10),
                new Point(10, 68, 10),
                new Point(10, 70, 10)
            ),
            new Point(11, 73, 10)
        );

        require(
            selected.equals(new Point(10, 68, 10)),
            "a vertical column must start at its lowest remaining block: " + selected
        );

        Point nearbyFrontier = MiningTargetOrder.select(
            List.of(
                new Point(40, 60, 40),
                new Point(40, 61, 40),
                new Point(13, 70, 10),
                new Point(13, 72, 10),
                new Point(11, 71, 15)
            ),
            new Point(10, 70, 10)
        );
        require(
            nearbyFrontier.equals(new Point(13, 70, 10)),
            "unrelated structures must compete by live distance between their lowest frontiers: "
                + nearbyFrontier
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
