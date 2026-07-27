package com.dwinovo.numen.core.combat;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.combat.ShieldCombatPolicy;

public final class ShieldCombatPolicyTest {
    @Test
    void verifiedRuntimeBehavior() {
        assertEquals(
            ShieldCombatPolicy.Decision.RAISE,
            ShieldCombatPolicy.decide(true, false, false, false),
            "an in-reach fighter with a usable shield must block while its attack is cooling down"
        );
        assertEquals(
            ShieldCombatPolicy.Decision.RELEASE,
            ShieldCombatPolicy.decide(true, false, true, true),
            "a raised shield must be released before a ready melee attack"
        );
        assertEquals(
            ShieldCombatPolicy.Decision.WAIT,
            ShieldCombatPolicy.decide(true, true, false, false),
            "combat must not interrupt eating or another non-shield use action"
        );
        assertEquals(
            ShieldCombatPolicy.Decision.HOLD,
            ShieldCombatPolicy.decide(true, false, true, false),
            "a raised shield must stay raised while the attack is still cooling down"
        );
        assertEquals(
            ShieldCombatPolicy.Decision.PROCEED,
            ShieldCombatPolicy.decide(false, false, false, false),
            "combat without a usable shield must preserve the existing attack behavior"
        );
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
