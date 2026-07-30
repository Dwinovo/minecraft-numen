package com.dwinovo.numen.core.survival;

/** Decides whether milk's loss of every beneficial effect is worth the cleanse. */
public final class EffectCleansingPolicy {
    public record State(
        boolean safeToDrink,
        float health,
        int witherTicks,
        int poisonTicks,
        int longestOtherHarmfulTicks,
        boolean valuableBeneficialEffect
    ) {
    }

    private EffectCleansingPolicy() {
    }

    public static boolean shouldDrinkMilk(State state) {
        if (!state.safeToDrink()) {
            return false;
        }
        return state.witherTicks() >= 20
            || (state.poisonTicks() >= 60 && state.health() <= 12.0f)
            || (state.longestOtherHarmfulTicks() >= 600
                && !state.valuableBeneficialEffect());
    }
}
