package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.loop.ToolPort;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.tool.ClientToolContext;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.entity.CompanionEvents;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The loop kernel's {@link ToolPort} for one companion: executes one model reply's tool calls and
 * hands the results back — the entire "run a tool call, get a result" concern.
 *
 * <h2>One synchronous serial queue</h2>
 * Calls run strictly one at a time: the next is dispatched only when the current
 * one's result lands (one body, one brain, one thing at a time — no async, no
 * concurrency). A tool reports its result through {@link ToolCall#complete},
 * synchronously or much later from any thread; the dispatcher is blind to how.
 *
 * <p>It reports back through the batch's {@link ToolPort.Sink}: each dispatched call via
 * {@link ToolPort.Sink#started}, each landed result via {@link ToolPort.Sink#finished}, and
 * {@link ToolPort.Sink#settled} once the batch's calls are all done.
 */
public final class ToolDispatcher implements ToolPort {

    /**
     * Wall-clock backstop (epoch millis) for the single in-flight call, 0 when idle.
     * Only rescues a dead-server / never-replying tool — deliberately generous, so a
     * core tool (always answered by the server) never trips it.
     */
    private static final long TOOL_BACKSTOP_MILLIS = 15 * 60 * 1000L;

    private final UUID entityUuid;
    /** The live client-side body (for client-run tools); may resolve to null when out of view. */
    private final Supplier<AbstractClientPlayer> entity;

    /** This batch's remaining calls, drained one at a time. */
    private final Deque<LlmToolCall> queue = new ArrayDeque<>();
    /** The single in-flight call (id → call); ≤1 under the serial model. */
    private final Map<String, LlmToolCall> inFlight = new HashMap<>();
    /** 本批允许调用的工具名(见 {@link #run})。 */
    private Set<String> callable = Set.of();
    /** 本批的回报口;放弃这批时摘掉,迟到的结果无处可报。 */
    private Sink sink;

    /** Reentrancy guard so a synchronously-completing tool keeps the drain iterative. */
    private boolean advancing = false;
    private long deadlineMillis = 0;

    public ToolDispatcher(UUID entityUuid, Supplier<AbstractClientPlayer> entity) {
        this.entityUuid = entityUuid;
        this.entity = entity;
    }

    /** Anything outstanding (in flight or still queued)? */
    public boolean busy() {
        return !inFlight.isEmpty() || !queue.isEmpty();
    }

    /** Is this call still outstanding (in flight or still queued), i.e. can its result still arrive? */
    public boolean holds(String callId) {
        if (inFlight.containsKey(callId)) return true;
        for (LlmToolCall call : queue) {
            if (call.id().equals(callId)) return true;
        }
        return false;
    }

    /** 在飞那一件的工具名(串行模型下 ≤1),空闲返回 null——头顶气泡的副文本取它。 */
    public String currentToolName() {
        for (LlmToolCall call : inFlight.values()) {
            return call.name();
        }
        LlmToolCall next = queue.peek();
        return next == null ? null : next.name();
    }

    /**
     * 收下这一批调用。{@code callable} 是<b>这一批发出时模型能看见定义的工具</b>——
     * 常驻的加上对话里还留着展开块的那些(见 {@code ToolDisclosure})。随批次传进来
     * 而不是存成全局状态:它描述的是一个瞬间,留到下一批就是陈账。
     */
    @Override
    public void run(List<LlmToolCall> calls, Set<String> callable, Sink sink) {
        this.callable = callable == null ? Set.of() : callable;
        this.sink = sink;
        queue.addAll(calls);
        drainNext();
    }

    /**
     * Per-tick backstop: fail a never-replying in-flight call so the loop can't wedge.
     *
     * <p>这具身体挂着一条等主人点头的征询时不算:服务端活着、正在等人,在飞的那件同步动作
     * (interact_at 左键、drop_items)就是悬着等这个答复,由服务端按游戏刻超时收尾。兜底时钟
     * 从答复之后重新起算。
     */
    public void tick() {
        if (deadlineMillis == 0 || inFlight.isEmpty()) return;
        if (com.dwinovo.numen.client.consent.ConsentCards.pending(entityUuid) != null) {
            deadlineMillis = System.currentTimeMillis() + TOOL_BACKSTOP_MILLIS;
            return;
        }
        if (System.currentTimeMillis() < deadlineMillis) return;
        LlmToolCall call = inFlight.values().iterator().next();
        Constants.LOG.warn("[numen-dispatch#{}] tool {} id={} hit backstop timeout — failing it",
                entityUuid, call.name(), call.id());
        complete(call, TaskResult.fail("tool timed out (no result returned)").toJson());
    }

    /**
     * 收掉这批所有未决调用(在飞 + 排着),返回它们的 id。
     *
     * @param stopBody 要不要连身体一起叫停。<b>主人按停止</b>要——他要她立刻住手;死亡、断线登出、
     *                 外接接管、遣散不要——死了不必叫,登出时她的身体还在服务器里、任务照样该跑完,
     *                 而且那一刻连接已经没了,叫停包根本发不出去
     */
    @Override
    public List<String> cancel(boolean stopBody) {
        List<String> ids = new ArrayList<>(inFlight.keySet());
        for (LlmToolCall call : queue) ids.add(call.id());
        inFlight.clear();
        queue.clear();
        sink = null;
        deadlineMillis = 0;
        advancing = false;
        // 停在传输层的这几个调用按 id 忘掉:结果回来也没人要了。只清自己派的,
        // 外接模型挂在同一只同伴身上的调用不动。
        com.dwinovo.numen.agent.tool.ServerToolTransport.forget(ids);
        if (stopBody) {
            CompanionEvents.fire(CompanionEvent.ABORT, entityUuid);   // 内容包据此停掉自己那边的活
        }
        return ids;
    }

    /**
     * Drain the serial queue: dispatch the next call, or — when the queue is empty
     * and nothing is in flight — signal the batch is settled. Exactly one call
     * occupies the in-flight slot at a time; {@link #complete} re-enters here to
     * advance. The {@link #advancing} guard keeps a synchronously-completing tool
     * draining iteratively instead of recursing.
     */
    private void drainNext() {
        if (advancing) return;
        advancing = true;
        try {
            while (inFlight.isEmpty()) {
                LlmToolCall call = queue.poll();
                if (call == null) {
                    sink.settled();
                    return;
                }
                NumenTool tool = ToolRegistry.resolve(call.name());
                if (tool != null && !callable.contains(tool.name())) {
                    // 定义没在她眼前,参数只能是猜的——挡下来并告诉她怎么补,
                    // 比让一次瞎填的调用真的动身体便宜。
                    Constants.LOG.info("[numen-dispatch#{}] tool '{}' not expanded yet (id={})",
                            entityUuid, call.name(), call.id());
                    sink.finished(call, TaskResult.fail(
                            com.dwinovo.numen.agent.tool.ToolDisclosure.notExpanded(tool.name())).toJson());
                    continue;
                }
                if (tool == null) {
                    Constants.LOG.warn("[numen-dispatch#{}] LLM called unknown tool '{}' (id={})",
                            entityUuid, call.name(), call.id());
                    sink.finished(call, TaskResult.fail("unknown tool: " + call.name()).toJson());
                    continue;   // nothing in flight — drain the next queued call
                }
                inFlight.put(call.id(), call);
                deadlineMillis = System.currentTimeMillis() + TOOL_BACKSTOP_MILLIS;
                sink.started(call);
                // 带规范名(tool.name())而不是 LLM 写的那个:大小写宽松只在 resolve 这一步,
                // 服务端工具经 ServerToolTransport 原样带名字过去,那边按注册名严格查。
                ToolCall handle = new ToolCall(call.id(), tool.name(), call.arguments(),
                        new ClientToolContext(entity.get(), entityUuid),
                        json -> complete(call, json));
                Constants.LOG.info("[numen-dispatch#{}] dispatch tool={} id={} args={}",
                        entityUuid, call.name(), call.id(), truncate(call.arguments()));
                try {
                    tool.invoke(handle);
                } catch (RuntimeException ex) {
                    Constants.LOG.warn("[numen-dispatch#{}] tool {} threw (id={}): {}",
                            entityUuid, call.name(), call.id(), ex.getMessage());
                    complete(call, TaskResult.fail(ex.getMessage()).toJson());
                }
                // Client tool: complete() cleared the slot → loop drains the next.
                // Server tool: slot occupied → exit and wait for deliver().
            }
        } finally {
            advancing = false;
        }
    }

    private void complete(LlmToolCall call, String resultJson) {
        if (inFlight.remove(call.id()) == null) {
            return;   // already settled by cancel/timeout, or a duplicate/late reply
        }
        deadlineMillis = 0;
        Constants.LOG.info("[numen-dispatch#{}] tool_result id={} tool={} (queued={}) → {}",
                entityUuid, call.id(), call.name(), queue.size(), truncate(resultJson));
        sink.finished(call, resultJson);
        // Advance unless drainNext is already looping (it picks up the next itself).
        if (!advancing) drainNext();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
