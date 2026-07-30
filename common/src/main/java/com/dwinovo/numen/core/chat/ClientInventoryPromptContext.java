package com.dwinovo.numen.core.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.dwinovo.numen.client.data.ClientNumenInventory;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;

/** Formats the server-authoritative inventory snapshot already carried by Numen's inventory payload. */
public final class ClientInventoryPromptContext {
    private final InventoryPromptContext context = new InventoryPromptContext();

    public String refresh(UUID entityUuid, AbstractClientPlayer player) {
        ClientNumenInventory.Snapshot snapshot = ClientNumenInventory.get(entityUuid).orElse(null);
        if (snapshot == null || !snapshot.loaded()) {
            return "";
        }
        return this.context.refresh(
            snapshot,
            0,
            handSignature(player),
            () -> capture(snapshot, player)
        );
    }

    private static InventoryPromptContext.Snapshot capture(
        ClientNumenInventory.Snapshot snapshot,
        AbstractClientPlayer player
    ) {
        List<InventoryPromptContext.Stack> slots = new ArrayList<>(snapshot.items().size());
        for (ItemStack stack : snapshot.items()) {
            if (!stack.isEmpty()) {
                slots.add(stack(stack));
            }
        }
        return new InventoryPromptContext.Snapshot(
            stack(player == null ? ItemStack.EMPTY : player.getMainHandItem()),
            stack(player == null ? ItemStack.EMPTY : player.getOffhandItem()),
            slots
        );
    }

    private static int handSignature(AbstractClientPlayer player) {
        if (player == null) {
            return 0;
        }
        return 31 * stackKey(player.getMainHandItem()).hashCode()
            + stackKey(player.getOffhandItem()).hashCode();
    }

    private static String stackKey(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "minecraft:air";
        }
        return stackLabel(stack) + "x" + stack.getCount();
    }

    private static InventoryPromptContext.Stack stack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new InventoryPromptContext.Stack("minecraft:air", 0);
        }
        return new InventoryPromptContext.Stack(
            stackLabel(stack),
            stack.getCount()
        );
    }

    private static String stackLabel(ItemStack stack) {
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return itemId;
        }

        boolean harmful = false;
        boolean instantHealth = false;
        boolean regeneration = false;
        for (MobEffectInstance effect : contents.getAllEffects()) {
            harmful |= effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL;
            instantHealth |= effect.is(MobEffects.INSTANT_HEALTH);
            regeneration |= effect.is(MobEffects.REGENERATION);
        }
        return PotionPromptLabel.decorate(itemId, harmful, instantHealth, regeneration);
    }
}
