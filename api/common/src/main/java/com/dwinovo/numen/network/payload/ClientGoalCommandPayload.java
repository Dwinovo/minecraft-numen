package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.network.ClientPayloadSink;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server → Client: ask a specific companion's client-side goal loop to execute
 * a chat-style {@code /goal ...} command. The goal state lives in the client
 * agent loop, so the server command only resolves the target companion and
 * ships the command text across.
 */
public record ClientGoalCommandPayload(UUID entityUuid, String command) implements CustomPacketPayload {

    public static final int MAX_COMMAND_LENGTH = 4096;

    public static final Type<ClientGoalCommandPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "client_goal_command"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientGoalCommandPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ClientGoalCommandPayload::entityUuid,
                    ByteBufCodecs.stringUtf8(MAX_COMMAND_LENGTH), ClientGoalCommandPayload::command,
                    ClientGoalCommandPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(ClientGoalCommandPayload p) {
        ClientPayloadSink.goalCommand.accept(p);
    }
}
