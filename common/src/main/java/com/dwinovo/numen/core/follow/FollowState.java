package com.dwinovo.numen.core.follow;

/**
 * The persisted follow intent for one companion.
 *
 * <p>This record deliberately contains no owner identity or runtime navigation
 * state. The companion UUID is the key in {@link FollowStateStore}; the owner
 * remains authoritative in Numen's existing player data and registry.
 */
public record FollowState(
        boolean enabled,
        boolean manualPaused,
        int schemaVersion,
        Double stopDistanceOverride,
        Double startDistanceOverride) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public FollowState {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported follow state schema " + schemaVersion);
        }
        validateDistance("stopDistanceOverride", stopDistanceOverride);
        validateDistance("startDistanceOverride", startDistanceOverride);
        if (stopDistanceOverride != null && startDistanceOverride != null
                && stopDistanceOverride >= startDistanceOverride) {
            throw new IllegalArgumentException("stop distance must be less than start distance");
        }
    }

    /** Safe intent for a companion with no saved follow entry. */
    public static FollowState defaults() {
        return new FollowState(false, false, CURRENT_SCHEMA_VERSION, null, null);
    }

    public FollowState withEnabled(boolean value) {
        return new FollowState(value, manualPaused, schemaVersion,
                stopDistanceOverride, startDistanceOverride);
    }

    public FollowState withManualPaused(boolean value) {
        return new FollowState(enabled, value, schemaVersion,
                stopDistanceOverride, startDistanceOverride);
    }

    public FollowState withDistanceOverrides(Double stopDistance, Double startDistance) {
        return new FollowState(enabled, manualPaused, schemaVersion,
                stopDistance, startDistance);
    }

    private static void validateDistance(String name, Double value) {
        if (value != null && (!Double.isFinite(value) || value <= 0.0)) {
            throw new IllegalArgumentException(name + " must be a finite positive number");
        }
    }
}
