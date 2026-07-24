package com.dwinovo.numen.api;

import com.dwinovo.numen.agent.tool.ClientToolContext;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.network.payload.DismissRequestPayload;
import com.dwinovo.numen.network.payload.SummonRequestPayload;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskResult;
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
 *   <li>{@link NumenGateway#enqueue} feeds a message to the companion's
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
 *   <li><b>大脑层</b>——「外接大脑」模式开启期间({@code McpMode.enabled()})内置大脑
 *       一轮都不开,主人聊天、弹幕桥接送进来的消息都只进收件箱不开轮;</li>
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

    private NumenActuator() {}

    /** One of the owner's live companions: stable id + display name. */
    public record Companion(UUID uuid, String name) {}

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
            Services.NETWORK.sendToServer(new SummonRequestPayload(n, "", ""));
            f.complete(true);
        });
        return f;
    }

    /**
     * Permanently dismiss a companion — its body drops its whole inventory (like
     * death), then it's gone for good — exactly the panel's ✕. The roster updates
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
            Services.NETWORK.sendToServer(new DismissRequestPayload(companion));
            f.complete(true);
        });
        return f;
    }

    /**
     * Run one tool for {@code companion} directly — the same tools the built-in
     * brain uses (see {@link ToolRegistry#all()} for the catalogue), invoked
     * without the LLM. Perception tools resolve fast; world-action tools resolve
     * when the body finishes the task.
     *
     * <p>不需要先取得控制权:内置大脑要么被「外接大脑」模式整体挂起,要么和这次调用
     * 一起受"一具身体一件活"闸门约束(身体忙时收到带话术的拒绝)。The future carries
     * the tool's result as a {@link TaskResult} JSON string; failures (unknown tool,
     * bad args, a thrown tool) come back as a {@code TaskResult.fail} JSON, never an
     * exceptional future.
     *
     * @param companion the body to act with
     * @param toolName  a registered tool name (case-tolerant, see {@link ToolRegistry#resolve})
     * @param argsJson  the tool's arguments as a JSON object string; null/blank means {@code {}}
     */
    public static CompletableFuture<String> invoke(UUID companion, String toolName, String argsJson) {
        CompletableFuture<String> f = new CompletableFuture<>();
        if (companion == null || toolName == null || toolName.isBlank()) {
            f.complete(TaskResult.fail("companion and toolName are required").toJson());
            return f;
        }
        Minecraft.getInstance().execute(() -> {
            try {
                NumenTool tool = ToolRegistry.resolve(toolName);
                if (tool == null) {
                    f.complete(TaskResult.fail("unknown tool: " + toolName).toJson());
                    return;
                }
                AbstractClientPlayer body = ClientNumenLookup.resolve(companion);
                String id = TaskRecord.EXTERNAL_CALL_PREFIX + SEQ.incrementAndGet();
                String args = (argsJson == null || argsJson.isBlank()) ? "{}" : argsJson;
                ToolCall call = new ToolCall(id, toolName, args,
                        new ClientToolContext(body, companion),
                        f::complete);
                tool.invoke(call);
            } catch (RuntimeException ex) {
                f.complete(TaskResult.fail(ex.getMessage()).toJson());
            }
        });
        return f;
    }
}
