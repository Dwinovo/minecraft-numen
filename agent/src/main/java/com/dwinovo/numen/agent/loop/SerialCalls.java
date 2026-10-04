package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.script.ApiReply;
import com.dwinovo.numen.agent.script.Invocation;
import com.dwinovo.numen.agent.script.ScriptCall;
import com.dwinovo.numen.agent.provider.LlmToolCall;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * 模型一次回复里的调用按顺序执行:一个做完了才派下一个。工具口({@link ToolPort})的实现把"怎么执行一个调用"交给
 * {@link Port},顺序与等待都在这里。
 *
 * <h2>做完是什么意思</h2>
 * 一个调用的结果回来就交给内核进历史,再派下一个。身体活只经程序派:程序里的每件活在程序里等它收尾(见下),所以一个调用
 * 结果回来时,它派的活已经做完了;别的工具(装技能、接进来的 MCP 工具)不派身体活。
 *
 * <h2>脚本</h2>
 * 一个调用是一段程序(跑脚本的那个工具),这个调用就是一段脚本({@link ScriptCall}):脚本每调一个 API 函数,这里把那次调用派出去
 * ({@link Port#dispatch}),等它的回执;留下了身体活就等它收尾,再让脚本从调用处接着跑。脚本跑完,它的回执才是这个
 * 调用的结果。脚本里的调用都要等收尾:脚本要按它的结局往下走。脚本等着的那件活的收尾归脚本({@link #awaits}):结局交给程序,账写进
 * 回执,不进队列;脚本停下时还在跑的活,收尾进队列成一条事件。
 *
 * <h2>等的时候来了急件</h2>
 * 脚本等身体收尾期间进来一条要立刻叫醒她的输入(主人说话、急事):不再等,脚本停下,还没派出去的调用各回一条"没执行"的结果写明原因,
 * 这一批结算,每个调用恰好一个结果。模型下一次调用时读到那条输入和这些结果,重新决定;等的那件活照常跑。
 * 单个工具在跑的时候不看输入:它们有界短,结算之后输入跟下一次调用走(队列的插话档)。脚本不一样,它由许多次 API 调用组成:
 * 一次调用在跑时来了急件,它的回执到了就停,停在调用之间,回执写明停在哪一行、哪些做了。
 *
 * <p>纯 JVM。一切状态只在内核的线程上读写;{@link Port} 的结果回调要切回这个线程再交进来。
 */
public final class SerialCalls {

    /** 执行调用与脚本要的几样东西。 */
    public interface Port extends ScriptCall.Host {

        /** 执行一个调用。结果经 {@code done} 恰好交回一次,当场或之后都行;交回之后再来的不算。 */
        void invoke(LlmToolCall call, Consumer<String> done);

        /**
         * 这个调用是不是一段程序(跑脚本的那个工具):是就返回它的正文,别的工具是 null。
         *
         * @throws IllegalArgumentException 是脚本工具,但参数写错了;消息就是给模型的那句话
         */
        String scriptOf(LlmToolCall call);

        /**
         * 执行脚本里的一次 API 调用。结果({@link com.dwinovo.numen.agent.script.ApiReply} 写的那一份)经 {@code done} 恰好交回一次,
         * 当场或之后都行。
         *
         * @param call       这次调用的编号与写法(函数名、参数 JSON),在飞时它就是手上的那一个
         * @param invocation 读好的函数与参数
         */
        void dispatch(LlmToolCall call, Invocation invocation, Consumer<String> done);

        /** 一条输入是哪件身体任务的收尾;不是收尾是 null。 */
        ScriptCall.Finish finish(EventQueue.Entry entry);
    }

    private final Port port;

    /** 这一批还没派出去的调用。 */
    private final Deque<LlmToolCall> queue = new ArrayDeque<>();
    /** 派出去、结果还没回来的那一个(脚本里的一次 API 调用也是);没有是 null。 */
    private LlmToolCall inFlight;
    /** 脚本正在等哪件身体任务收尾;不在等是 null。 */
    private String awaiting;
    /** 正在跑的脚本与它所属的那个调用;没有是 null。 */
    private ScriptCall script;
    private LlmToolCall scriptCall;
    /** 脚本里一次调用在跑时来的急件:它的回执到了就停;没有是 null。 */
    private EventQueue.Entry interruptedBy;
    /** 脚本里派出的调用的编号。 */
    private int callSeq;
    /** 这一批的回报口;结算之后摘掉,之后再来的结果无处可报。 */
    private ToolPort.Sink sink;
    /** 正在 {@link #advance} 的那一圈里:当场回来的结果不递归,由这一圈接着往下走。 */
    private boolean advancing;
    /** 脚本接下来要做的事;没有是 null。 */
    private ScriptCall.Next next;

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
     * 这条输入是不是正在跑的程序等着的那件身体活的收尾。是就归这段程序:它的结局交给程序、它的账写进程序的回执,内核不再把它
     * 放进队列——一件活的收尾只说一次。程序停下之后还在跑的活没有程序等它,它的收尾照常进队列,是一条事件。
     */
    public boolean awaits(EventQueue.Entry entry) {
        if (awaiting == null) {
            return false;
        }
        ScriptCall.Finish finish = port.finish(entry);
        return finish != null && awaiting.equals(finish.task());
    }

    /**
     * 一条输入到了,{@code urgent} 是它要不要立刻叫醒她(队列的急件规则算出来的)。等身体收尾时:它是那件的收尾就接着走;
     * 它是急件就不再等。脚本里一次调用在跑时来了急件:记下,它的回执到了就停。
     */
    public void arrived(EventQueue.Entry entry, boolean urgent) {
        if (awaiting == null) {
            if (script != null && inFlight != null && urgent && interruptedBy == null) {
                interruptedBy = entry;
            }
            return;
        }
        ScriptCall.Finish finish = port.finish(entry);
        if (finish != null && awaiting.equals(finish.task())) {
            awaiting = null;
            next = script.finished(finish);
            reportCalled();
            advance();
            return;
        }
        if (urgent) {
            String waited = awaiting;
            awaiting = null;
            endScript(script.stop(what(entry) + "; " + waited + " keeps running"));
            dropRest(notRun("while you were waiting for " + waited + " to finish, " + what(entry) + ". " + waited
                    + " keeps running"));
            advance();
        }
    }

    /**
     * 放弃这一批里还没结果的调用(在飞的与排着的),返回它们的 id;等着的那件身体任务不归这里管。在跑的脚本这时交出它的回执——
     * 停在哪一行、哪些做了——作为它那个调用的结果,所以它不在返回的 id 里;脚本里在飞的那次 API 调用在里面。
     *
     * @param stopBody 身体是不是一起叫停:回执照实说那件活停了还是照常跑
     */
    public List<String> cancel(boolean stopBody) {
        List<String> ids = new ArrayList<>();
        if (inFlight != null) {
            ids.add(inFlight.id());
        }
        if (script != null && sink != null) {
            String body = awaiting == null ? ""
                    : "; " + awaiting + (stopBody ? " was stopped too" : " keeps running");
            sink.finished(scriptCall, script.stop("this turn was cut off" + body));
        }
        for (LlmToolCall call : queue) {
            ids.add(call.id());
        }
        inFlight = null;
        queue.clear();
        awaiting = null;
        script = null;
        scriptCall = null;
        next = null;
        interruptedBy = null;
        sink = null;
        advancing = false;
        return ids;
    }

    /** 这个调用的结果还会不会来:在飞,排着,或者是正在跑的那段脚本。 */
    public boolean holds(String callId) {
        if (inFlight != null && inFlight.id().equals(callId)) {
            return true;
        }
        if (scriptCall != null && scriptCall.id().equals(callId)) {
            return true;
        }
        return queue.stream().anyMatch(call -> call.id().equals(callId));
    }

    /** 在飞的那一个(脚本里的一次 API 调用也是);没有是 null。 */
    public LlmToolCall inFlight() {
        return inFlight;
    }

    /** 手上这一个:在跑的脚本,在飞的,没有就是下一个要派的;都没有是 null。 */
    public LlmToolCall current() {
        if (scriptCall != null) {
            return scriptCall;
        }
        return inFlight != null ? inFlight : queue.peek();
    }

    /**
     * 往下走:没有在飞的、也不在等身体收尾,就派下一个——脚本在跑就是脚本的下一次 API 调用,否则是下一个调用;这一批派完了就报 settled。
     * 当场回来的结果、settled 里当场收下的下一批都由这一圈接着走,不递归。
     */
    private void advance() {
        if (advancing) {
            return;
        }
        advancing = true;
        try {
            while (inFlight == null && awaiting == null && sink != null) {
                if (script != null) {
                    stepScript();
                    continue;
                }
                LlmToolCall call = queue.poll();
                if (call == null) {
                    ToolPort.Sink done = sink;
                    sink = null;
                    done.settled();
                    continue;
                }
                sink.started(call);
                String lua;
                try {
                    lua = port.scriptOf(call);
                } catch (IllegalArgumentException invalid) {
                    sink.finished(call, ToolOutcome.failure("invalid arguments: " + invalid.getMessage()));
                    continue;
                }
                if (lua != null) {
                    startScript(call, ScriptCall.inline(lua, port));
                    continue;
                }
                inFlight = call;
                port.invoke(call, json -> finish(call, json));
            }
        } finally {
            advancing = false;
        }
    }

    private void startScript(LlmToolCall call, ScriptCall started) {
        script = started;
        scriptCall = call;
        callSeq = 0;
        next = started.begin();
        reportCalled();
    }

    /** 脚本里有了结局的调用逐个报出去(评测按函数统计)。 */
    private void reportCalled() {
        for (ScriptCall.Called c : script.drainCalled()) {
            sink.called(scriptCall, c);
        }
    }

    /** 脚本的下一步:派一次 API 调用、等一件活,或者脚本结束、交出这个调用的结果。 */
    private void stepScript() {
        ScriptCall.Next step = next;
        next = null;
        switch (step) {
            case ScriptCall.Next.Dispatch d -> {
                Invocation invocation = d.invocation();
                LlmToolCall line = new LlmToolCall(scriptCall.id() + "#" + (++callSeq), invocation.function(),
                        invocation.args().toString());
                inFlight = line;
                port.dispatch(line, invocation, json -> lineResult(line, json));
            }
            case ScriptCall.Next.Await a -> awaiting = a.task();
            case ScriptCall.Next.Done d -> endScript(d.receipt());
        }
    }

    private void lineResult(LlmToolCall line, String resultJson) {
        if (inFlight != line) {
            return;   // 已经被放弃,或者重复、迟到的结果
        }
        inFlight = null;
        if (interruptedBy != null) {
            EventQueue.Entry by = interruptedBy;
            interruptedBy = null;
            ApiReply.Parsed reply = ApiReply.parse(resultJson);
            String body = reply.ok() && reply.job() != null ? "; " + reply.job() + " keeps running" : "";
            endScript(script.stop(what(by) + body));
            dropRest(notRun("while your script was running, " + what(by)));
        } else {
            next = script.result(resultJson);
            reportCalled();
        }
        advance();
    }

    /** 脚本结束:它的回执是那个调用的结果。 */
    private void endScript(String receipt) {
        reportCalled();
        LlmToolCall call = scriptCall;
        script = null;
        scriptCall = null;
        next = null;
        interruptedBy = null;
        sink.finished(call, receipt);
    }

    private void finish(LlmToolCall call, String resultJson) {
        if (inFlight != call) {
            return;   // 已经被放弃,或者重复、迟到的结果
        }
        inFlight = null;
        sink.finished(call, resultJson);
        advance();
    }

    /** 余下还没派的调用各回一条"没执行"。 */
    private void dropRest(String why) {
        List<LlmToolCall> dropped = new ArrayList<>(queue);
        queue.clear();
        for (LlmToolCall call : dropped) {
            sink.finished(call, why);
        }
    }

    /** 来的是什么:主人开口,或某种急事。 */
    private static String what(EventQueue.Entry entry) {
        return EventTypes.get(entry.type()).ownerWords()
                ? "your owner spoke"
                : "an urgent " + entry.type() + " event arrived";
    }

    /** 没执行的调用拿到的那条结果:为什么没执行、先读那条输入再决定。 */
    private static String notRun(String why) {
        return ToolOutcome.failure("Not run: " + why + "; read it, then decide what to do next.");
    }
}
