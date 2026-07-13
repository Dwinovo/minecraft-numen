package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server -> Client: an asynchronous WORLD EVENT for a companion's brain
 * (dimension change, a hazard, ...) -- the generic version of Claude Code's
 * "channel notification" (an external event pushed into a running session).
 * The server detects the event (edge-triggered) and ships a ready-made
 * {@code <event>} XML string; the client loop queues it like an owner prompt and
 * splices it in at a protocol-valid boundary. {@code urgent} wakes an idle brain
 * to react now; otherwise it rides along on the next owner-driven turn (no extra
 * LLM call). Death is its own bespoke freeze/thaw pair, not this.
 */
public record NumenEventPayload(UUID entityUuid, String xml, boolean urgent) {

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "numen_event");

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(entityUuid);
        buf.writeUtf(xml);
        buf.writeBoolean(urgent);
    }

    public static NumenEventPayload decode(FriendlyByteBuf buf) {
        return new NumenEventPayload(buf.readUUID(), buf.readUtf(), buf.readBoolean());
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(NumenEventPayload p) {
        AgentLoopRegistry.get(p.entityUuid()).ifPresent(loop -> loop.injectEvent(p.xml(), p.urgent()));
    }
}
