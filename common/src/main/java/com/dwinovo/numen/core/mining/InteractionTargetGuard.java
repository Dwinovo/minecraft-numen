package com.dwinovo.numen.core.mining;

public final class InteractionTargetGuard {
    public enum Decision {
        ATTACK_REQUESTED_TARGET,
        TARGET_GONE,
        OCCLUDED
    }

    private InteractionTargetGuard() {
    }

    public static Decision decide(boolean targetIsAir, boolean rayHitRequestedTarget) {
        if (targetIsAir) {
            return Decision.TARGET_GONE;
        }
        return rayHitRequestedTarget ? Decision.ATTACK_REQUESTED_TARGET : Decision.OCCLUDED;
    }
}
