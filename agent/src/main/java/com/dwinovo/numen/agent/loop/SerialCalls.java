package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.provider.LlmToolCall;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 模型一次回复里的调用按顺序执行:一个做完了才派下一个。工具口({@link ToolPort})的实现把"怎么执行一个调用"交给
 * {@link Invoker},顺序与等待都在这里。
 *
 * <h2>做完是什么意思</h2>
 * 一个调用的结果回来就交给内核进历史。结果说它留下了一件还在跑、会自己收尾的身体任务(受理回执),而后面还有调用时,
 * 身体动作要做完才往下走:这件任务的收尾进了队列,才派下一个。所以同一轮里写的几件身体动作一件接一件做完,不会让后一件
 * 顶掉前一件;常驻的活(跟随)没有收尾,不等;最后一件受理了这一批就结算,活在后台做、她照常说话。判据是确定的事实——
 * 那件任务的收尾到没到——不猜"是不是同一批"。
 *
 * <h2>等的时候来了急件</h2>
 * 等身体收尾期间进来一条要立刻叫醒她的输入(主人说话、急事):不再等,还没派出去的调用各回一条"没执行"的结果写明原因,
 * 这一批结算,每个调用恰好一个结果。模型下一次调用时读到那条输入和这些结果,重新决定;等的那件活照常跑。
 * 工具本身在跑的时候不看输入:它们有界短,结算之后输入跟下一次调用走(队列的插话档)。
 *
 * <p>纯 JVM。一切状态只在内核的线程上读写;{@link Invoker} 的结果回调要切回这个线程再交进来。
 */
public final class SerialCalls {

    /** 执行一个调用。结果经 {@code done} 恰好交回一次,当场或之后都行;交回之后再来的不算。 */
    @FunctionalInterface
    public interface Invoker {
        void invoke(LlmToolCall call, Consumer<String> done);
    }

    private final Invoker invoker;
    /** 一个调用的结果留下的、还在跑且会自己收尾的身体任务的编号;没有是 null。 */
    private final Function<String, String> leftRunning;
    /** 一条输入是哪件身体任务的收尾:那件的编号;不是收尾是 null。 */
    private final Function<EventQueue.Entry, String> finishes;

    /** 这一批还没派出去的调用。 */
    private final Deque<LlmToolCall> queue = new ArrayDeque<>();
    /** 派出去、结果还没回来的那一个;没有是 null。 */
    private LlmToolCall inFlight;
    /** 正在等哪件身体任务收尾;不在等是 null。 */
    private String awaiting;
    /** 这一批的回报口;结算之后摘掉,之后再来的结果无处可报。 */
    private ToolPort.Sink sink;
    /** 正在 {@link #advance} 的那一圈里:当场回来的结果不递归,由这一圈接着往下走。 */
    private boolean advancing;

    /**
     * @param leftRunning 结果 → 它留下的、会自己收尾的身体任务的编号(没有是 null)
     * @param finishes    输入 → 它收尾的那件身体任务的编号(不是收尾是 null)
     */
    public SerialCalls(Invoker invoker, Function<String, String> leftRunning,
                       Function<EventQueue.Entry, String> finishes) {
        this.invoker = invoker;
        this.leftRunning = leftRunning;
        this.finishes = finishes;
    }

    /** 收下一批调用,按顺序执行;每个派出、结算时经 {@code sink} 各报一次,全部结算后报一次 settled。 */
    public void run(List<LlmToolCall> calls, ToolPort.Sink sink) {
        this.sink = sink;
        queue.addAll(calls);
        advance();
    }

    /**
     * 一条输入进了队列,{@code urgent} 是它要不要立刻叫醒她(队列的急件规则算出来的)。只在等身体收尾时有用:它是那件的
     * 收尾就接着派下一个;它是急件就不再等,余下的调用逐条回"没执行"。
     */
    public void arrived(EventQueue.Entry entry, boolean urgent) {
        if (awaiting == null) {
            return;
        }
        if (awaiting.equals(finishes.apply(entry))) {
            awaiting = null;
            advance();
            return;
        }
        if (urgent) {
            String why = notRun(awaiting, entry);
            awaiting = null;
            ToolPort.Sink reportTo = sink;
            List<LlmToolCall> dropped = new ArrayList<>(queue);
            queue.clear();
            for (LlmToolCall call : dropped) {
                reportTo.finished(call, why);
            }
            advance();
        }
    }

    /** 放弃这一批里还没结果的调用(在飞的与排着的),返回它们的 id;等着的那件身体任务不归这里管。 */
    public List<String> cancel() {
        List<String> ids = new ArrayList<>();
        if (inFlight != null) {
            ids.add(inFlight.id());
        }
        for (LlmToolCall call : queue) {
            ids.add(call.id());
        }
        inFlight = null;
        queue.clear();
        awaiting = null;
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

    /**
     * 往下走:没有在飞的、也不在等身体收尾,就派下一个;这一批派完了就报 settled。当场回来的结果、settled 里当场收下的
     * 下一批都由这一圈接着走,不递归。
     */
    private void advance() {
        if (advancing) {
            return;
        }
        advancing = true;
        try {
            while (inFlight == null && awaiting == null && sink != null) {
                LlmToolCall call = queue.poll();
                if (call == null) {
                    ToolPort.Sink done = sink;
                    sink = null;
                    done.settled();
                    continue;
                }
                inFlight = call;
                sink.started(call);
                invoker.invoke(call, json -> finish(call, json));
            }
        } finally {
            advancing = false;
        }
    }

    private void finish(LlmToolCall call, String resultJson) {
        if (inFlight != call) {
            return;   // 已经被放弃,或者重复、迟到的结果
        }
        inFlight = null;
        sink.finished(call, resultJson);
        // 后面还有调用才等:最后一件受理了,这一批就结算,活在后台做、她照常说话
        awaiting = queue.isEmpty() ? null : leftRunning.apply(resultJson);
        advance();
    }

    /** 没执行的调用拿到的那条结果:在等哪件活、来了什么、那件活照常跑、先读那条输入再决定。 */
    private static String notRun(String awaited, EventQueue.Entry entry) {
        String what = EventTypes.get(entry.type()).ownerWords()
                ? "your owner spoke"
                : "an urgent " + entry.type() + " event arrived";
        return ToolOutcome.failure("Not run: while you were waiting for " + awaited + " to finish, " + what
                + ". " + awaited + " keeps running; read it, then decide what to do next.");
    }
}
