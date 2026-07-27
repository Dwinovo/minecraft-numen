package com.dwinovo.numen.core.mining;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MiningGeometry {
    private static final double REACH = 4.5;
    private static final double REACH_SQUARED = REACH * REACH;
    private static final double STABLE_MINING_REACH = 3.5;
    private static final double STABLE_MINING_REACH_SQUARED =
        STABLE_MINING_REACH * STABLE_MINING_REACH;
    private static final int MAX_HORIZONTAL_STANCE_RADIUS = 2;
    private static final int MAX_HORIZONTAL_STANCE_RADIUS_SQUARED =
        MAX_HORIZONTAL_STANCE_RADIUS * MAX_HORIZONTAL_STANCE_RADIUS;

    public record Point(int x, int y, int z) {
        public double distanceSquared(Point other) {
            double dx = this.x - other.x;
            double dy = this.y - other.y;
            double dz = this.z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private MiningGeometry() {
    }

    public static boolean withinReach(double eyeX, double eyeY, double eyeZ, Point target) {
        double dx = eyeX - (target.x + 0.5);
        double dy = eyeY - (target.y + 0.5);
        double dz = eyeZ - (target.z + 0.5);
        return dx * dx + dy * dy + dz * dz <= REACH_SQUARED;
    }

    public static boolean withinStableMiningReach(
        double feetX,
        double feetY,
        double feetZ,
        Point target
    ) {
        double dx = feetX - (target.x + 0.5);
        double dy = feetY - (target.y + 0.5);
        double dz = feetZ - (target.z + 0.5);
        return dx * dx + dy * dy + dz * dz <= STABLE_MINING_REACH_SQUARED;
    }

    public static List<Point> candidateFeet(Point target) {
        List<Point> candidates = new ArrayList<>();
        for (int dy = -4; dy <= 2; dy++) {
            for (int dx = -MAX_HORIZONTAL_STANCE_RADIUS;
                 dx <= MAX_HORIZONTAL_STANCE_RADIUS;
                 dx++) {
                for (int dz = -MAX_HORIZONTAL_STANCE_RADIUS;
                     dz <= MAX_HORIZONTAL_STANCE_RADIUS;
                     dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    if (dx * dx + dz * dz > MAX_HORIZONTAL_STANCE_RADIUS_SQUARED) {
                        continue;
                    }
                    Point feet = new Point(target.x + dx, target.y + dy, target.z + dz);
                    if (withinStableMiningReach(
                        feet.x + 0.5,
                        feet.y,
                        feet.z + 0.5,
                        target
                    )) {
                        candidates.add(feet);
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(target::distanceSquared));
        return List.copyOf(candidates);
    }

    public static List<Point> closestHorizontalRing(List<Point> candidates, Point target) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        int closestRing = candidates.stream()
            .mapToInt(candidate -> horizontalRingDistance(candidate, target))
            .min()
            .orElseThrow();
        return candidates.stream()
            .filter(candidate -> horizontalRingDistance(candidate, target) == closestRing)
            .toList();
    }

    public static boolean withinHorizontalRing(Point feet, Point target, int maximumRing) {
        if (maximumRing < 0) {
            throw new IllegalArgumentException("maximumRing must be non-negative");
        }
        return horizontalRingDistance(feet, target) <= maximumRing;
    }

    private static int horizontalRingDistance(Point candidate, Point target) {
        return Math.max(
            Math.abs(candidate.x - target.x),
            Math.abs(candidate.z - target.z)
        );
    }
}
