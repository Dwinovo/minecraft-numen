package com.dwinovo.numen.core.survival;

import org.junit.jupiter.api.Test;

public final class TotemSafetyPolicyTest {
    @Test
    void equipsAndRestoresOnlyTheSystemManagedTotem() {
        TotemSafetyPolicy.Decision critical = TotemSafetyPolicy.decide(
            new TotemSafetyPolicy.State(4.0f, true, false, false, false, 0)
        );
        require(
            critical.action() == TotemSafetyPolicy.Action.EQUIP
                && critical.priority() > 9.0f,
            "at two hearts a carried totem must be equipped before ordinary healing or lava escape"
        );

        TotemSafetyPolicy.Decision ongoingDamage = TotemSafetyPolicy.decide(
            new TotemSafetyPolicy.State(8.0f, true, false, true, false, 0)
        );
        require(
            ongoingDamage.action() == TotemSafetyPolicy.Action.EQUIP,
            "at four hearts, continuing lava, fire, wither, or poison danger must equip the totem early"
        );

        TotemSafetyPolicy.Decision recovered = TotemSafetyPolicy.decide(
            new TotemSafetyPolicy.State(12.0f, true, true, false, true, 100)
        );
        require(
            recovered.action() == TotemSafetyPolicy.Action.RESTORE,
            "a system-equipped totem must restore the original offhand after five safe seconds"
        );

        TotemSafetyPolicy.Decision ownerEquipped = TotemSafetyPolicy.decide(
            new TotemSafetyPolicy.State(20.0f, false, true, false, false, 200)
        );
        require(
            ownerEquipped.action() == TotemSafetyPolicy.Action.NONE,
            "a totem already placed by the owner must never be removed by automatic restoration"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
