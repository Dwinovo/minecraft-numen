package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.tool.ClientToolContext;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.ToolInvocation;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.entity.CompanionLifecycle;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executes one agent turn's tool calls and hands the results back — the entire
 * "run a tool call, get a result" concern, lifted out of {@link EntityAgentLoop}
 * so the loop stays pure conversation/turn management.
 *
 * <h2>One synchronous serial queue</h2>
 * Calls run strictly one at a time: the next is dispatched only when the current
 * one's result lands (one body, one brain, one thing at a time — no async, no
 * concurrency). A tool reports its result through {@link ToolCall#complete},
 * synchronously or much later from any thread; the dispatcher is blind to how.
 *
 * <p>It reports back to its owner through the {@link Sink}: each landed result
 * via {@link Sink#onResult}, and {@link Sink#onAllSettled} once the turn's calls
 * are all done.
 */
public final class ToolDispatcher {

    /** Calls whose server executor rechecks world state and is safe to attach/rebuild by id. */
    private static final java.util.Set<String> RESTART_RECOVERABLE = java.util.Set.of(
            "move_to", "place_block", "break_block", "build_blueprint",
            "auto_mine", "collect_items", "locate_biome", "locate_structure", "craft_items");

    /** The dispatcher's only line back to the agent loop. */
    public interface Sink {
        /** A result landed for {@code inv} — record it into the conversation. */
        void onResult(ToolInvocation inv, String resultJson);
        /** Every call this turn has settled — the loop may start the next LLM turn. */
        void onAllSettled();
        /** The live client-side body (for client-run tools); may be null when out of view. */
        AbstractClientPlayer entity();
    }

    /**
     * Wall-clock backstop (epoch millis) for the single in-flight call, 0 when idle.
     * Only rescues a dead-server / never-replying tool — deliberately generous, so a
     * core tool (always answered by the server) never trips it.
     */
    private static final long TOOL_BACKSTOP_MILLIS = 15 * 60 * 1000L;

    private final UUID entityUuid;
    private final Sink sink;
    private final RiskController riskController;

    /** This turn's remaining calls, drained one at a time. */
    private final Deque<ToolInvocation> queue = new ArrayDeque<>();
    /** The single in-flight call (id → invocation); ≤1 under the serial model. */
    private final Map<String, ToolInvocation> inFlight = new HashMap<>();
    private final java.util.Set<String> reconnectIds = new java.util.HashSet<>();
    private final java.util.Set<String> restoredIds = new java.util.HashSet<>();
    /** Tool+args fingerprint -> safe retries still allowed after a verified failure. */
    private final Map<String, Integer> retryBudgets = new HashMap<>();
    /** Reentrancy guard so a synchronously-completing tool keeps the drain iterative. */
    private boolean advancing = false;
    private long deadlineMillis = 0;

    public ToolDispatcher(UUID entityUuid, Sink sink, RiskController riskController) {
        this.entityUuid = entityUuid;
        this.sink = sink;
        this.riskController = riskController;
    }

    /** Anything outstanding (in flight or still queued)? */
    public boolean busy() {
        return !inFlight.isEmpty() || !queue.isEmpty();
    }

    /** Run this turn's tool calls, serially. */
    public void dispatch(List<ToolInvocation> calls) {
        queue.addAll(calls);
        drainNext();
    }

    /** Resume persisted calls serially, invoking only tools that explicitly allow restart recovery. */
    public void restore(List<ToolInvocation> calls) {
        for (ToolInvocation inv : calls) {
            queue.addLast(inv);
            restoredIds.add(inv.id());
            if (RESTART_RECOVERABLE.contains(inv.name())) reconnectIds.add(inv.id());
        }
        drainNext();
    }

    /** Per-tick backstop: fail a never-replying in-flight call so the loop can't wedge. */
    public void tick() {
        if (deadlineMillis == 0 || inFlight.isEmpty()) return;
        if (System.currentTimeMillis() < deadlineMillis) return;
        ToolInvocation inv = inFlight.values().iterator().next();
        Constants.LOG.warn("[numen-dispatch#{}] tool {} id={} hit backstop timeout — failing it",
                entityUuid, inv.name(), inv.id());
        complete(inv, TaskResult.fail("tool timed out (no result returned)").toJson());
    }

    /** A fresh owner directive starts a new recovery decision boundary. */
    public void resetRecoveryBudgets() {
        retryBudgets.clear();
    }

    /**
     * Abandon everything outstanding (in flight + queued) and return their ids so the
     * caller can heal the conversation. Used on owner-interrupt and on death.
     */
    public List<String> cancelAndDrain() {
        List<String> ids = new ArrayList<>(inFlight.keySet());
        for (ToolInvocation inv : queue) ids.add(inv.id());
        inFlight.clear();
        queue.clear();
        reconnectIds.clear();
        restoredIds.clear();
        deadlineMillis = 0;
        advancing = false;
        CompanionLifecycle.fireAbort(entityUuid);   // tool packs stop their own server-side work
        return ids;
    }

    /**
     * Drain the serial queue: dispatch the next call, or — when the queue is empty
     * and nothing is in flight — signal the turn is settled. Exactly one call
     * occupies the in-flight slot at a time; {@link #complete} re-enters here to
     * advance. The {@link #advancing} guard keeps a synchronously-completing tool
     * draining iteratively instead of recursing.
     */
    private void drainNext() {
        if (advancing) return;
        advancing = true;
        try {
            while (inFlight.isEmpty()) {
                ToolInvocation inv = queue.poll();
                if (inv == null) {
                    sink.onAllSettled();
                    return;
                }
                boolean restored = restoredIds.remove(inv.id());
                NumenTool tool = ToolRegistry.resolve(inv.name());
                if (restored && (tool == null || !RESTART_RECOVERABLE.contains(inv.name()))) {
                    reconnectIds.remove(inv.id());
                    sink.onResult(inv, TaskResult.cancelled(
                            "interrupted: " + inv.name() + " is not safe to replay after restart").toJson());
                    continue;
                }
                if (tool == null) {
                    Constants.LOG.warn("[numen-dispatch#{}] LLM called unknown tool '{}' (id={})",
                            entityUuid, inv.name(), inv.id());
                    sink.onResult(inv, TaskResult.fail("unknown tool: " + inv.name()).toJson());
                    continue;   // nothing in flight — drain the next queued call
                }
                String blocked = riskController.preflight(inv, sink.entity());
                if (blocked != null) {
                    sink.onResult(inv, blocked);
                    continue;
                }
                String retryKey = retryKey(inv);
                Integer retryBudget = retryBudgets.get(retryKey);
                if (retryBudget != null) {
                    if (retryBudget <= 0) {
                        sink.onResult(inv, TaskResult.fail(
                                "automatic recovery budget exhausted; inspect current world/inventory and choose a different plan",
                                "retry_budget_exhausted", Map.of("tool", inv.name())).toJson());
                        continue;
                    }
                    retryBudgets.put(retryKey, retryBudget - 1);
                }
                inFlight.put(inv.id(), inv);
                deadlineMillis = System.currentTimeMillis() + TOOL_BACKSTOP_MILLIS;
                ToolCall call = new ToolCall(inv.id(), inv.name(), inv.argsJson(),
                        new ClientToolContext(sink.entity(), entityUuid),
                        reconnectIds.remove(inv.id()),
                        json -> complete(inv, json));
                Constants.LOG.info("[numen-dispatch#{}] dispatch tool={} id={} args={}",
                        entityUuid, inv.name(), inv.id(), truncate(inv.argsJson()));
                try {
                    tool.invoke(call);
                } catch (RuntimeException ex) {
                    Constants.LOG.warn("[numen-dispatch#{}] tool {} threw (id={}): {}",
                            entityUuid, inv.name(), inv.id(), ex.getMessage());
                    complete(inv, TaskResult.fail(ex.getMessage()).toJson());
                }
                // Client tool: complete() cleared the slot → loop drains the next.
                // Server tool: slot occupied → exit and wait for deliver().
            }
        } finally {
            advancing = false;
        }
    }

    private void complete(ToolInvocation inv, String resultJson) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> complete(inv, resultJson));
            return;
        }
        if (inFlight.remove(inv.id()) == null) {
            return;   // already settled by cancel/timeout, or a duplicate/late reply
        }
        deadlineMillis = 0;
        Constants.LOG.info("[numen-dispatch#{}] tool_result id={} tool={} (queued={}) → {}",
                entityUuid, inv.id(), inv.name(), queue.size(), truncate(resultJson));
        updateRetryBudget(inv, resultJson);
        sink.onResult(inv, resultJson);
        // Advance unless drainNext is already looping (it picks up the next itself).
        if (!advancing) drainNext();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    private void updateRetryBudget(ToolInvocation inv, String resultJson) {
        String key = retryKey(inv);
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(resultJson).getAsJsonObject();
            if (root.has("success") && root.get("success").getAsBoolean()) {
                retryBudgets.remove(key);
                return;
            }
            if (!root.has("data") || !root.get("data").isJsonObject()) return;
            var data = root.getAsJsonObject("data");
            if (!data.has("recovery") || !data.get("recovery").isJsonObject()) return;
            var recovery = data.getAsJsonObject("recovery");
            boolean safe = recovery.has("retry_safe") && recovery.get("retry_safe").getAsBoolean();
            int budget = recovery.has("retry_budget") ? recovery.get("retry_budget").getAsInt() : 0;
            if (safe) retryBudgets.putIfAbsent(key, Math.max(0, Math.min(3, budget)));
            else retryBudgets.remove(key);
        } catch (RuntimeException ignored) {
            // Non-standard local tool results do not participate in automatic retry accounting.
        }
    }

    private static String retryKey(ToolInvocation inv) {
        String args = inv.argsJson() == null || inv.argsJson().isBlank() ? "{}" : inv.argsJson().trim();
        try { args = com.google.gson.JsonParser.parseString(args).toString(); }
        catch (RuntimeException ignored) { }
        return inv.name() + "\n" + args;
    }
}
