package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server → Client: an asynchronous WORLD EVENT for a companion's brain (dimension change, a hazard,
 * task wind-down, …). The server ships a ready-made {@code <event>} XML string; the client-side
 * INBOX decides consumption timing by the brain's state at arrival (mid-turn → next boundary;
 * background task running → immediate turn; fully idle → wait for the next turn). {@code principal}
 * marks "a live human is speaking" (bridge mods relaying danmaku / QQ messages): those open a turn
 * even from full idle, same privilege as the owner. Plain world events leave it false — their
 * timing is the inbox's business, not the producer's.
 */
public record NumenEventPayload(UUID entityUuid, String xml, boolean principal) implements CustomPacketPayload {

    public static final Type<NumenEventPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "numen_event"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NumenEventPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, NumenEventPayload::entityUuid,
                    ByteBufCodecs.STRING_UTF8, NumenEventPayload::xml,
                    ByteBufCodecs.BOOL, NumenEventPayload::principal,
                    NumenEventPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(NumenEventPayload p) {
        AgentLoopRegistry.get(p.entityUuid()).ifPresent(loop -> loop.pushEvent(p.xml(), p.principal()));
    }
}
