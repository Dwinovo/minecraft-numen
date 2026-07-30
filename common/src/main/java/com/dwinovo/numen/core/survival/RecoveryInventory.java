package com.dwinovo.numen.core.survival;

import com.dwinovo.numen.core.task.chain.FoodPolicy;
import com.dwinovo.numen.entity.NumenPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodConstants;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.gamerules.GameRules;

public final class RecoveryInventory {
    private static final int ADEQUATE_REGENERATION_TICKS = 40;

    private RecoveryInventory() {
    }

    public static RecoveryPolicy.Decision decide(NumenPlayer player) {
        return RecoveryPolicy.decide(state(player), candidates(player.getInventory()));
    }

    public static int selectedRequestedSlot(NumenPlayer player, Item requestedItem) {
        Inventory inventory = player.getInventory();
        if (requestedItem != Items.POTION) {
            return firstSlot(inventory, requestedItem);
        }

        RecoveryPolicy.Decision decision = decide(player);
        int slot = decision.slot();
        boolean expectedMode = decision.mode() == RecoveryPolicy.Mode.ACTIVE_HEALING;
        return expectedMode
            && slot >= 0
            && slot < inventory.getContainerSize()
            && inventory.getItem(slot).is(requestedItem)
                ? slot
                : -1;
    }

    public static String requestedUseRefusal(NumenPlayer player, Item requestedItem) {
        Inventory inventory = player.getInventory();
        int requestedSlot = firstSlot(inventory, requestedItem);
        if (requestedSlot < 0) {
            return null;
        }

        if (requestedItem == Items.POTION) {
            return selectedRequestedSlot(player, requestedItem) >= 0
                ? null
                : "kept potion: no safe healing potion is currently selected by the recovery policy";
        }

        List<RecoveryPolicy.Candidate> candidates = candidates(inventory);
        RecoveryPolicy.Candidate requested = null;
        for (RecoveryPolicy.Candidate candidate : candidates) {
            if (candidate.slot() == requestedSlot) {
                requested = candidate;
                break;
            }
        }
        if (requested == null
            || RecoveryPolicy.allowsRequestedUse(state(player), requested, candidates)) {
            return null;
        }

        String label = BuiltInRegistries.ITEM.getKey(requestedItem).getPath();
        if (requested.kind() != RecoveryPolicy.Kind.FOOD) {
            return "kept " + label
                + ": emergency healing items are used only when the recovery policy selects them";
        }
        return switch (requested.value()) {
            case VALUABLE -> "kept " + label + ": use an ordinary safe food first";
            case RISKY -> "kept " + label
                + ": risky food is allowed only at hunger 6 or lower when no safe food is available";
            case FORBIDDEN -> "kept " + label + ": this unsafe food is never allowed";
            case ORDINARY -> null;
        };
    }

    private static int firstSlot(Inventory inventory, Item item) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                return slot;
            }
        }
        return -1;
    }

    private static RecoveryPolicy.State state(NumenPlayer player) {
        FoodData food = player.getFoodData();
        RecoveryEffects.Snapshot effects = RecoveryEffects.inspect(player);
        MobEffectInstance regeneration = player.getEffect(MobEffects.REGENERATION);
        boolean regenerationActive = regeneration != null
            && (regeneration.isInfiniteDuration()
                || regeneration.getDuration() > ADEQUATE_REGENERATION_TICKS);
        return new RecoveryPolicy.State(
            food.getFoodLevel(),
            food.getSaturationLevel(),
            player.getHealth(),
            player.getMaxHealth(),
            player.level().getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION),
            regenerationActive,
            effects.cleanseRecommended()
        );
    }

    private static List<RecoveryPolicy.Candidate> candidates(Inventory inventory) {
        List<RecoveryPolicy.Candidate> candidates = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(Items.GOLDEN_APPLE)) {
                candidates.add(
                    RecoveryPolicy.Candidate.healing(
                        slot,
                        RecoveryPolicy.Kind.GOLDEN_APPLE,
                        4.0f
                    )
                );
                continue;
            }
            if (stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
                candidates.add(
                    RecoveryPolicy.Candidate.healing(
                        slot,
                        RecoveryPolicy.Kind.ENCHANTED_GOLDEN_APPLE,
                        16.0f
                    )
                );
                continue;
            }
            if (stack.is(Items.MILK_BUCKET)) {
                candidates.add(RecoveryPolicy.Candidate.cleansing(slot));
                continue;
            }
            if (stack.is(Items.POTION)) {
                addHealingPotion(candidates, slot, stack);
                continue;
            }

            FoodProperties properties = stack.get(DataComponents.FOOD);
            if (properties == null) {
                continue;
            }
            RecoveryPolicy.Value value = foodValue(stack);
            candidates.add(
                RecoveryPolicy.Candidate.food(
                    slot,
                    value,
                    properties.nutrition(),
                    FoodConstants.saturationByModifier(
                        properties.nutrition(),
                        properties.saturation()
                    )
                )
            );
        }
        return candidates;
    }

    private static RecoveryPolicy.Value foodValue(ItemStack stack) {
        FoodPolicy.Tier tier = FoodPolicy.classify(stack);
        if (tier == FoodPolicy.Tier.NEVER) {
            return RecoveryPolicy.Value.FORBIDDEN;
        }
        if (tier == FoodPolicy.Tier.FAMINE_ONLY) {
            return RecoveryPolicy.Value.RISKY;
        }
        return stack.is(Items.GOLDEN_CARROT)
            ? RecoveryPolicy.Value.VALUABLE
            : RecoveryPolicy.Value.ORDINARY;
    }

    private static void addHealingPotion(
        List<RecoveryPolicy.Candidate> candidates,
        int slot,
        ItemStack stack
    ) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return;
        }

        float instantHealing = 0.0f;
        float regenerationHealing = 0.0f;
        for (MobEffectInstance effect : contents.getAllEffects()) {
            if (effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
                return;
            }
            if (effect.is(MobEffects.INSTANT_HEALTH)) {
                instantHealing += 4.0f * (1 << Math.min(effect.getAmplifier(), 5));
            } else if (effect.is(MobEffects.REGENERATION)) {
                int interval = Math.max(1, 50 >> Math.min(effect.getAmplifier(), 5));
                regenerationHealing += Math.max(1, effect.getDuration() / interval);
            }
        }
        if (instantHealing > 0.0f) {
            candidates.add(
                RecoveryPolicy.Candidate.healing(
                    slot,
                    RecoveryPolicy.Kind.INSTANT_HEALTH,
                    instantHealing
                )
            );
        } else if (regenerationHealing > 0.0f) {
            candidates.add(
                RecoveryPolicy.Candidate.healing(
                    slot,
                    RecoveryPolicy.Kind.REGENERATION,
                    regenerationHealing
                )
            );
        }
    }
}
