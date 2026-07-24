package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.task.survival.SurvivalDecisions;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Food-selection policy for the autonomous {@link FoodChain}: which edibles the
 * chain may pick on its own, in two tiers.
 *
 * <ul>
 *   <li>{@link Tier#NEVER} — items whose downside outweighs any refill: lethal
 *       poison, or a side effect that breaks whatever task the body is running.
 *       Excluded unconditionally.</li>
 *   <li>{@link Tier#FAMINE_ONLY} — foods with a likely harmful (but survivable)
 *       consume effect. Skipped by the regular pick; allowed only when the body is
 *       starving and nothing better is carried, because the effect is a nuisance
 *       while starving to death is not.</li>
 *   <li>{@link Tier#REGULAR} — ordinary rations, chosen by highest nutrition.</li>
 * </ul>
 *
 * <p>This policy applies ONLY to the chain's own autonomous picks. An explicit
 * eat instruction (the LLM's eat task) is the owner's call and bypasses it.
 *
 * <p>The tier arithmetic ({@link #classify(boolean, boolean)},
 * {@link #famineUnlocked}, {@link #resolveSlot}) is pure so it can be unit-tested
 * headless; only the {@link Item}/{@link FoodProperties} wrappers touch Minecraft,
 * and they are never called from the pure paths.
 */
public final class FoodPolicy {

    /**
     * A HARMFUL consume effect at or above this probability disqualifies a food
     * from the regular tier. Catches both the near-certain (rotten flesh, 80%
     * hunger) and the coin-flip-ish (raw chicken, 30% hunger); rarer effects are
     * treated as acceptable noise.
     */
    public static final float HARMFUL_EFFECT_PROBABILITY = 0.3f;

    /** Which tier a food competes in when the chain picks a meal on its own. */
    public enum Tier {
        /** Never self-fed, no matter how hungry. */
        NEVER,
        /** Competes only when starving and the regular tier is empty. */
        FAMINE_ONLY,
        /** Ordinary rations. */
        REGULAR
    }

    private FoodPolicy() {}

    /** Classification of one edible stack (Minecraft-facing entry point). The
     *  filter is a registered reflex ({@code food_policy}, constitution §6):
     *  switched off, everything edible competes as a regular ration. The pure
     *  overload below stays switch-free so the tier arithmetic tests hold. */
    public static Tier classify(net.minecraft.world.item.ItemStack stack) {
        if (!com.dwinovo.numen.task.reflex.ReflexRegistry.enabled(
                com.dwinovo.numen.core.task.reflex.CoreReflexes.FOOD_POLICY_ID)) {
            return Tier.REGULAR;
        }
        return classify(neverAutoEat(stack.getItem()), likelyHarmful(stack));
    }

    /** Pure tier arithmetic: the hard exclusion wins over everything else. */
    public static Tier classify(boolean neverAutoEat, boolean likelyHarmful) {
        if (neverAutoEat) return Tier.NEVER;
        return likelyHarmful ? Tier.FAMINE_ONLY : Tier.REGULAR;
    }

    /**
     * Items the chain never self-feeds: pufferfish (lethal poison + nausea),
     * poisonous potato and spider eye (poison for a trivial refill), and chorus
     * fruit (a random teleport that yanks the body away from whatever it was
     * doing — worse than any status effect).
     */
    public static boolean neverAutoEat(Item item) {
        return item == Items.PUFFERFISH
                || item == Items.POISONOUS_POTATO
                || item == Items.SPIDER_EYE
                || item == Items.CHORUS_FRUIT;
    }

    /**
     * True when eating carries a real chance of a harmful status effect: any
     * consume effect whose category is {@link MobEffectCategory#HARMFUL} with
     * probability &ge; {@link #HARMFUL_EFFECT_PROBABILITY}.
     */
    public static boolean likelyHarmful(net.minecraft.world.item.ItemStack stack) {
        // 1.21.2+:食用效果从 FoodProperties 搬进 CONSUMABLE 组件的 ConsumeEffect 列表。
        var consumable = stack.get(net.minecraft.core.component.DataComponents.CONSUMABLE);
        if (consumable == null) {
            return false;
        }
        for (var effect : consumable.onConsumeEffects()) {
            if (effect instanceof net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect apply
                    && apply.probability() >= HARMFUL_EFFECT_PROBABILITY) {
                for (var instance : apply.effects()) {
                    if (instance.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Pure famine gate: the {@link Tier#FAMINE_ONLY} tier unlocks only once the
     * body is genuinely starving (at or below the can't-sprint line).
     */
    public static boolean famineUnlocked(int foodLevel) {
        return foodLevel <= SurvivalDecisions.HUNGRY_LEVEL;
    }

    /**
     * Pure resolution of one inventory scan: the regular tier always wins; the
     * famine tier stands in only when the regular tier is empty AND the famine
     * gate is unlocked. Slots are -1 when the tier found nothing; returns -1 when
     * there is nothing acceptable to eat.
     */
    public static int resolveSlot(int regularSlot, int famineSlot, boolean famineUnlocked) {
        if (regularSlot >= 0) return regularSlot;
        return famineUnlocked ? famineSlot : -1;
    }
}
