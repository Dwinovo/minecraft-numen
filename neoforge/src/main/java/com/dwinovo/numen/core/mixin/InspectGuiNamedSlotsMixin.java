package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.inventory.OwnInventorySlots;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.dwinovo.numen.core.tools.GuiTools")
public abstract class InspectGuiNamedSlotsMixin {
    @Unique private static final Gson NUMEN$GSON = new Gson();
    @Unique private static final String NUMEN$MARKER = "named equipment and hand slots";

    @Inject(method = "inspectGui", at = @At("RETURN"), cancellable = true)
    private void numen$labelOwnEquipmentAndHands(
        NumenPlayer player,
        CallbackInfoReturnable<String> callback
    ) {
        if (player.containerMenu != player.inventoryMenu) {
            return;
        }

        JsonObject result = JsonParser.parseString(callback.getReturnValue()).getAsJsonObject();
        if (!result.has("message")) {
            return;
        }

        String message = result.get("message").getAsString();
        if (message.contains(NUMEN$MARKER)) {
            return;
        }

        int mainHand = OwnInventorySlots.mainHand(player.getInventory().getSelectedSlot());
        String namedSlots = "\n" + NUMEN$MARKER + " (empty slots are shown):\n"
            + line("head", OwnInventorySlots.HEAD, player.inventoryMenu.getSlot(OwnInventorySlots.HEAD).getItem())
            + line("chest", OwnInventorySlots.CHEST, player.inventoryMenu.getSlot(OwnInventorySlots.CHEST).getItem())
            + line("legs", OwnInventorySlots.LEGS, player.inventoryMenu.getSlot(OwnInventorySlots.LEGS).getItem())
            + line("feet", OwnInventorySlots.FEET, player.inventoryMenu.getSlot(OwnInventorySlots.FEET).getItem())
            + line("mainhand (currently selected hotbar slot)", mainHand, player.inventoryMenu.getSlot(mainHand).getItem())
            + line("offhand", OwnInventorySlots.OFFHAND, player.inventoryMenu.getSlot(OwnInventorySlots.OFFHAND).getItem())
            + "backpack destination slots: " + OwnInventorySlots.BACKPACK_START
            + "-" + OwnInventorySlots.BACKPACK_END + "\n";

        result.addProperty("message", message + namedSlots);
        callback.setReturnValue(NUMEN$GSON.toJson(result));
    }

    @Unique
    private static String line(String name, int slot, ItemStack stack) {
        return "  " + name + " slot " + slot + ": " + describe(stack) + "\n";
    }

    @Unique
    private static String describe(ItemStack stack) {
        if (stack.isEmpty()) {
            return "-";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
    }
}
