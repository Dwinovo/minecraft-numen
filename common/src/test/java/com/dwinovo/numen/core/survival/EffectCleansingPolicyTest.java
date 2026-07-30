package com.dwinovo.numen.core.survival;

import org.junit.jupiter.api.Test;

public final class EffectCleansingPolicyTest {
    @Test
    void usesMilkOnlyWhenAutomaticCleansingIsWorthTheTradeoff() {
        require(
            EffectCleansingPolicy.shouldDrinkMilk(
                new EffectCleansingPolicy.State(true, 16.0f, 100, 0, 0, false)
            ),
            "a continuing wither effect must be cleansed when it is safe to finish drinking"
        );

        require(
            EffectCleansingPolicy.shouldDrinkMilk(
                new EffectCleansingPolicy.State(true, 10.0f, 0, 100, 0, false)
            ),
            "poison that is still draining an injured companion must be cleansed"
        );

        require(
            EffectCleansingPolicy.shouldDrinkMilk(
                new EffectCleansingPolicy.State(true, 20.0f, 0, 0, 600, false)
            ),
            "a long harmful effect may be cleansed when no valuable beneficial effect would be lost"
        );

        require(
            !EffectCleansingPolicy.shouldDrinkMilk(
                new EffectCleansingPolicy.State(false, 6.0f, 200, 0, 0, false)
            ),
            "milk must not be started in lava or while burning"
        );
        require(
            !EffectCleansingPolicy.shouldDrinkMilk(
                new EffectCleansingPolicy.State(true, 20.0f, 0, 0, 600, true)
            ),
            "a non-damaging effect must not erase a valuable long beneficial effect"
        );
        require(
            !EffectCleansingPolicy.shouldDrinkMilk(
                new EffectCleansingPolicy.State(true, 20.0f, 0, 0, 100, false)
            ),
            "a short nuisance effect must expire naturally instead of wasting milk"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
