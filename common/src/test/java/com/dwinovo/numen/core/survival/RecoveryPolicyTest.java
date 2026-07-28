package com.dwinovo.numen.core.survival;

import java.util.List;
import org.junit.jupiter.api.Test;

public final class RecoveryPolicyTest {
    @Test
    void selectsFoodByUsableNutritionSaturationAndValue() {
        RecoveryPolicy.State routine = state(14, 0.0f, 20.0f, true, false);
        equal(
            1,
            RecoveryPolicy.selectFoodSlot(
                RecoveryPolicy.Mode.MAINTENANCE,
                routine,
                List.of(
                    RecoveryPolicy.Candidate.food(0, RecoveryPolicy.Value.ORDINARY, 8, 12.8f),
                    RecoveryPolicy.Candidate.food(1, RecoveryPolicy.Value.ORDINARY, 6, 7.2f)
                )
            ),
            "routine food must avoid nutrition overflow before maximizing saturation"
        );

        equal(
            3,
            RecoveryPolicy.selectFoodSlot(
                RecoveryPolicy.Mode.NATURAL_REGEN,
                state(14, 0.0f, 9.0f, true, false),
                List.of(
                    RecoveryPolicy.Candidate.food(2, RecoveryPolicy.Value.ORDINARY, 6, 7.2f),
                    RecoveryPolicy.Candidate.food(3, RecoveryPolicy.Value.ORDINARY, 8, 12.8f)
                )
            ),
            "injury recovery must prefer usable saturation after reaching full hunger"
        );

        equal(
            4,
            RecoveryPolicy.selectFoodSlot(
                RecoveryPolicy.Mode.MAINTENANCE,
                state(6, 0.0f, 20.0f, true, false),
                List.of(
                    RecoveryPolicy.Candidate.food(4, RecoveryPolicy.Value.ORDINARY, 2, 1.2f),
                    RecoveryPolicy.Candidate.food(5, RecoveryPolicy.Value.VALUABLE, 6, 14.4f)
                )
            ),
            "ordinary food must preserve valuable food even when another meal will be needed"
        );

        equal(
            -1,
            RecoveryPolicy.selectFoodSlot(
                RecoveryPolicy.Mode.MAINTENANCE,
                state(7, 0.0f, 20.0f, true, false),
                List.of(RecoveryPolicy.Candidate.food(6, RecoveryPolicy.Value.RISKY, 4, 1.0f))
            ),
            "risky food must stay locked outside famine"
        );
        equal(
            6,
            RecoveryPolicy.selectFoodSlot(
                RecoveryPolicy.Mode.MAINTENANCE,
                state(6, 0.0f, 20.0f, true, false),
                List.of(RecoveryPolicy.Candidate.food(6, RecoveryPolicy.Value.RISKY, 4, 1.0f))
            ),
            "risky food must remain available as the final famine fallback"
        );
    }

    @Test
    void healsActivelyWithoutWastingEmergencyItems() {
        RecoveryPolicy.Decision routine = RecoveryPolicy.decide(
            state(6, 0.0f, 20.0f, true, false),
            List.of(
                RecoveryPolicy.Candidate.healing(7, RecoveryPolicy.Kind.GOLDEN_APPLE, 4.0f),
                RecoveryPolicy.Candidate.food(8, RecoveryPolicy.Value.ORDINARY, 5, 6.0f)
            )
        );
        equal(RecoveryPolicy.Mode.MAINTENANCE, routine.mode(), "routine hunger must not become healing");
        equal(8, routine.slot(), "routine hunger must preserve golden apples");

        RecoveryPolicy.Decision critical = RecoveryPolicy.decide(
            state(16, 2.0f, 6.0f, true, false),
            List.of(
                RecoveryPolicy.Candidate.healing(9, RecoveryPolicy.Kind.REGENERATION, 8.0f),
                RecoveryPolicy.Candidate.healing(10, RecoveryPolicy.Kind.INSTANT_HEALTH, 4.0f)
            )
        );
        equal(RecoveryPolicy.Mode.ACTIVE_HEALING, critical.mode(), "critical health must actively heal");
        equal(10, critical.slot(), "instant health must precede slower regeneration at critical health");

        RecoveryPolicy.Decision alreadyRegenerating = RecoveryPolicy.decide(
            state(20, 5.0f, 6.0f, true, true),
            List.of(
                RecoveryPolicy.Candidate.healing(11, RecoveryPolicy.Kind.REGENERATION, 8.0f),
                RecoveryPolicy.Candidate.healing(12, RecoveryPolicy.Kind.GOLDEN_APPLE, 4.0f)
            )
        );
        equal(
            RecoveryPolicy.Mode.NONE,
            alreadyRegenerating.mode(),
            "an adequate regeneration effect must prevent another healing item from being wasted"
        );
    }

    @Test
    void guardsRequestedEmergencyAndRiskyConsumables() {
        RecoveryPolicy.State healthy = state(17, 14.2f, 20.0f, true, false);
        RecoveryPolicy.Candidate enchantedApple = RecoveryPolicy.Candidate.healing(
            0,
            RecoveryPolicy.Kind.ENCHANTED_GOLDEN_APPLE,
            16.0f
        );
        require(
            !RecoveryPolicy.allowsRequestedUse(healthy, enchantedApple, List.of(enchantedApple)),
            "routine hunger must not authorize an enchanted golden apple"
        );

        RecoveryPolicy.Candidate riskyFood = RecoveryPolicy.Candidate.food(
            1,
            RecoveryPolicy.Value.RISKY,
            4,
            1.0f
        );
        require(
            !RecoveryPolicy.allowsRequestedUse(healthy, riskyFood, List.of(riskyFood)),
            "requested risky food must remain locked outside famine"
        );

        require(
            RecoveryPolicy.allowsRequestedUse(
                state(17, 14.2f, 4.0f, true, false),
                enchantedApple,
                List.of(enchantedApple)
            ),
            "the emergency item selected by active healing must remain usable"
        );
    }

    private static RecoveryPolicy.State state(
        int food,
        float saturation,
        float health,
        boolean naturalRegeneration,
        boolean regenerationActive
    ) {
        return new RecoveryPolicy.State(
            food,
            saturation,
            health,
            20.0f,
            naturalRegeneration,
            regenerationActive
        );
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
