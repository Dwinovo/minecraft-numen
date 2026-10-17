package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.network.Wire;
import com.dwinovo.numen.api.program.NetworkTransport;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
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

    public static final Type<ClientCallResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "client_call_result"));

    public static final StreamCodec<ByteBuf, ClientCallResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ClientCallResultPayload::entityUuid,
                    Wire.TO_SERVER.text(), ClientCallResultPayload::callId,
                    Wire.TO_SERVER.text(), ClientCallResultPayload::replyJson,
                    ClientCallResultPayload::new);

    /** 编码后的字节数。 */
    public int size() {
        return Wire.size(STREAM_CODEC, this, Unpooled::buffer);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handler invoked on the server main thread. */
    public static void handle(ClientCallResultPayload p, ServerPlayer player) {
        NetworkTransport.INSTANCE.answered(player.getUUID(), p);
    }
}
