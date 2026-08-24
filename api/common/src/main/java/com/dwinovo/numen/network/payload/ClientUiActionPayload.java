package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.network.NumenPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → Client: ask the receiving owner's client to perform a local UI action.
 * The {@code /numen} command tree lives entirely on the server now (so it
 * doesn't collide with the client-side command dispatcher), but a couple of its
 * verbs — opening the settings GUI, clearing the conversation loops — are
 * inherently client-local. The server command just fires this packet at the
 * caller and their client does the rest.
 */
public record ClientUiActionPayload(Action action) implements NumenPayload {

    public enum Action { OPEN_SETTINGS, RESET_LOOPS, DEBUG_TEXT_ON, DEBUG_TEXT_OFF }

    public static final ResourceLocation ID =
            new ResourceLocation(Constants.MOD_ID, "client_ui_action");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(action.ordinal());
    }

    public static ClientUiActionPayload read(FriendlyByteBuf buf) {
        return new ClientUiActionPayload(Action.values()[buf.readVarInt()]);
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(ClientUiActionPayload p) {
        com.dwinovo.numen.network.ClientPayloadSink.uiAction.accept(p);
    }
}
