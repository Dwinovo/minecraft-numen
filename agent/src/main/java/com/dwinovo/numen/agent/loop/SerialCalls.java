package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.script.Program;
import com.dwinovo.numen.agent.script.ScriptCall;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * 模型一次回复里的调用按顺序执行:一个做完了才派下一个。工具口({@link ToolPort})的实现把"怎么执行一个调用"交给 {@link Port},
 * 顺序与等待都在这里。
 *
 * <h2>程序</h2>
 * 跑 Lua 的那个工具的调用是一整段程序。程序不在这里跑:{@link Port#invoke} 把它整段送去服务端,服务端在身体与数据旁边跑完,
 * 回一张回执(连同每次 API 调用的结局)。这里只管顺序:程序在服务端跑的时候,这一批后面的调用等着;回来了才派下一个。
 *
 * <h2>等的时候来了急件、被切断</h2>
 * 程序在服务端跑着,这里来了一条要立刻叫醒她的输入(主人说话、急事,急不急是收件箱的规则算的):告诉服务端让程序停在调用之间
 * ({@link Port#interrupt}),不等它跑完;服务端交回的结局写明程序是被叫停的,这一批余下还没派的调用各回一条"没执行"。
 * 切断({@link #cancel}):程序当场叫停,这一批所有未决的调用都作废、返回它们的 id。
 * 单个工具在跑的时候不看输入:它们有界短,结算之后输入跟下一次调用走(队列的插话档)。
 *
 * <p>纯 JVM。一切状态只在内核的线程上读写;{@link Port} 的结果回调要切回这个线程再交进来。
 */
public final class SerialCalls {

    /**
     * 一个调用的结局。{@code ending} 是程序怎么结束的(结构化的,给评测读);不是程序的调用是 null。
     */
    public record Settled(String result, ScriptCall.Ending ending, List<ScriptCall.Called> calls, String stoppedFor) {

        /** 一个工具的结果:没有程序里的调用,不是被叫停的。 */
        public static Settled of(String result) {
            return new Settled(result, null, List.of(), null);
        }
    }

    /** 执行调用要的几样东西。 */
    public interface Port {

        /** 执行一个调用。结局经 {@code done} 恰好交回一次,当场或之后都行;交回之后再来的不算。 */
        void invoke(LlmToolCall call, Consumer<Settled> done);

        /** 这个调用是不是一段在服务端跑的程序(可以叫它停)。 */
        boolean isProgram(LlmToolCall call);

        /** 让服务端上的这段程序停在调用之间,{@code why} 是给模型的原因。 */
        void interrupt(LlmToolCall program, String why);

        /**
         * 这一轮被切断,让服务端上的这段程序当场停下。
         *
         * @param stopBody 身体是不是一起叫停(叫停本身由端口做),回执照实说
         */
        void cutOff(LlmToolCall program, boolean stopBody);
    }

    private final Port port;

    /** 这一批还没派出去的调用。 */
    private final Deque<LlmToolCall> queue = new ArrayDeque<>();
    /** 派出去、结果还没回来的那一个;没有是 null。 */
    private LlmToolCall inFlight;
    /** 在飞的程序已经被告知停在调用之间(同一条急件只说一次)。 */
    private boolean interrupted;
    /** 这一批的回报口;结算之后摘掉,之后再来的结果无处可报。 */
    private ToolPort.Sink sink;
    /** 正在 {@link #advance} 的那一圈里:当场回来的结果不递归,由这一圈接着往下走。 */
    private boolean advancing;

    public SerialCalls(Port port) {
        this.port = port;
    }

    /** 收下一批调用,按顺序执行;每个派出、结算时经 {@code sink} 各报一次,全部结算后报一次 settled。 */
    public void run(List<LlmToolCall> calls, ToolPort.Sink sink) {
        this.sink = sink;
        queue.addAll(calls);
        advance();
    }

    /**
     * 一条输入到了,{@code urgent} 是它要不要立刻叫醒她(队列的急件规则算出来的)。一段程序在服务端跑着:是急件就让它停在调用之间。
     */
    public void arrived(EventQueue.Entry entry, boolean urgent) {
        if (urgent && inFlight != null && !interrupted && port.isProgram(inFlight)) {
            interrupted = true;
            port.interrupt(inFlight, Program.what(entry));
        }
    }

    /**
     * 放弃这一批里还没结果的调用(在飞的与排着的),返回它们的 id;等着的那件身体任务不归这里管。在飞的是程序的话,先叫服务端当场
     * 停下它。
     *
     * @param stopBody 身体是不是一起叫停:回执照实说那件活停了还是照常跑
     */
    public List<String> cancel(boolean stopBody) {
        List<String> ids = new ArrayList<>();
        if (inFlight != null) {
            if (port.isProgram(inFlight)) {
                port.cutOff(inFlight, stopBody);
            }
            ids.add(inFlight.id());
        }
        for (LlmToolCall call : queue) {
            ids.add(call.id());
        }
        inFlight = null;
        interrupted = false;
        queue.clear();
        sink = null;
        advancing = false;
        return ids;
    }

    /** 这个调用的结果还会不会来:在飞,或者还排着。 */
    public boolean holds(String callId) {
        if (inFlight != null && inFlight.id().equals(callId)) {
            return true;
        }
        return queue.stream().anyMatch(call -> call.id().equals(callId));
    }

    /** 在飞的那一个;没有是 null。 */
    public LlmToolCall inFlight() {
        return inFlight;
    }

    /** 手上这一个:在飞的,没有就是下一个要派的;都没有是 null。 */
    public LlmToolCall current() {
        return inFlight != null ? inFlight : queue.peek();
    }

    /** 往下走:没有在飞的就派下一个;这一批派完了就报 settled。当场回来的结果、settled 里当场收下的下一批都由这一圈接着走,不递归。 */
    private void advance() {
        if (advancing) {
            return;
        }
        advancing = true;
        try {
            while (inFlight == null && sink != null) {
                LlmToolCall call = queue.poll();
                if (call == null) {
                    ToolPort.Sink done = sink;
                    sink = null;
                    done.settled();
                    continue;
                }
                sink.started(call);
                inFlight = call;
                interrupted = false;
                port.invoke(call, settled -> finish(call, settled));
            }
        } finally {
            advancing = false;
        }
    }

    private void finish(LlmToolCall call, Settled settled) {
        if (inFlight != call) {
            return;   // 已经被放弃,或者重复、迟到的结果
        }
        inFlight = null;
        if (settled.ending() != null) {
            sink.ended(call, settled.ending(), settled.calls());
        }
        sink.finished(call, settled.result());
        for (ScriptCall.Called called : settled.calls()) {
            sink.called(call, called);
        }
        if (settled.stoppedFor() != null) {
            dropRest(settled.stoppedFor());
        }
        advance();
    }

    /** 余下还没派的调用各回一条"没执行"。 */
    private void dropRest(String stoppedFor) {
        List<LlmToolCall> dropped = new ArrayList<>(queue);
        queue.clear();
        String why = ToolOutcome.failure("Not run: the program before it was stopped (" + stoppedFor
                + "); read what came in, then decide what to do next.");
        for (LlmToolCall call : dropped) {
            sink.finished(call, why);
        }
    }
}
