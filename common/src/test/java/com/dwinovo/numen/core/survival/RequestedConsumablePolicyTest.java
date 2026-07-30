package com.dwinovo.numen.core.survival;

import org.junit.jupiter.api.Test;
import java.util.List;

public final class RequestedConsumablePolicyTest {
    @Test
    void keepsEmergencyFoodGuardedButHonorsExplicitMilk() {
        RecoveryPolicy.State healthyAndHungry = new RecoveryPolicy.State(
            17,
            14.2f,
            20.0f,
            20.0f,
            true,
            false
        );
        RecoveryPolicy.Candidate enchantedGoldenApple = RecoveryPolicy.Candidate.healing(
            0,
            RecoveryPolicy.Kind.ENCHANTED_GOLDEN_APPLE,
            16.0f
        );

        assertFalse(
            RecoveryPolicy.allowsRequestedUse(
                healthyAndHungry,
                enchantedGoldenApple,
                List.of(enchantedGoldenApple)
            ),
            "routine hunger must not authorize an enchanted golden apple"
        );

        RecoveryPolicy.Candidate riskyFood = RecoveryPolicy.Candidate.food(
            1,
            RecoveryPolicy.Value.RISKY,
            4,
            1.0f
        );
        assertFalse(
            RecoveryPolicy.allowsRequestedUse(
                healthyAndHungry,
                riskyFood,
                List.of(riskyFood)
            ),
            "risky food must stay locked outside famine"
        );

        RecoveryPolicy.State criticalHealth = new RecoveryPolicy.State(
            17,
            14.2f,
            4.0f,
            20.0f,
            true,
            false
        );
        assertTrue(
            RecoveryPolicy.allowsRequestedUse(
                criticalHealth,
                enchantedGoldenApple,
                List.of(enchantedGoldenApple)
            ),
            "the emergency item selected by active healing must remain usable"
        );

        RecoveryPolicy.State needsCleanse = new RecoveryPolicy.State(
            20,
            10.0f,
            16.0f,
            20.0f,
            true,
            false,
            true
        );
        RecoveryPolicy.Candidate milk = RecoveryPolicy.Candidate.cleansing(3);
        assertTrue(
            RecoveryPolicy.allowsRequestedUse(needsCleanse, milk, List.of(milk)),
            "milk selected for an automatic cleanse must remain manually usable"
        );
        assertTrue(
            RecoveryPolicy.allowsRequestedUse(healthyAndHungry, milk, List.of(milk)),
            "an explicit player request must allow drinking milk without a negative effect"
        );
        assertTrue(
            RecoveryPolicy.decide(healthyAndHungry, List.of(milk)).mode()
                == RecoveryPolicy.Mode.NONE,
            "allowing an explicit milk request must not make automatic recovery waste milk"
        );
    }

    private static void assertFalse(boolean actual, String message) {
        if (actual) {
            throw new AssertionError(message);
        }
    }

    private static void assertTrue(boolean actual, String message) {
        if (!actual) {
            throw new AssertionError(message);
        }
    }
}
