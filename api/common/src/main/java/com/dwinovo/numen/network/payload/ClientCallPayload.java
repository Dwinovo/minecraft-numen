package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.program.ClientEndpoint;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server-to-client payload: a request the other way round. A program running on the server called an API function that
 * only the owner's client can answer (a {@code ClientCall} function: {@code numen.module.save}, {@code numen.api.help},
 * anything reading data only the client has); the program waits while the client runs it and answers with a
 * {@link ClientCallResultPayload}. Same shape as the server asking the client for something in the protocols this is
 * modelled on (MCP sampling, LSP {@code workspace/configuration}).
 *
 * <p>{@code function} is the function's full name, {@code argumentsJson} the arguments the program's call was read into
 * on the server; the client reads them again with the same codecs before running the function. The arguments can be
 * as long as the program that wrote them, so the payload is {@link Wire.Fragmentable}.
 */
public record ClientCallPayload(UUID entityUuid, String callId, String function, String argumentsJson)
        implements CustomPacketPayload, Wire.Fragmentable {

    public static final Type<ClientCallPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "client_call"));

    public static final StreamCodec<ByteBuf, ClientCallPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ClientCallPayload::entityUuid,
                    Wire.TO_CLIENT.text(), ClientCallPayload::callId,
                    Wire.TO_CLIENT.text(), ClientCallPayload::function,
                    Wire.TO_CLIENT.text(), ClientCallPayload::argumentsJson,
                    ClientCallPayload::new);

    /** 编码后的字节数。 */
    public int size() {
        return Wire.size(STREAM_CODEC, this, Unpooled::buffer);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. */
    public static void handle(ClientCallPayload p) {
        Constants.LOG.debug("[numen-net] client_call entity={} id={} {}", p.entityUuid(), p.callId(), p.function());
        ClientEndpoint.CONNECTION.handle(p);
    }
}
