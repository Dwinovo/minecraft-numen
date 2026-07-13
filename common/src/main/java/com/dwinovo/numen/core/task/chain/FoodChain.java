package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.task.SurvivalConfig;
import com.dwinovo.numen.core.task.TaskChain;
import com.dwinovo.numen.core.task.survival.SurvivalDecisions;
import com.dwinovo.numen.core.pathing.exec.Interaction;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Autonomous auto-eat survival chain. Polls the vanilla {@code FoodData} each tick;
 * when the body is hungry (or hurt and not full) AND is carrying something edible,
 * it spikes above the LLM task, holds the most nourishing food, and drives a native
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
public final class FoodChain implements TaskChain {

    /** The in-flight native eat (held use), or {@code null} between eats. */
    private Interaction eat;

    @Override
    public float getPriority(NumenPlayer companion) {
        if (!SurvivalConfig.enabled()) return Float.NEGATIVE_INFINITY;
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
            eat = Interaction.useInAir(companion, InteractionHand.MAIN_HAND, Interaction.Timing.hold());
        }
        switch (eat.tick()) {
            case RUNNING -> { /* still chewing */ }
            case DONE, FAILED -> {
                // Finished (or declined) one item; release. If still hungry, priority
                // stays up and the next tick starts a fresh eat; else we go dormant.
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

    /**
     * Slot of the most nourishing edible in the whole inventory (highest
     * {@link FoodProperties#nutrition}), or -1 if the body carries nothing edible.
     * "Edible" is the native consumable test ({@link DataComponents#FOOD} present),
     * matching {@code EatCompanionTask} — covers food, modded consumables, milk.
     */
    private static int bestEdibleSlot(NumenPlayer companion) {
        Inventory inv = companion.getInventory();
        int best = -1;
        int bestNutrition = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null) continue;
            if (food.nutrition() > bestNutrition) {
                bestNutrition = food.nutrition();
                best = i;
            }
        }
        return best;
    }
}
