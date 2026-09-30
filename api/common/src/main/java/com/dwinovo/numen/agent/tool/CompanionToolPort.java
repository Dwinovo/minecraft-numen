package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.loop.SerialCalls;
import com.dwinovo.numen.agent.loop.ToolPort;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.entity.CompanionEvents;
import com.dwinovo.numen.event.NumenEvents;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskResult;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 一只同伴的工具口:循环内核把模型一次回复里的调用交给它,它逐个执行、把结果报回。主人客户端的派发器与评测大脑都用这一份。
 *
 * <p>顺序与等待——一次一个、留下后台身体活的等它收尾再派下一个、等的时候来了急件怎么办——是 {@link SerialCalls} 的;身体活的
 * 受理回执与 task_finished 按 {@link TaskDispatch#runningTaskOf}、{@link NumenEvents#finishedTaskOf} 认,和写它们的地方挨着。
 * 这里只管一个调用怎么执行:按名字取工具,交给它一个绑着这只同伴的 {@link ToolCall},由工具自己决定当场答还是送去服务端
 * ({@link ServerToolTransport}),结果之后从任何线程经 {@link ToolCall#complete} 回来。
 */
public final class CompanionToolPort implements ToolPort {

    private final UUID companion;
    /** 每个调用派出那一刻取一次:客户端上它带着此刻看得见的那具身体(出了视距是 null)。 */
    private final Supplier<? extends ToolAnchor> anchor;
    private final SerialCalls calls;

    /** 在飞的那一个的回报口,供 {@link #failInFlight} 用;没有在飞的是 null。 */
    private Consumer<String> inFlightDone;

    public CompanionToolPort(UUID companion, Supplier<? extends ToolAnchor> anchor) {
        this.companion = companion;
        this.anchor = anchor;
        this.calls = new SerialCalls(this::invoke, TaskDispatch::runningTaskOf, NumenEvents::finishedTaskOf);
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
     * 收掉这批所有未决调用(在飞 + 排着),返回它们的 id。停在传输层的这几个按 id 忘掉:结果回来也没人要了。只清自己派的,
     * 外接模型挂在同一只同伴身上的调用不动。
     *
     * @param stopBody 要不要连身体一起叫停:主人按停止要,他要她立刻住手;死亡、登出、外接接管、遣散、收场不要
     */
    @Override
    public List<String> cancel(boolean stopBody) {
        List<String> ids = calls.cancel();
        inFlightDone = null;
        ServerToolTransport.forget(ids);
        if (stopBody) {
            CompanionEvents.fire(CompanionEvent.ABORT, companion);   // 内容包据此停掉自己那边的活
        }
        return ids;
    }

    /** 这个调用的结果还会不会来:在飞,或者还排着。 */
    public boolean holds(String callId) {
        return calls.holds(callId);
    }

    /** 派出去、结果还没回来的那一个;没有是 null。 */
    public LlmToolCall inFlight() {
        return calls.inFlight();
    }

    /** 手上这一件的工具名(在飞的,没有就是下一个要派的),空闲返回 null。 */
    public String currentToolName() {
        LlmToolCall call = calls.current();
        return call == null ? null : call.name();
    }

    /** 在飞的那一个就此以一条失败结算,{@code why} 是给模型的原因;没有在飞的什么都不做。 */
    public void failInFlight(String why) {
        if (inFlightDone != null) {
            inFlightDone.accept(TaskResult.fail(why).toJson());
        }
    }

    /**
     * 执行一个调用:按名字取工具,交给它一个绑着这只同伴的 {@link ToolCall}。没有这个工具、工具抛出,都当场回一条失败。
     */
    private void invoke(LlmToolCall call, Consumer<String> done) {
        NumenTool tool = ToolRegistry.resolve(call.name());
        if (tool == null) {
            Constants.LOG.warn("[numen-dispatch#{}] LLM called unknown tool '{}' (id={})",
                    companion, call.name(), call.id());
            done.accept(TaskResult.fail("unknown tool: " + call.name()).toJson());
            return;
        }
        Consumer<String> landed = json -> {
            if (inFlightDone != null && call == calls.inFlight()) {
                inFlightDone = null;
            }
            Constants.LOG.info("[numen-dispatch#{}] tool_result id={} tool={} → {}",
                    companion, call.id(), call.name(), truncate(json));
            done.accept(json);
        };
        inFlightDone = landed;
        // 带规范名(tool.name())而不是 LLM 写的那个:大小写宽松只在 resolve 这一步,
        // 服务端工具经 ServerToolTransport 原样带名字过去,那边按注册名严格查。
        ToolCall handle = new ToolCall(call.id(), tool.name(), call.arguments(), anchor.get(), landed);
        Constants.LOG.info("[numen-dispatch#{}] dispatch tool={} id={} args={}",
                companion, call.name(), call.id(), truncate(call.arguments()));
        try {
            tool.invoke(handle);
        } catch (RuntimeException ex) {
            Constants.LOG.warn("[numen-dispatch#{}] tool {} threw (id={}): {}",
                    companion, call.name(), call.id(), ex.getMessage());
            landed.accept(TaskResult.fail(ex.getMessage()).toJson());
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
