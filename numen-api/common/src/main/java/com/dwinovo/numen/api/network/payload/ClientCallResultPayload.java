package com.dwinovo.numen.api.network.payload;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.FriendlyByteBuf;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.network.Wire;
import com.dwinovo.numen.api.program.NetworkTransport;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Client-to-server payload: the answer to a {@link ClientCallPayload}, the call's result in the shape every API call
 * returns ({@code ApiReply}). A result can be as long as what the function read on the client, so the payload is
 * {@link Wire.Fragmentable}.
 */
public record ClientCallResultPayload(UUID entityUuid, String callId, String replyJson)
        implements CustomPacketPayload, Wire.Fragmentable {

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "client_call_result");

    /** 编码后的字节数。 */
    public int size() {
        return Wire.size(this);
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(entityUuid);
        Wire.writeText(buf, callId);
        Wire.writeText(buf, replyJson);
    }

    public static ClientCallResultPayload read(FriendlyByteBuf buf) {
        return new ClientCallResultPayload(buf.readUUID(), Wire.readText(buf), Wire.readText(buf));
    }

    /** Handler invoked on the server main thread. */
    public static void handle(ClientCallResultPayload p, ServerPlayer player) {
        NetworkTransport.INSTANCE.answered(player.getUUID(), p);
    }
}
