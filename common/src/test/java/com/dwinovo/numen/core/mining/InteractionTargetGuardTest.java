package com.dwinovo.numen.core.mining;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.mining.InteractionTargetGuard;

public final class InteractionTargetGuardTest {
    @Test
    void verifiedRuntimeBehavior() {
        expect(
            InteractionTargetGuard.Decision.TARGET_GONE,
            InteractionTargetGuard.decide(true, false),
            "an air target must stop without attacking the ray hit behind it"
        );
        expect(
            InteractionTargetGuard.Decision.OCCLUDED,
            InteractionTargetGuard.decide(false, false),
            "a non-air target must reject a ray hit on another block"
        );
        expect(
            InteractionTargetGuard.Decision.ATTACK_REQUESTED_TARGET,
            InteractionTargetGuard.decide(false, true),
            "only an exact ray hit may attack the requested block"
        );
    }

    private static void expect(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
