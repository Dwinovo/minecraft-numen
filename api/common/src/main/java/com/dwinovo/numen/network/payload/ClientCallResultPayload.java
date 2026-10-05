package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.program.ModuleSet;
import com.dwinovo.numen.program.NetworkTransport;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.UUID;

/**
 * Client-to-server payload: the answer to a {@link ClientCallPayload}, the call's result in the shape every API call
 * returns ({@code ApiReply}). When the function changed her modules (a {@code numen.module.save}), the client's new
 * module list rides along as {@code modules}, so the program that is still running sees the new text the next time it
 * uses the module and the client stays the only source of truth. A result can be as long as what the function read
 * (a module's code) and the new module texts ride along, so the payload is {@link Wire.Fragmentable}.
 */
public record ClientCallResultPayload(UUID entityUuid, String callId, String replyJson, Optional<ModuleSet> modules)
        implements CustomPacketPayload, Wire.Fragmentable {

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "client_call_result");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(entityUuid);
        Wire.writeText(buf, callId);
        Wire.writeText(buf, replyJson);
        buf.writeOptional(modules, (b, set) -> set.write(b));
    }

    public static ClientCallResultPayload read(FriendlyByteBuf buf) {
        return new ClientCallResultPayload(buf.readUUID(), Wire.readText(buf), Wire.readText(buf),
                buf.readOptional(ModuleSet::read));
    }

    /** 编码后的字节数。 */
    public int size() {
        return Wire.size(this);
    }

    /** Handler invoked on the server main thread. */
    public static void handle(ClientCallResultPayload p, ServerPlayer player) {
        NetworkTransport.INSTANCE.answered(player.getUUID(), p);
    }
}
