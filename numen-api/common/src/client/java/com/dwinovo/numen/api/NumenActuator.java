package com.dwinovo.numen.api;

import com.dwinovo.numen.api.agent.inbox.ExternalWait;
import com.dwinovo.numen.api.mcp.server.ExternalHands.Companion;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.tool.ClientToolContext;
import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ToolCall;
import com.dwinovo.numen.api.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.api.client.agent.ClientNumenLookup;
import com.dwinovo.numen.api.client.agent.NumenRoster;
import com.dwinovo.numen.api.agent.tool.ScriptTool;
import com.dwinovo.numen.api.network.payload.DismissRequestPayload;
import com.dwinovo.numen.api.network.payload.SummonRequestPayload;
import com.dwinovo.numen.api.network.NumenNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The public entry point for driving a companion's <strong>body</strong> from
 * an outside brain — an MCP server, a remote agent, anything that wants to be
 * the one making the gameplay decisions instead of the companion's built-in LLM.
 *
 * <h2>Actuator vs {@link NumenGateway}</h2>
 * Two deliberately separate doors:
 * <ul>
 *   <li>{@link NumenGateway#emit} feeds a message to the companion's
 *       <em>built-in brain</em>, which then decides what to do. The brain stays
 *       in charge (chat, QQ, stream relays use this).</li>
 *   <li>{@code NumenActuator} lets an <em>external</em> brain skip the built-in
 *       LLM entirely: it lists tools, takes control of a body, calls tools
 *       directly, and reads their results. The built-in brain steps aside.</li>
 * </ul>
 *
 * <h2>没有"取得控制权"这一步</h2>
 * 直接 {@link #invoke} 即可,不需要先申请、用完再归还。两个大脑不打架靠两道现成的闸:
 * <ul>
 *   <li><b>大脑层</b>——外接大脑驱动期间({@code McpMode.driving()})内置大脑
 *       一轮都不开,主人聊天、弹幕桥接送进来的消息都只进收件箱,由外脑经
 *       {@link #takeEvents} 取走、用 {@link #say} 回话;</li>
 *   <li><b>身体层</b>——{@code TaskDispatch} 的"一具身体一件活"闸门:身体上有活时新派
 *       的活当场被拒(附带话术),物理上不可能两件活撕扯一具身体。</li>
 * </ul>
 * 模式没开时外部驱动者依然能调工具,只是要和内置大脑抢身体闸门——这是刻意留的口子
 * (程序化调用者不该被 UI 模式绑住)。
 *
 * <h2>Parallelism is free</h2>
 * Every call is addressed to a companion UUID, and each companion runs its own
 * body tasks independently on the server. So one external brain can {@link #invoke}
 * on several companions — they execute concurrently.
 *
 * <h2>No conversation side effects</h2>
 * A headless {@link #invoke} does NOT touch the companion's conversation log or
 * its built-in brain's memory. The result is returned to the caller only; the
 * external brain owns the context. (The built-in brain, being paused, records
 * nothing.)
 *
 * <h2>Client-side API, any thread</h2>
 * Companions are driven by their owner's game client, so this must be called in
 * the owner's client process. Every method is safe to call from any thread — the
 * work is marshalled onto the client main thread and a {@link CompletableFuture}
 * carries the result back.
 */
public final class NumenActuator {

    /** Synthetic tool-call ids for headless invocations, disjoint from the LLM's ids. */
    private static final AtomicLong SEQ = new AtomicLong();
    /** Ids of programs an external brain runs, disjoint from the LLM's ids. */
    private static final String PROGRAM_PREFIX = "ext-";
    /** Ids of the other tools an external brain calls (tools of remote MCP servers), disjoint from the LLM's ids. */
    private static final String TOOL_PREFIX = "mcp-";

    private NumenActuator() {}

    /**
     * The owner's companions currently live in the world — what an external brain
     * picks from. Names are what the owner sees; the UUID is the handle for every
     * other method here.
     */
    public static CompletableFuture<List<Companion>> companions() {
        CompletableFuture<List<Companion>> f = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            List<Companion> out = new ArrayList<>();
            for (NumenRoster.Entry e : NumenRoster.instance().entries()) {
                out.add(new Companion(e.uuid(), e.name()));
            }
            f.complete(out);
        });
        return f;
    }

    /**
     * Summon a fresh companion into the world by name — exactly the owner's panel
     * "+" button (idempotent per name). The body arrives asynchronously (a skin
     * lookup runs first), so confirm it by polling {@link #companions()}. Provider
     * and persona are the game's own defaults; an external driver never uses the
     * built-in brain, so a companion with no provider is still fully drivable here.
     *
     * @return true once the request was sent; false if not currently in a world
     */
    public static CompletableFuture<Boolean> create(String name) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().getConnection() == null) {
                f.complete(false);
                return;
            }
            String n = name == null ? "" : name.trim();
            NumenNetwork.sendToServer(new SummonRequestPayload(n, "", "", false, "", ""));
            f.complete(true);
        });
        return f;
    }

    /**
     * Permanently dismiss a companion — its body drops its whole inventory, armor and
     * accessories (like death), then it's gone for good — exactly the panel's ✕. The roster updates
     * asynchronously; confirm via {@link #companions()}.
     *
     * @return true once the request was sent; false if not currently in a world
     */
    public static CompletableFuture<Boolean> delete(UUID companion) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        if (companion == null) {
            f.complete(false);
            return f;
        }
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().getConnection() == null) {
                f.complete(false);
                return;
            }
            NumenNetwork.sendToServer(new DismissRequestPayload(companion));
            f.complete(true);
        });
        return f;
    }

    /**
     * Take whatever events are pending right now (the owner speaking arrives as a
     * {@code <query>}, world happenings as {@code <event>}s — same wire text the
     * built-in brain would see), including the ones that only ride along. This is
     * the one place events are consumed for an external driver: the deadline sweep
     * of a long poll and the events a program receipt carries both take from here,
     * so nothing is delivered twice. For the parked wait use {@link #awaitEvents}.
     *
     * @return the rendered event batch, or null when there was nothing
     */
    public static CompletableFuture<String> takeEvents(UUID companion) {
        CompletableFuture<String> f = new CompletableFuture<>();
        if (companion == null) {
            f.complete(null);
            return f;
        }
        Minecraft.getInstance().execute(() ->
                f.complete(AgentLoopRegistry.getOrCreate(companion).takeEventsForExternal()));
        return f;
    }

    /**
     * Park until {@code companion} has something that wakes it (the inbox's own rule, see
     * {@link ExternalWait}): {@code batch} then completes with the whole event batch;
     * {@code cancel} withdraws the waiter and hands back a batch the wake took first, so a deadline
     * that lands on the wake loses nothing. The caller's deadline sweep ({@link #takeEvents}) picks up
     * whatever a lost poke would have delivered.
     */
    public static ExternalWait.Parked awaitEvents(UUID companion) {
        if (companion == null) {
            return new ExternalWait.Parked(CompletableFuture.completedFuture(null), () -> null);
        }
        return ExternalWait.parkOn(Minecraft.getInstance(),
                () -> AgentLoopRegistry.getOrCreate(companion).parkForExternal());
    }

    /**
     * Speak as the companion: speech bubble + chat line + its bound voice, the same
     * presentation the built-in brain's replies get. Conversation state stays with
     * the caller — the game records nothing for it.
     */
    public static CompletableFuture<Boolean> say(UUID companion, String text) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        if (companion == null || text == null || text.isBlank()) {
            f.complete(false);
            return f;
        }
        Minecraft.getInstance().execute(() -> {
            AgentLoopRegistry.getOrCreate(companion).externalSay(text);
            f.complete(true);
        });
        return f;
    }

    /**
     * Run one tool for {@code companion} directly — the same tools the built-in
     * brain uses (see {@link ToolRegistry#all()} for the catalogue), invoked
     * without the LLM.
     *
     * <p>The script tool runs a program through the companion's own dispatcher
     * ({@code EntityAgentLoop#runExternal}): the same one the built-in brain uses, so
     * it waits for each body task to finish, stops between calls on an urgent event,
     * and the future completes with the program's receipt when it ends. A task's end goes
     * to the program that waits for it and into that receipt; a task left running when the
     * program stops ends as a task_finished event in get_events. Other tools
     * (the skill tool, tools of remote MCP servers) are invoked as they are.
     *
     * <p>不需要先取得控制权:内置大脑要么被「外接大脑」模式整体挂起,要么和这次调用
     * 一起受"一具身体一件活"闸门约束(身体忙时收到带话术的拒绝)。The future carries
     * the tool's result as a {@code ToolOutcome} JSON string; failures (unknown tool,
     * bad args, a thrown tool) come back as a {@code ToolOutcome.failure} JSON, never an
     * exceptional future.
     *
     * @param companion the body to act with
     * @param toolName  a registered tool name (case-tolerant, see {@link ToolRegistry#resolve})
     * @param argsJson  the tool's arguments as a JSON object string; null/blank means {@code {}}
     */
    public static CompletableFuture<String> invoke(UUID companion, String toolName, String argsJson) {
        CompletableFuture<String> f = new CompletableFuture<>();
        if (companion == null || toolName == null || toolName.isBlank()) {
            f.complete(com.dwinovo.numen.api.agent.llm.ToolOutcome.failure("companion and toolName are required"));
            return f;
        }
        Minecraft.getInstance().execute(() -> {
            try {
                NumenTool tool = ToolRegistry.resolve(toolName);
                if (tool == null) {
                    f.complete(com.dwinovo.numen.api.agent.llm.ToolOutcome.failure("unknown tool: " + toolName));
                    return;
                }
                String args = (argsJson == null || argsJson.isBlank()) ? "{}" : argsJson;
                if (tool instanceof ScriptTool) {
                    ScriptTool.code(args);   // 参数写错当场回失败,不占工具口
                    LlmToolCall program = new LlmToolCall(PROGRAM_PREFIX + SEQ.incrementAndGet(), tool.name(), args);
                    if (!AgentLoopRegistry.getOrCreate(companion).runExternal(program, f::complete)) {
                        f.complete(com.dwinovo.numen.api.agent.llm.ToolOutcome.failure("a program is already running for this companion, or its "
                                + "built-in brain is acting; wait for it to end"));
                    }
                    return;
                }
                AbstractClientPlayer body = ClientNumenLookup.resolve(companion);
                String id = TOOL_PREFIX + SEQ.incrementAndGet();
                ToolCall call = new ToolCall(id, toolName, args,
                        new ClientToolContext(body, companion),
                        f::complete);
                tool.invoke(call);
            } catch (RuntimeException ex) {
                f.complete(com.dwinovo.numen.api.agent.llm.ToolOutcome.failure(ex.getMessage()));
            }
        });
        return f;
    }
}
