package com.dwinovo.numen.api;

import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import net.minecraft.client.Minecraft;

import java.util.UUID;

/**
 * The public entry point for feeding messages INTO a companion from outside
 * the chat GUI — Discord bridges, QQ bots, stream-chat relays, MCP servers,
 * anything.
 *
 * <h2>Deliberately unspecialized</h2>
 * This is the abstract "start()" on the base class: numen-api defines one
 * verb — <em>emit an input of a type for a companion</em>, the same verb as
 * {@link NumenApi#emit(UUID, String, String)} — and every integration decides
 * for itself what the string is. Provenance tags, rate limiting,
 * translation, permission checks: all caller-side. The input lands in the
 * companion's one event queue — the queue the chat GUI and the server's
 * events use — and is spliced into the conversation at the next
 * protocol-valid point: the owner's words exactly as if the owner had typed
 * them, a world event exactly like one the server sent.
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
 * Safe from any thread — the emit itself is marshalled onto the client
 * main thread. Companion UUIDs come from the entity
 * ({@code entity.getUUID()}).
 */
public final class NumenGateway {

    private NumenGateway() {}


    /**
     * Queue {@code text} for {@code companion}, verbatim, as an input of {@code type}: either the owner's
     * words ({@link EventTypes#QUERY}) or a registered world event coming from this client — a bridge
     * relaying what someone else said, a live-stream comment. World events are wrapped as
     * {@code <event kind="type">} with the in-game time, exactly like the server's; how urgent they are
     * is that type's row. Things that happen to the body live on the server and go out through the body's
     * {@link NumenApi#emit(com.dwinovo.numen.entity.NumenPlayer, String, java.util.Map, String, boolean)}.
     * If the companion is idle this starts a turn immediately; if it is mid-task the message is
     * seen by the model at the next tool-batch boundary (queued messages merge
     * into one user message).
     *
     * <p>The agent loop is created on first contact: any companion on the
     * client's roster is addressable immediately after login — no need to have
     * opened its chat panel first. (Loops used to be panel-created only, which
     * made every quick-key/bridge message before the first panel visit vanish
     * with "not online".)
     *
     * <p>返回的是<b>实际发生了什么</b>,不是对"她忙不忙"的推断——桥接方判"送没送到"
     * 就照这个判,自己另算一遍必然会跟真正的闸门跑偏。游戏内的聊天回显只记事不记状态,
     * 所以这几个值今天没有任何界面在读;它们是给外部集成的汇报,该说实话。
     *
     * @param companion the companion entity's UUID
     * @param type      {@link EventTypes#QUERY}, or a type registered as a world event
     * @param text      delivered exactly as given — formatting is the caller's business
     * @throws IllegalArgumentException {@code type} is neither the owner's words nor a registered world event
     */
    public static Delivery emit(UUID companion, String type, String text) {
        com.dwinovo.numen.event.NumenEvents.requireClientInput(type);
        boolean ownerWords = EventTypes.QUERY.equals(type);
        if (companion == null || text == null || text.isBlank()) return Delivery.REJECTED;
        boolean known = AgentLoopRegistry.get(companion).isPresent()
                || com.dwinovo.numen.client.agent.NumenRoster.instance().name(companion) != null;
        if (!known) return Delivery.REJECTED;
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            mc.execute(() -> submit(AgentLoopRegistry.getOrCreate(companion), ownerWords, type, text));
            return Delivery.HANDED_OFF;
        }
        EntityAgentLoop loop = AgentLoopRegistry.getOrCreate(companion);
        boolean pressed = submit(loop, ownerWords, type, text);
        // 外脑驾驶期间内脑恒为停牌,那个 boolean 恒真却什么也不说明——报驾驶席,
        // 判据取自 isExternallyDriven() 这一处真源,不另猜。
        if (loop.isExternallyDriven()) return Delivery.TO_EXTERNAL_BRAIN;
        return pressed ? Delivery.QUEUED : Delivery.SEEN;
    }

    private static boolean submit(EntityAgentLoop loop, boolean ownerWords, String type, String text) {
        return ownerWords ? loop.submitPrompt(text) : loop.submitEvent(type, text);
    }

    /**
     * 接管同伴头像的画法——装了会改外观的插件之后,原版皮肤那张脸就不是她真实的样子了。
     *
     * <p>契约与注意事项见 {@link CompanionPortrait}(尤其是"每帧都会调用,所以必须便宜"
     * 那条)。注册后引擎在所有画头像的地方都会问你,你只在能答时答,其余返回 null 让它回退。
     *
     * <p>客户端调用,通常在你的模组构造期。
     */
    public static void registerPortrait(CompanionPortrait provider) {
        com.dwinovo.numen.client.skin.CompanionFace.register(provider);
    }
}
