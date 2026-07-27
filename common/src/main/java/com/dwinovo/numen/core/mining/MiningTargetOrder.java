package com.dwinovo.numen.core.mining;

import com.dwinovo.numen.core.mining.MiningGeometry.Point;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Chooses the nearest structural frontier without depending on scan insertion order. */
public final class MiningTargetOrder {
    private record Column(int x, int z) {
    }

    private MiningTargetOrder() {
    }

    public static Point select(List<Point> targets, Point origin) {
        if (targets == null || targets.isEmpty() || origin == null) {
            return null;
        }

        Map<Column, Point> lowestByColumn = new LinkedHashMap<>();
        for (Point target : targets) {
            Column column = new Column(target.x(), target.z());
            lowestByColumn.merge(
                column,
                target,
                (current, candidate) -> candidate.y() < current.y() ? candidate : current
            );
        }

        return lowestByColumn.values().stream()
            .min(Comparator
                .comparingDouble(origin::distanceSquared)
                .thenComparingInt(Point::y)
                .thenComparingInt(Point::x)
                .thenComparingInt(Point::z))
            .orElse(null);
    }
}
