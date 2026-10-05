package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.network.NumenPayload;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.program.ClientEndpoint;
import net.minecraft.network.FriendlyByteBuf;
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
        implements NumenPayload, Wire.Fragmentable {

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "client_call");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(entityUuid);
        Wire.writeText(buf, callId);
        Wire.writeText(buf, function);
        Wire.writeText(buf, argumentsJson);
    }

    public static ClientCallPayload read(FriendlyByteBuf buf) {
        return new ClientCallPayload(buf.readUUID(),
                Wire.readText(buf),
                Wire.readText(buf),
                Wire.readText(buf));
    }

    /** 编码后的字节数。 */
    public int size() {
        return Wire.size(this);
    }

    /** Client-side handler. */
    public static void handle(ClientCallPayload p) {
        Constants.LOG.debug("[numen-net] client_call entity={} id={} {}", p.entityUuid(), p.callId(), p.function());
        ClientEndpoint.CONNECTION.handle(p);
    }
}
