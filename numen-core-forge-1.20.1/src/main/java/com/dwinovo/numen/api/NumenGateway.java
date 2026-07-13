package com.dwinovo.numen.api;

import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import net.minecraft.client.Minecraft;

import java.util.UUID;

/**
 * The public entry point for feeding messages INTO a companion from outside
 * the chat GUI — Discord bridges, QQ bots, stream-chat relays, MCP servers,
 * anything.
 *
 * <h2>Deliberately unspecialized</h2>
 * This is the abstract "start()" on the base class: numen-api defines one
 * verb — <em>enqueue a string for a companion</em> — and every integration
 * decides for itself what that string is. Provenance tags, rate limiting,
 * translation, permission checks: all caller-side. The message lands in the
 * same owner-prompt queue the chat GUI uses and is spliced into the
 * conversation at the next protocol-valid point, exactly as if the owner had
 * typed it.
 *
 * <h2>Outbound is not here — and never will be</h2>
 * Replies leave the companion through tools, not callbacks: register a
 * {@code NumenTool} (e.g. {@code send_qq_message}) via
 * {@code ToolRegistry.register} and the brain calls it when it has something
 * to say. Inbound = message queue, outbound = tool call. The tool-call queue
 * itself is likewise sealed: its entries are the products of an LLM decision,
 * each bound to a {@code tool_call_id} in the conversation protocol —
 * injecting one from outside would be operating the hands without the brain.
 *
 * <h2>Client-side API</h2>
 * Companions are driven by their owner's game client (the owner's API key
 * pays for the tokens), so this must be called in the owner's client process.
 * Safe from any thread — the enqueue itself is marshalled onto the client
 * main thread. Companion UUIDs come from the entity
 * ({@code entity.getUUID()}).
 */
public final class NumenGateway {

    private NumenGateway() {}

    /**
     * Queue {@code message} for {@code companion}, verbatim. If the companion
     * is idle this starts a turn immediately; if it is mid-task the message is
     * seen by the model at the next tool-batch boundary (queued messages merge
     * into one user message).
     *
     * @param companion the companion entity's UUID
     * @param message   delivered exactly as given — formatting is the caller's
     *                  business
     * @return false when the message is blank or no agent loop exists for
     *         {@code companion} at call time (never summoned this session);
     *         true means the message was handed to the client thread for
     *         enqueueing (a dead-and-awaiting-respawn body may still drop it)
     */
    public static boolean enqueue(UUID companion, String message) {
        if (companion == null || message == null || message.isBlank()) return false;
        if (AgentLoopRegistry.get(companion).isEmpty()) return false;
        Minecraft.getInstance().execute(() ->
                AgentLoopRegistry.get(companion).ifPresent(loop -> loop.submitPrompt(message)));
        return true;
    }
}
