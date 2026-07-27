package com.dwinovo.numen.core.combat;

public final class ShieldCombatPolicy {
    public enum Decision {
        PROCEED,
        WAIT,
        RAISE,
        HOLD,
        RELEASE
    }

    private ShieldCombatPolicy() {
    }

    public static Decision decide(
        boolean shieldUsable,
        boolean usingOtherItem,
        boolean shieldRaised,
        boolean attackReady
    ) {
        if (usingOtherItem) {
            return Decision.WAIT;
        }
        if (shieldRaised) {
            return attackReady ? Decision.RELEASE : Decision.HOLD;
        }
        if (shieldUsable && !attackReady) {
            return Decision.RAISE;
        }
        return Decision.PROCEED;
    }
}
