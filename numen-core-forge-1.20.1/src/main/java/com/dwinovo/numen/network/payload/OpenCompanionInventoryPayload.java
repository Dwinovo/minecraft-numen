package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.inventory.CompanionInventoryMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraftforge.network.NetworkHooks;

import java.util.UUID;

/** Opens the server-authoritative native inventory menu for an owned companion. */
public record OpenCompanionInventoryPayload(UUID uuid) {
    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "open_companion_inventory");

    public void encode(FriendlyByteBuf buf) { buf.writeUUID(uuid); }
    public static OpenCompanionInventoryPayload decode(FriendlyByteBuf buf) {
        return new OpenCompanionInventoryPayload(buf.readUUID());
    }

    public static void handle(OpenCompanionInventoryPayload payload, ServerPlayer owner) {
        NumenPlayer body = NumenPlayer.findByUuid(owner.level.getServer(), payload.uuid());
        if (body == null || !body.isOwnedByPlayer(owner.getUUID())) return;
        NetworkHooks.openScreen(owner, new SimpleMenuProvider(
                        (containerId, inventory, player) ->
                                new CompanionInventoryMenu(containerId, inventory, payload.uuid(), body),
                        Component.literal(body.getName().getString() + " - 背包")),
                buf -> buf.writeUUID(payload.uuid()));
    }
}
