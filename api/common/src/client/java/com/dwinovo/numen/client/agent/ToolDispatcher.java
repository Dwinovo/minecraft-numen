package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.loop.SerialCalls;
import com.dwinovo.numen.agent.loop.ToolPort;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.tool.ClientToolContext;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.entity.CompanionEvents;
import com.dwinovo.numen.event.NumenEvents;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The loop kernel's {@link ToolPort} for one companion: executes one model reply's tool calls and
 * hands the results back — the entire "run a tool call, get a result" concern.
 *
 * <h2>In order, each one done before the next</h2>
 * The order — one call at a time, a background body job finished before the next call goes out, and
 * what an urgent input does while one is awaited — is {@link SerialCalls}'s. This class supplies how one
 * call runs: resolve the tool, hand it a {@link ToolCall} bound to this companion, and let the tool report
 * through {@link ToolCall#complete}, synchronously or much later from any thread. A body job's receipt and
 * its task_finished are read with {@link TaskDispatch#runningTaskOf} and {@link NumenEvents#finishedTaskOf},
 * next to where each is written.
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
    private final SerialCalls calls;

    /** Where the in-flight call's result goes, for the backstop; null when nothing is in flight. */
    private Consumer<String> inFlightDone;
    private long deadlineMillis = 0;

    public ToolDispatcher(UUID entityUuid, Supplier<AbstractClientPlayer> entity) {
        this.entityUuid = entityUuid;
        this.entity = entity;
        this.calls = new SerialCalls(this::invoke, TaskDispatch::runningTaskOf, NumenEvents::finishedTaskOf);
    }

    /** Is this call still outstanding (in flight or still queued), i.e. can its result still arrive? */
    public boolean holds(String callId) {
        return calls.holds(callId);
    }

    /** 手上这一件的工具名(在飞的,没有就是下一个要派的),空闲返回 null——头顶气泡的副文本取它。 */
    public String currentToolName() {
        LlmToolCall call = calls.current();
        return call == null ? null : call.name();
    }

    /** 收下这一批调用,按顺序执行。 */
    @Override
    public void run(List<LlmToolCall> batch, Sink sink) {
        calls.run(batch, sink);
    }

    @Override
    public void arrived(EventQueue.Entry entry, boolean urgent) {
        calls.arrived(entry, urgent);
    }

    /**
     * Per-tick backstop: fail a never-replying in-flight call so the loop can't wedge.
     *
     * <p>这具身体挂着一条等主人点头的征询时不算:服务端活着、正在等人,在飞的那件同步动作
     * (use block 左键、inv drop)就是悬着等这个答复,由服务端按游戏刻超时收尾。兜底时钟
     * 从答复之后重新起算。等身体任务收尾不在这里:那件活有自己的期限,到点以 timeout 收尾。
     */
    public void tick() {
        if (deadlineMillis == 0 || calls.inFlight() == null) return;
        if (com.dwinovo.numen.client.consent.ConsentCards.pending(entityUuid) != null) {
            deadlineMillis = System.currentTimeMillis() + TOOL_BACKSTOP_MILLIS;
            return;
        }
        if (System.currentTimeMillis() < deadlineMillis) return;
        LlmToolCall call = calls.inFlight();
        Constants.LOG.warn("[numen-dispatch#{}] tool {} id={} hit backstop timeout — failing it",
                entityUuid, call.name(), call.id());
        inFlightDone.accept(TaskResult.fail("tool timed out (no result returned)").toJson());
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
        List<String> ids = calls.cancel();
        inFlightDone = null;
        deadlineMillis = 0;
        // 停在传输层的这几个调用按 id 忘掉:结果回来也没人要了。只清自己派的,
        // 外接模型挂在同一只同伴身上的调用不动。
        com.dwinovo.numen.agent.tool.ServerToolTransport.forget(ids);
        if (stopBody) {
            CompanionEvents.fire(CompanionEvent.ABORT, entityUuid);   // 内容包据此停掉自己那边的活
        }
        return ids;
    }

    /**
     * 执行一个调用:按名字取工具,交给它一个绑着这只同伴的 {@link ToolCall}。客户端工具当场回结果;服务端工具送过去,
     * 结果之后回来。没有这个工具、工具抛出,都当场回一条失败。
     */
    private void invoke(LlmToolCall call, Consumer<String> done) {
        NumenTool tool = ToolRegistry.resolve(call.name());
        if (tool == null) {
            Constants.LOG.warn("[numen-dispatch#{}] LLM called unknown tool '{}' (id={})",
                    entityUuid, call.name(), call.id());
            done.accept(TaskResult.fail("unknown tool: " + call.name()).toJson());
            return;
        }
        Consumer<String> landed = json -> {
            if (inFlightDone != null && call == calls.inFlight()) {
                inFlightDone = null;
                deadlineMillis = 0;
            }
            Constants.LOG.info("[numen-dispatch#{}] tool_result id={} tool={} → {}",
                    entityUuid, call.id(), call.name(), truncate(json));
            done.accept(json);
        };
        inFlightDone = landed;
        deadlineMillis = System.currentTimeMillis() + TOOL_BACKSTOP_MILLIS;
        // 带规范名(tool.name())而不是 LLM 写的那个:大小写宽松只在 resolve 这一步,
        // 服务端工具经 ServerToolTransport 原样带名字过去,那边按注册名严格查。
        ToolCall handle = new ToolCall(call.id(), tool.name(), call.arguments(),
                new ClientToolContext(entity.get(), entityUuid), landed);
        Constants.LOG.info("[numen-dispatch#{}] dispatch tool={} id={} args={}",
                entityUuid, call.name(), call.id(), truncate(call.arguments()));
        try {
            tool.invoke(handle);
        } catch (RuntimeException ex) {
            Constants.LOG.warn("[numen-dispatch#{}] tool {} threw (id={}): {}",
                    entityUuid, call.name(), call.id(), ex.getMessage());
            landed.accept(TaskResult.fail(ex.getMessage()).toJson());
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
