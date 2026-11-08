package com.dwinovo.numen.api.agent.tool;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.llm.ToolOutcome;
import com.dwinovo.numen.api.agent.loop.SerialCalls;
import com.dwinovo.numen.api.agent.loop.ToolPort;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.entity.CompanionEvents;
import com.dwinovo.numen.api.agent.loop.Program;
import com.dwinovo.numen.api.program.ProgramHost;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 一只同伴的工具口:循环内核把模型一次回复里的调用交给它,它逐个执行、把结果报回。她的大脑({@code CompanionLoop})用这一份。
 *
 * <p>顺序与等待——一次一个、程序在跑的时候后面的等着、来了急件叫程序停在调用之间——是 {@link SerialCalls} 的。这里只管
 * 一个调用怎么执行:跑 Lua 的那个工具({@link ScriptTool})的调用是一整段程序,整段交给 {@link ProgramHost},它在
 * 身体与数据旁边跑完,回一张回执(连同每次 API 调用的结局);别的工具(装技能、记计划、记札记、接进来的 MCP 工具)按名字取出来,
 * 在这里就地执行,结果之后从任何线程经 {@link ToolCall#complete} 回来。切断时跑着的程序照常停下、交出回执,晚到的回执经
 * {@link AfterCut} 交出去。
 */
public final class CompanionToolPort implements ToolPort, SerialCalls.Port {

    private final UUID companion;
    /** 每个调用派出那一刻取一次:客户端上它带着此刻看得见的那具身体(出了视距是 null)。 */
    private final Supplier<? extends ToolAnchor> anchor;
    private final ProgramHost programs;
    private final ToolBook tools;
    private final AfterCut afterCut;
    private final SerialCalls calls;

    /** 在飞的那一个的回报口,供 {@link #failInFlight} 用;没有在飞的是 null。 */
    private java.util.function.Consumer<SerialCalls.Settled> inFlightDone;

    /**
     * 被切断的程序交出回执之后,这里把它转给收件的人:程序编号与回执的文字。切断时这一批已经作废,模型读不到这份回执,
     * 但程序切断前做了什么必须让她知道——收件的人把它作为一条事件({@code NumenEvents.programStopped})放进她的收件箱。
     */
    @FunctionalInterface
    public interface AfterCut {
        void receipt(String programId, String receipt);
    }

    /**
     * @param programs 程序交给谁跑:大脑是本进程里的 {@code LocalPrograms}
     * @param tools    她看得到的工具:模型点名的工具从这里取
     */
    public CompanionToolPort(UUID companion, Supplier<? extends ToolAnchor> anchor, ProgramHost programs,
                             ToolBook tools, AfterCut afterCut) {
        this.companion = companion;
        this.anchor = anchor;
        this.programs = programs;
        this.tools = tools;
        this.afterCut = afterCut;
        this.calls = new SerialCalls(this);
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
     * 收掉这批所有未决调用(在飞 + 排着),返回它们的 id。在飞的是程序的话,它当场停下;只清自己派的,外接模型挂在同一只
     * 同伴身上的调用不动。
     *
     * @param stopBody 要不要连身体一起叫停:主人按停止要,他要她立刻住手;死亡、登出、外接接管、遣散、收场不要
     */
    @Override
    public List<String> cancel(boolean stopBody) {
        LlmToolCall flying = calls.inFlight();
        String program = flying != null && isProgram(flying) ? flying.id() : null;
        List<String> ids = calls.cancel(stopBody);
        inFlightDone = null;
        for (String id : ids) {
            if (id.equals(program)) {
                // 这一批作废了,程序照常停下并交出回执:晚到的那份作为一条事件交给她
                programs.afterwards(id, outcome -> afterCut.receipt(id, outcome.receipt()));
            } else {
                programs.forget(id);
            }
        }
        if (stopBody) {
            programs.stopBody(companion);
            CompanionEvents.fire(CompanionEvent.ABORT, companion);   // 内容包据此停掉自己那边的活
        }
        return ids;
    }

    /** 这个调用的结果还会不会来:在飞,或者还排着。 */
    public boolean holds(String callId) {
        return calls.holds(callId);
    }

    /** 结果还会来的调用的 id(在飞的与排着的):历史里没结果、又不在这里的调用是被切断的。 */
    public List<String> outstanding() {
        return calls.outstanding();
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

    /** 在飞的那一个就此以一条失败结算,{@code why} 是给模型的原因;没有在飞的什么都不做。在飞的是程序的话,它一并叫停。 */
    public void failInFlight(String why) {
        LlmToolCall call = calls.inFlight();
        if (call != null && isProgram(call)) {
            programs.forget(call.id());
            programs.cutOff(companion, call.id(), false);
        }
        if (inFlightDone != null) {
            inFlightDone.accept(SerialCalls.Settled.of(ToolOutcome.failure(why)));
        }
    }

    // ---- SerialCalls.Port ----

    @Override
    public boolean isProgram(LlmToolCall call) {
        return tools.resolve(call.name()) instanceof ScriptTool;
    }

    @Override
    public void interrupt(LlmToolCall program, String why) {
        programs.interrupt(companion, program.id(), why);
    }

    @Override
    public void cutOff(LlmToolCall program, boolean stopBody) {
        programs.cutOff(companion, program.id(), stopBody);
    }

    /**
     * 执行一个调用:程序整段交给 {@link ProgramHost};别的按名字取工具,交给它一个绑着这只同伴的 {@link ToolCall}。没有这个工具、参数写错、
     * 工具抛出,都当场回一条失败。
     */
    @Override
    public void invoke(LlmToolCall call, java.util.function.Consumer<SerialCalls.Settled> done) {
        NumenTool tool = tools.resolve(call.name());
        if (tool == null) {
            Constants.LOG.warn("[numen-dispatch#{}] LLM called unknown tool '{}' (id={})",
                    companion, call.name(), call.id());
            done.accept(SerialCalls.Settled.of(ToolOutcome.failure("unknown tool: " + call.name())));
            return;
        }
        java.util.function.Consumer<SerialCalls.Settled> landed = landing(call, done);
        // 日志是公开的(服主、mclo.gs):工具参数(程序源码)只记多大,不记原文
        Constants.LOG.info("[numen-dispatch#{}] dispatch tool={} id={} args={} chars",
                companion, call.name(), call.id(), call.arguments().length());
        try {
            if (tool instanceof ScriptTool) {
                programs.run(companion, call.id(), ScriptTool.code(call.arguments()),
                        outcome -> landed.accept(settled(outcome)));
                return;
            }
            // 带规范名(tool.name())而不是 LLM 写的那个:大小写宽松只在 resolve 这一步
            tool.invoke(new ToolCall(call.id(), tool.name(), call.arguments(), anchor.get(),
                    json -> landed.accept(SerialCalls.Settled.of(json))));
        } catch (IllegalArgumentException invalid) {
            landed.accept(SerialCalls.Settled.of(ToolOutcome.failure("invalid arguments: " + invalid.getMessage())));
        } catch (RuntimeException ex) {
            Constants.LOG.warn("[numen-dispatch#{}] tool {} threw (id={}): {}",
                    companion, call.name(), call.id(), ex.getMessage());
            landed.accept(SerialCalls.Settled.of(ToolOutcome.failure(ex.getMessage())));
        }
    }

    /** 程序交回的结局:回执、每次调用的结局、是不是被叫停的。 */
    private static SerialCalls.Settled settled(Program.Outcome outcome) {
        return new SerialCalls.Settled(outcome.receipt(), outcome.ending(), outcome.calls(), outcome.stoppedFor());
    }

    /** 一个调用的回报口:记一笔,交给 {@code done};在飞时它也是 {@link #failInFlight} 用的那一个。 */
    private java.util.function.Consumer<SerialCalls.Settled> landing(LlmToolCall call,
                                                                     java.util.function.Consumer<SerialCalls.Settled> done) {
        java.util.function.Consumer<SerialCalls.Settled> landed = settled -> {
            if (inFlightDone != null && call == calls.inFlight()) {
                inFlightDone = null;
            }
            Constants.LOG.info("[numen-dispatch#{}] result id={} {} → {}, {} chars",
                    companion, call.id(), call.name(), ToolOutcome.failed(settled.result()) ? "failed" : "ok",
                    settled.result().length());
            done.accept(settled);
        };
        inFlightDone = landed;
        return landed;
    }
}
