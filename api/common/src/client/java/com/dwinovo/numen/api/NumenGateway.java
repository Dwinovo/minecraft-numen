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

    /** 一句话送出去之后的下场。 */
    public enum Delivery {
        /** 空消息 / 不认识这个 UUID —— 没送出去。 */
        REJECTED,
        /** 她当场就看了(请求已经发出)。 */
        SEEN,
        /** 先排着:她手上有事没法马上看(在飞的回合、未决工具、死着、被停止)。 */
        QUEUED,
        /** 不在客户端主线程,只能交给它稍后处理 —— 结果这边观察不到。 */
        HANDED_OFF
    }

    /**
     * Queue {@code message} for {@code companion}, verbatim. If the companion
     * is idle this starts a turn immediately; if it is mid-task the message is
     * seen by the model at the next tool-batch boundary (queued messages merge
     * into one user message).
     *
     * <p>The agent loop is created on first contact: any companion on the
     * client's roster is addressable immediately after login — no need to have
     * opened its chat panel first. (Loops used to be panel-created only, which
     * made every quick-key/bridge message before the first panel visit vanish
     * with "not online".)
     *
     * <p>返回的是<b>实际发生了什么</b>,不是对"她忙不忙"的推断。界面要显示"排队中"
     * 就照这个显示;自己另判一遍必然会跟真正的闸门跑偏。
     *
     * @param companion the companion entity's UUID
     * @param message   delivered exactly as given — formatting is the caller's business
     */
    public static Delivery enqueue(UUID companion, String message) {
        if (companion == null || message == null || message.isBlank()) return Delivery.REJECTED;
        boolean known = AgentLoopRegistry.get(companion).isPresent()
                || com.dwinovo.numen.client.agent.NumenRoster.instance().name(companion) != null;
        if (!known) return Delivery.REJECTED;
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            mc.execute(() -> AgentLoopRegistry.getOrCreate(companion).submitPrompt(message));
            return Delivery.HANDED_OFF;
        }
        return AgentLoopRegistry.getOrCreate(companion).submitPrompt(message)
                ? Delivery.QUEUED : Delivery.SEEN;
    }
}
