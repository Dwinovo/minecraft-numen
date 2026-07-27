package com.dwinovo.numen.core.scaffold;

/** Pure counterfactual decision used before any temporary block is mined. */
public final class ScaffoldRemovalSafety {
    public enum Action {
        REMOVE,
        KEEP,
        FORGET
    }

    public record Context(
        boolean worldStateKnown,
        boolean placedBlockStillMatches,
        boolean supportsPlayer,
        boolean requiredByCurrentOrNextPath,
        boolean landingKnown,
        boolean hazardousLanding,
        int fallDistance,
        boolean onlyKnownRetreat,
        boolean reachableForRemoval
    ) {
    }

    public record Decision(Action action, String reason) {
    }

    private ScaffoldRemovalSafety() {
    }

    public static Decision evaluate(Context context) {
        if (!context.worldStateKnown()) {
            return new Decision(Action.KEEP, "chunk_not_loaded");
        }
        if (!context.placedBlockStillMatches()) {
            return new Decision(Action.FORGET, "placed_block_changed");
        }
        if (context.requiredByCurrentOrNextPath()) {
            return new Decision(Action.KEEP, "required_by_active_path");
        }
        if (context.supportsPlayer() && context.hazardousLanding()) {
            return new Decision(Action.KEEP, "supports_ai_over_hazard");
        }
        if (context.supportsPlayer()) {
            return new Decision(Action.KEEP, "currently_supports_ai");
        }
        if (!context.landingKnown()) {
            return new Decision(Action.KEEP, "landing_not_known");
        }
        if (context.hazardousLanding()) {
            return new Decision(Action.KEEP, "hazard_below");
        }
        if (context.fallDistance() > 3) {
            return new Decision(Action.KEEP, "unsafe_fall_below");
        }
        if (context.onlyKnownRetreat()) {
            return new Decision(Action.KEEP, "only_known_retreat");
        }
        if (!context.reachableForRemoval()) {
            return new Decision(Action.KEEP, "currently_out_of_reach");
        }
        return new Decision(Action.REMOVE, "safe_to_remove");
    }

    public static boolean canNavigateForRemoval(Context context) {
        if (context.reachableForRemoval()) {
            return false;
        }
        Context fromReachableStance = new Context(
            context.worldStateKnown(),
            context.placedBlockStillMatches(),
            context.supportsPlayer(),
            context.requiredByCurrentOrNextPath(),
            context.landingKnown(),
            context.hazardousLanding(),
            context.fallDistance(),
            context.onlyKnownRetreat(),
            true
        );
        return evaluate(fromReachableStance).action() == Action.REMOVE;
    }
}
