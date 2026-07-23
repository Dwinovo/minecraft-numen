package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.task.BodyLog;
import com.dwinovo.numen.task.reflex.Reflex;

import com.dwinovo.numen.core.task.SurvivalConfig;
import com.dwinovo.numen.task.TaskChain;
import com.dwinovo.numen.core.task.survival.SurvivalDecisions;
import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Autonomous auto-eat survival chain. Polls the vanilla {@code FoodData} each tick;
 * when the body is hungry (or hurt and not full) AND is carrying something edible
 * that {@link FoodPolicy} allows the chain to pick on its own, it spikes above the
 * LLM task, holds the most nourishing acceptable food, and drives a native
 * held-use eat — mirroring {@code EatCompanionTask} (the body's own {@code aiStep}
 * finishes the chew, applying hunger / saturation / consume-effects) — then drops
 * back to dormant once fed or out of food.
 *
 * <p>Drives the {@link Interaction} primitive directly rather than wrapping an
 * {@code AbstractCompanionTask}: an eat is a single held-use with no nav, no
 * sub-goals and no {@code TaskResult}, so the base class's recovery/result
 * machinery buys nothing here. The only cross-tick state is the in-flight eat
 * handle, held as a field.
 *
 * <p>GATED OFF by default via {@link SurvivalConfig}: with the gate off,
 * {@link #getPriority} short-circuits to {@link Float#NEGATIVE_INFINITY} before
 * touching the body, so the chain is a strict no-op.
 */
public final class FoodChain implements TaskChain, com.dwinovo.numen.task.reflex.Reflex {

    /** BodyLog for completed episodes — dual-rail routed (may be null in unit tests). */
    private final com.dwinovo.numen.task.BodyLog bodyLog;

    /** The in-flight native eat (held use), or {@code null} between eats. */
    private Interaction eat;
    /** What's being chewed + hunger at the first bite, for the diary line. */
    private String eatingLabel;
    private int eatingStartFood;

    public FoodChain() {
        this(null);
    }

    public FoodChain(com.dwinovo.numen.task.BodyLog bodyLog) {
        this.bodyLog = bodyLog;
    }

    @Override
    public float getPriority(NumenPlayer companion) {
        if (!SurvivalConfig.enabled()) return Float.NEGATIVE_INFINITY;
        if (!com.dwinovo.numen.task.reflex.ReflexRegistry.enabled(id())) {
            return SurvivalDecisions.DORMANT;   // reflex switched off by the owner
        }
        // Never preempt a body already using an item UNLESS it's our own in-flight
        // eat: the LLM may be mid-eat (whose before/after item accounting a hand
        // swap would corrupt) or drawing a bow. Our own chew must keep priority,
        // or the spike would drop mid-bite and the eat could never finish.
        if (eat == null && companion.isUsingItem()) return Float.NEGATIVE_INFINITY;
        int foodLevel = companion.getFoodData().getFoodLevel();
        float health = companion.getHealth();
        boolean hasEdible = bestEdibleSlot(companion) >= 0;
        return SurvivalDecisions.foodPriority(foodLevel, health, hasEdible);
    }

    @Override
    public void tick(NumenPlayer companion) {
        if (eat == null) {
            int slot = bestEdibleSlot(companion);
            if (slot < 0) return;   // priority-gated; belt-and-braces
            companion.holdInHand(slot);
            eatingLabel = companion.getInventory().getItem(slot).getHoverName().getString();
            eatingStartFood = companion.getFoodData().getFoodLevel();
            eat = Interaction.useInAir(companion, InteractionHand.MAIN_HAND, Interaction.Timing.hold());
        }
        switch (eat.tick()) {
            case RUNNING -> { /* still chewing */ }
            case DONE, FAILED -> {
                // Finished (or declined) one item; release. If still hungry, priority
                // stays up and the next tick starts a fresh eat; else we go dormant.
                // Diary only a REAL meal (hunger actually rose — a declined/instant DONE
                // with no effect isn't an episode).
                if (bodyLog != null
                        && companion.getFoodData().getFoodLevel() > eatingStartFood) {
                    bodyLog.report("got hungry and ate a " + eatingLabel);
                }
                eat.stop();
                eat = null;
            }
        }
    }

    @Override
    public void onInterrupt(NumenPlayer companion) {
        if (eat != null) {
            eat.stop();
            eat = null;
        }
    }

    @Override
    public String name() {
        return "food";
    }

    // ---- Reflex roster paperwork (constitution §6) ----

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "饿了或受伤时会自己吃背包里的食物";
    }

    /**
     * Slot of the most nourishing ACCEPTABLE edible in the whole inventory
     * (highest {@link FoodProperties#nutrition}), or -1 if nothing acceptable is
     * carried. "Edible" is the native consumable test ({@link DataComponents#FOOD}
     * present), matching {@code EatCompanionTask} — covers food, modded
     * consumables, milk. On top of that, {@link FoodPolicy} filters what the chain
     * may pick on its own: hard-excluded items never, likely-harmful foods only
     * when the body is starving and carries nothing better.
     *
     * <p>One scan buckets every edible into the two tiers; {@link #getPriority}
     * and {@link #tick} both call this single method, so "priority says there is
     * food" and "tick picks a slot" can never disagree.
     */
    private static int bestEdibleSlot(NumenPlayer companion) {
        Inventory inv = companion.getInventory();
        int bestRegular = -1;
        int bestRegularNutrition = -1;
        int bestFamine = -1;
        int bestFamineNutrition = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null) continue;
            switch (FoodPolicy.classify(stack)) {
                case NEVER -> { /* excluded unconditionally */ }
                case FAMINE_ONLY -> {
                    if (food.nutrition() > bestFamineNutrition) {
                        bestFamineNutrition = food.nutrition();
                        bestFamine = i;
                    }
                }
                case REGULAR -> {
                    if (food.nutrition() > bestRegularNutrition) {
                        bestRegularNutrition = food.nutrition();
                        bestRegular = i;
                    }
                }
            }
        }
        boolean famine = FoodPolicy.famineUnlocked(companion.getFoodData().getFoodLevel());
        return FoodPolicy.resolveSlot(bestRegular, bestFamine, famine);
    }
}
