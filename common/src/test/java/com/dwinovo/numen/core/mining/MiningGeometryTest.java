package com.dwinovo.numen.core.mining;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.mining.MiningGeometry;
import com.dwinovo.numen.core.mining.MiningGeometry.Point;
import java.util.List;

public final class MiningGeometryTest {
    @Test
    void verifiedRuntimeBehavior() {
        Point target = new Point(10, 70, 10);

        expectTrue(
            MiningGeometry.withinReach(6.5, 71.62, 10.5, target),
            "a target four blocks to the side must be reachable"
        );
        expectTrue(
            MiningGeometry.withinReach(10.5, 74.62, 10.5, target),
            "a target below the player must be reachable"
        );
        expectFalse(
            MiningGeometry.withinReach(5.5, 71.62, 10.5, target),
            "a target five blocks to the side must not be reachable"
        );
        expectFalse(
            MiningGeometry.withinStableMiningReach(6.0, 70.5, 10.5, target),
            "the theoretical 4.5-block limit must not start unstable direct mining"
        );
        expectTrue(
            MiningGeometry.withinStableMiningReach(7.0, 70.5, 10.5, target),
            "a target 3.5 blocks from the player's feet may start direct mining"
        );

        List<Point> candidates = MiningGeometry.candidateFeet(target);
        expectContains(candidates, new Point(9, 70, 10), "west side stance");
        expectContains(candidates, new Point(11, 70, 10), "east side stance");
        expectContains(candidates, new Point(10, 70, 9), "north side stance");
        expectContains(candidates, new Point(10, 70, 11), "south side stance");
        expectContains(candidates, new Point(9, 69, 10), "lower stance for elevated targets");
        expectContains(candidates, new Point(8, 70, 10), "two-block horizontal stance");
        expectNotContains(
            candidates,
            new Point(7, 70, 10),
            "planned stance must stay within a two-block horizontal radius"
        );

        Point elevatedTarget = new Point(10, 73, 10);
        List<Point> elevatedCandidates = MiningGeometry.candidateFeet(elevatedTarget);
        expectNotContains(
            elevatedCandidates,
            new Point(9, 70, 10),
            "ground stance outside stable mining reach must not block scaffolding"
        );
        expectContains(
            elevatedCandidates,
            new Point(9, 71, 10),
            "one-block elevated scaffold stance must remain available"
        );

        for (Point candidate : candidates) {
            if (candidate.x() == target.x() && candidate.z() == target.z()) {
                throw new AssertionError("candidate stances must not force entry into the target column: " + candidate);
            }
        }

        List<Point> preferred = MiningGeometry.closestHorizontalRing(
            List.of(
                new Point(8, 70, 10),
                new Point(9, 70, 10),
                new Point(9, 70, 9)
            ),
            target
        );
        expectContains(preferred, new Point(9, 70, 10), "adjacent cardinal stance");
        expectContains(preferred, new Point(9, 70, 9), "adjacent diagonal stance");
        expectNotContains(preferred, new Point(8, 70, 10), "outer stance when adjacency exists");

        List<Point> outerFallback = MiningGeometry.closestHorizontalRing(
            List.of(new Point(8, 70, 10)),
            target
        );
        expectContains(outerFallback, new Point(8, 70, 10), "outer stance fallback");

        expectTrue(
            MiningGeometry.withinHorizontalRing(new Point(11, 70, 10), target, 1),
            "adjacent cardinal position may start mining"
        );
        expectTrue(
            MiningGeometry.withinHorizontalRing(new Point(11, 70, 11), target, 1),
            "adjacent diagonal position may start mining"
        );
        expectFalse(
            MiningGeometry.withinHorizontalRing(new Point(12, 70, 10), target, 1),
            "attack reach alone must not start mining outside the selected ring"
        );
        expectTrue(
            MiningGeometry.withinHorizontalRing(new Point(12, 70, 10), target, 2),
            "outer ring may start mining only when selected as fallback"
        );
    }

    private static void expectContains(List<Point> values, Point expected, String message) {
        if (!values.contains(expected)) {
            throw new AssertionError(message + " missing from " + values);
        }
    }

    private static void expectNotContains(List<Point> values, Point rejected, String message) {
        if (values.contains(rejected)) {
            throw new AssertionError(message + " unexpectedly contained in " + values);
        }
    }

    private static void expectTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void expectFalse(boolean value, String message) {
        if (value) {
            throw new AssertionError(message);
        }
    }
}
