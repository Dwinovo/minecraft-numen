package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.lua.ScriptCall;
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
 * 一个调用的结果回来就交给内核进历史。结果说它留下了一件还在跑、会自己收尾的身体任务(受理回执),而后面还有调用时,
 * 身体动作要做完才往下走:这件任务的收尾进了队列,才派下一个。所以同一轮里写的几件身体动作一件接一件做完,不会让后一件
 * 顶掉前一件;常驻的活(跟随)没有收尾,不等;最后一件受理了这一批就结算,活在后台做、她照常说话。判据是确定的事实——
 * 那件任务的收尾到没到——不猜"是不是同一批"。
 *
 * <h2>脚本</h2>
 * 一个调用是一段 Lua({@code lua} 工具),或者它的回执说"去跑这一份"({@code script run}),这个调用就是一段脚本
 * ({@link ScriptCall}):脚本每调一个命令函数,这里把那一行当一条普通命令派出去({@link Port#commandCall}),等它的回执;
 * 留下了身体活就用同一个等法等它收尾,再让脚本从调用处接着跑。脚本跑完,它的回执才是这个调用的结果。脚本里的命令都要等收尾:
 * 脚本要按它的结局往下走。
 *
 * <h2>等的时候来了急件</h2>
 * 等身体收尾期间进来一条要立刻叫醒她的输入(主人说话、急事):不再等,还没派出去的调用各回一条"没执行"的结果写明原因,
 * 这一批结算,每个调用恰好一个结果。模型下一次调用时读到那条输入和这些结果,重新决定;等的那件活照常跑。
 * 单个工具在跑的时候不看输入:它们有界短,结算之后输入跟下一次调用走(队列的插话档)。脚本不一样,它由许多条命令组成:
 * 一行命令在跑时来了急件,这一行的回执到了就停,停在命令之间,回执写明停在哪一行、哪些做了。
 *
 * <p>纯 JVM。一切状态只在内核的线程上读写;{@link Port} 的结果回调要切回这个线程再交进来。
 */
public final class SerialCalls {

    /** 执行调用与脚本要的几样东西。 */
    public interface Port extends ScriptCall.Host {

        /** 执行一个调用。结果经 {@code done} 恰好交回一次,当场或之后都行;交回之后再来的不算。 */
        void invoke(LlmToolCall call, Consumer<String> done);

        /**
         * 这个调用是不是一段 Lua 脚本:是就返回它的正文,别的工具是 null。
         *
         * @throws IllegalArgumentException 是脚本工具,但参数写错了;消息就是给模型的那句话
         */
        String luaOf(LlmToolCall call);

        /** 脚本里的一行命令写成一次调用,和模型直接调一行命令是同一个工具。 */
        LlmToolCall commandCall(String id, String line);

        /** 一个调用的结果留下的、还在跑且会自己收尾的身体任务的编号;没有是 null。 */
        String leftRunning(String resultJson);

        /** 一条输入是哪件身体任务的收尾;不是收尾是 null。 */
        ScriptCall.Finish finish(EventQueue.Entry entry);
    }

    private final Port port;

    /** 这一批还没派出去的调用。 */
    private final Deque<LlmToolCall> queue = new ArrayDeque<>();
    /** 派出去、结果还没回来的那一个(脚本的一行也是);没有是 null。 */
    private LlmToolCall inFlight;
    /** 正在等哪件身体任务收尾;不在等是 null。 */
    private String awaiting;
    /** 正在跑的脚本与它所属的那个调用;没有是 null。 */
    private ScriptCall script;
    private LlmToolCall scriptCall;
    /** 脚本那一行在跑时来的急件:这一行的回执到了就停;没有是 null。 */
    private EventQueue.Entry interruptedBy;
    /** 脚本里派出的行的编号。 */
    private int lineSeq;
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
     * 一条输入进了队列,{@code urgent} 是它要不要立刻叫醒她(队列的急件规则算出来的)。等身体收尾时:它是那件的收尾就接着走;
     * 它是急件就不再等。脚本的一行在跑时来了急件:记下,这一行的回执到了就停。
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
            if (script != null) {
                next = script.finished(finish);
            }
            advance();
            return;
        }
        if (urgent) {
            String waited = awaiting;
            awaiting = null;
            if (script != null) {
                endScript(script.stop(what(entry) + "; " + waited + " keeps running"));
            }
            dropRest(notRun("while you were waiting for " + waited + " to finish, " + what(entry) + ". " + waited
                    + " keeps running"));
            advance();
        }
    }

    /**
     * 放弃这一批里还没结果的调用(在飞的与排着的),返回它们的 id;等着的那件身体任务不归这里管。在跑的脚本这时交出它的回执——
     * 停在哪一行、哪些做了——作为它那个调用的结果,所以它不在返回的 id 里;脚本那一行在飞的调用在里面。
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

    /** 在飞的那一个(脚本里的一行也是);没有是 null。 */
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
     * 往下走:没有在飞的、也不在等身体收尾,就派下一个——脚本在跑就是脚本的下一行,否则是下一个调用;这一批派完了就报 settled。
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
                    lua = port.luaOf(call);
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
        lineSeq = 0;
        next = started.begin();
    }

    /** 脚本的下一步:派一行、等一件活,或者脚本结束、交出这个调用的结果。 */
    private void stepScript() {
        ScriptCall.Next step = next;
        next = null;
        switch (step) {
            case ScriptCall.Next.Dispatch d -> {
                LlmToolCall line = port.commandCall(scriptCall.id() + "#" + (++lineSeq), d.line());
                inFlight = line;
                port.invoke(line, json -> lineResult(line, json));
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
        String running = port.leftRunning(resultJson);
        if (interruptedBy != null) {
            EventQueue.Entry by = interruptedBy;
            interruptedBy = null;
            String body = running == null ? "" : "; " + running + " keeps running";
            endScript(script.stop(what(by) + body));
            dropRest(notRun("while your script was running, " + what(by)));
        } else {
            next = script.result(resultJson, running);
        }
        advance();
    }

    /** 脚本结束:它的回执是那个调用的结果;和别的调用一样,后面还有调用而它留下了身体活,就等那件收尾。 */
    private void endScript(String receipt) {
        LlmToolCall call = scriptCall;
        script = null;
        scriptCall = null;
        next = null;
        interruptedBy = null;
        sink.finished(call, receipt);
        awaiting = queue.isEmpty() ? null : port.leftRunning(receipt);
    }

    private void finish(LlmToolCall call, String resultJson) {
        if (inFlight != call) {
            return;   // 已经被放弃,或者重复、迟到的结果
        }
        inFlight = null;
        ScriptCall.ToRun toRun = ScriptCall.toRun(resultJson);
        if (toRun != null) {
            startScript(call, ScriptCall.named(toRun, port));
            advance();
            return;
        }
        sink.finished(call, resultJson);
        // 后面还有调用才等:最后一件受理了,这一批就结算,活在后台做、她照常说话
        awaiting = queue.isEmpty() ? null : port.leftRunning(resultJson);
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
