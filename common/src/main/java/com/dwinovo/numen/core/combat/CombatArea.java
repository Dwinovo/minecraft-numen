package com.dwinovo.numen.core.combat;

import java.util.Locale;

/** Fixed combat boundary captured when nearby targets are scanned. */
public record CombatArea(
    double centerX,
    double centerY,
    double centerZ,
    double horizontalRadius,
    double verticalRange,
    Origin origin,
    LevelScope levelScope
) {
    public static final double SAME_PLANE_VERTICAL_RANGE = 2.0;

    public enum Origin {
        SELF,
        OWNER;

        public static Origin parse(String value) {
            if (value == null || value.isBlank()) {
                return SELF;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "self" -> SELF;
                case "owner" -> OWNER;
                default -> throw new IllegalArgumentException("origin must be self or owner");
            };
        }
    }

    public enum LevelScope {
        SAME_PLANE,
        ALL;

        public static LevelScope parse(String value) {
            if (value == null || value.isBlank()) {
                return SAME_PLANE;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "same_plane" -> SAME_PLANE;
                case "all" -> ALL;
                default -> throw new IllegalArgumentException("level_scope must be same_plane or all");
            };
        }
    }

    public static CombatArea samePlane(double x, double y, double z, double horizontalRadius) {
        return samePlane(x, y, z, horizontalRadius, Origin.SELF);
    }

    public static CombatArea samePlane(double x, double y, double z, double horizontalRadius, Origin origin) {
        return new CombatArea(
            x,
            y,
            z,
            horizontalRadius,
            SAME_PLANE_VERTICAL_RANGE,
            origin,
            LevelScope.SAME_PLANE
        );
    }

    public static CombatArea allHeights(double x, double y, double z, double horizontalRadius) {
        return allHeights(x, y, z, horizontalRadius, Origin.SELF);
    }

    public static CombatArea allHeights(double x, double y, double z, double horizontalRadius, Origin origin) {
        return new CombatArea(x, y, z, horizontalRadius, horizontalRadius, origin, LevelScope.ALL);
    }

    public boolean contains(double x, double y, double z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return (dx * dx) + (dz * dz) <= horizontalRadius * horizontalRadius
            && Math.abs(y - centerY) <= verticalRange;
    }
}
