package com.dwinovo.numen.core.survival;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/** Adapts live vanilla effects into the small, pure cleansing policy. */
public final class RecoveryEffects {
    public record Snapshot(boolean dangerousOngoing, boolean cleanseRecommended) {
    }

    private RecoveryEffects() {
    }

    public static Snapshot inspect(NumenPlayer player) {
        int witherTicks = 0;
        int poisonTicks = 0;
        int longestOtherHarmfulTicks = 0;
        boolean valuableBeneficialEffect = false;

        for (MobEffectInstance effect : player.getActiveEffects()) {
            int duration = effect.isInfiniteDuration() ? Integer.MAX_VALUE : effect.getDuration();
            MobEffectCategory category = effect.getEffect().value().getCategory();
            if (category == MobEffectCategory.HARMFUL) {
                if (effect.is(MobEffects.WITHER)) {
                    witherTicks = Math.max(witherTicks, duration);
                } else if (effect.is(MobEffects.POISON)) {
                    poisonTicks = Math.max(poisonTicks, duration);
                } else {
                    longestOtherHarmfulTicks = Math.max(longestOtherHarmfulTicks, duration);
                }
            } else if (category == MobEffectCategory.BENEFICIAL && duration >= 200) {
                valuableBeneficialEffect = true;
            }
        }

        boolean safeToDrink = !player.isInLava() && !player.isOnFire();
        boolean cleanseRecommended = EffectCleansingPolicy.shouldDrinkMilk(
            new EffectCleansingPolicy.State(
                safeToDrink,
                player.getHealth(),
                witherTicks,
                poisonTicks,
                longestOtherHarmfulTicks,
                valuableBeneficialEffect
            )
        );
        boolean dangerousOngoing = witherTicks >= 20 || poisonTicks >= 20;
        return new Snapshot(dangerousOngoing, cleanseRecommended);
    }
}
