package com.dwinovo.numen.core.mining;

import com.dwinovo.numen.core.mining.MiningGeometry.Point;
import java.util.Comparator;
import java.util.List;

/** Escalates drop collection from a broad goal to one bounded exact fallback. */
public final class MiningDropTarget {
    public enum Mode {
        BROAD,
        EXACT,
        EXHAUSTED
    }

    private Mode next = Mode.BROAD;
    private boolean attempted;

    public Mode beginAttempt() {
        this.attempted = true;
        Mode attempt = this.next;
        this.next = switch (attempt) {
            case BROAD -> Mode.EXACT;
            case EXACT, EXHAUSTED -> Mode.EXHAUSTED;
        };
        return attempt;
    }

    public boolean exhausted() {
        return this.next == Mode.EXHAUSTED;
    }

    public boolean attempted() {
        return this.attempted;
    }

    public void reset() {
        this.next = Mode.BROAD;
        this.attempted = false;
    }

    public static Point selectPickupFeet(List<Point> drops, Point origin) {
        if (drops == null || drops.isEmpty() || origin == null) {
            return null;
        }
        return drops.stream()
            .min(Comparator
                .comparingDouble(origin::distanceSquared)
                .thenComparingInt(Point::y)
                .thenComparingInt(Point::x)
                .thenComparingInt(Point::z))
            .orElse(null);
    }

    public static String failureReason(List<Point> drops) {
        if (drops == null || drops.isEmpty()) {
            return "no remaining drop could be found after one broad and one exact pickup attempt";
        }
        List<String> positions = drops.stream()
            .distinct()
            .map(point -> point.x() + "," + point.y() + "," + point.z())
            .toList();
        return "remaining drop(s) could not be collected after one broad and one exact pickup attempt; "
            + "last known positions: " + positions;
    }
}
