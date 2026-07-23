package com.dwinovo.numen.api;

import com.dwinovo.numen.agent.tool.ClientToolContext;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
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
 * <h2>The contract: acquire → invoke* → release</h2>
 * Before driving a companion, {@link #acquire} it — this pauses its built-in
 * brain and frees its body (any in-flight internal turn/task is stopped) so the
 * two brains never fight over one body. Then {@link #invoke} tools as needed.
 * When done, {@link #release} so the built-in brain can act again.
 *
 * <h2>Parallelism is free</h2>
 * Every call is addressed to a companion UUID, and each companion runs its own
 * body tasks independently on the server. So one external brain can {@link
 * #acquire} several companions and {@link #invoke} on each — they execute
 * concurrently. Mixed fleets work too: acquire A and B, leave C to its built-in
 * brain.
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
     * Take external control of {@code companion}: pause its built-in brain and
     * stop any in-flight internal turn/task, leaving the body free to drive.
     * Idempotent.
     *
     * @return true once control is held; false if {@code companion} is not one of
     *         the owner's live companions
     */
    public static CompletableFuture<Boolean> acquire(UUID companion) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        if (companion == null) {
            f.complete(false);
            return f;
        }
        Minecraft.getInstance().execute(() -> {
            boolean known = NumenRoster.instance().entries().stream()
                    .anyMatch(e -> companion.equals(e.uuid()));
            if (!known) {
                f.complete(false);
                return;
            }
            AgentLoopRegistry.getOrCreate(companion).acquireExternal();
            f.complete(true);
        });
        return f;
    }

    /**
     * Release external control of {@code companion} — its built-in brain may act
     * again (it does not auto-start; it waits for the next owner prompt).
     * Idempotent; a no-op if the companion was never acquired.
     */
    public static CompletableFuture<Boolean> release(UUID companion) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        if (companion == null) {
            f.complete(false);
            return f;
        }
        Minecraft.getInstance().execute(() -> {
            AgentLoopRegistry.get(companion).ifPresent(EntityAgentLoop::releaseExternal);
            f.complete(true);
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
     * <p>The caller should {@link #acquire} the companion first so its built-in
     * brain isn't also driving the body. The future carries the tool's result as
     * a {@link TaskResult} JSON string; failures (unknown tool, bad args, a thrown
     * tool) come back as a {@code TaskResult.fail} JSON, never an exceptional
     * future.
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
