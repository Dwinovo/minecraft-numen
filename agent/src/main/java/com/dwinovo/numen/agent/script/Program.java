package com.dwinovo.numen.agent.script;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.ai.AiLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 一段程序从头跑到回执,整个过程在它自己的执行体上({@code serial}),不占任何别的线程:{@link ScriptCall} 把程序换成一次次 API 调用,
 * 这里把每次调用交给 {@link Port} 去执行、等它的结果,留下身体活就等那件活收尾,再让程序从调用处接着跑,直到 {@link ScriptCall}
 * 交出回执。两次调用之间脚本在它自己的虚拟线程上算,这个执行体等它算完(指令预算管着);执行调用的那一边(服务端主线程)永远不等脚本。
 *
 * <h2>谁在哪个线程</h2>
 * 状态只在 {@code serial} 上读写。{@link #arrived}、{@link #taskFinished}、{@link #interrupt}、{@link #cancel} 与调用的结果回调
 * 从任何线程调都行,各自把活交给 {@code serial}。同一个 {@link SerialExecutor} 先交先跑,所以一件活的收尾一定排在受理它的那次调用
 * 的结果之后。
 *
 * <h2>等的时候来了急件</h2>
 * 程序等身体收尾期间来了急件(急不急是收件箱那一条规则,{@link EventQueue#isUrgent}):不再等,程序停下,回执写明停在哪一行,
 * 等的那件活照常跑。一次调用在跑时来了急件:记下,它的结果到了就停,停在调用之间。主人开口、按停止键这类客户端那边的事,客户端上行
 * 一个停止({@link #interrupt}、{@link #cancel}),这里不再另判。
 *
 * <p>纯 JVM。
 */
public final class Program {

    /** 程序要的几样东西。 */
    public interface Port {

        /** 程序能调的函数与库。 */
        ScriptCatalog catalog();

        /**
         * 程序里的一次 API 调用读成一个函数和它的参数。
         *
         * @throws ApiError 读不成:抛给脚本的就是它
         */
        Invocation invocation(ScriptRun.Call call);

        /** 执行一次 API 调用,结果({@link ApiReply} 写的那一份)经 {@code done} 恰好交回一次,任何线程、当场或之后都行。 */
        void dispatch(String callId, Invocation invocation, Consumer<String> done);

        /** 一条输入是哪件身体活的收尾;不是收尾是 null。 */
        ScriptCall.Finish finish(EventQueue.Entry entry);

        /**
         * 程序等的那件活的收尾已经归了它,到的时候程序却不在等了(停了、结束了):收尾不能就此消失,照常送给主人的大脑。
         */
        void forward(EventQueue.Entry entry);

        /** 墙钟,毫秒。 */
        long now();
    }

    /** 用到了某个模块的这段程序的结局,客户端据此记那个模块的战绩。 */
    public record Used(String module, ScriptCall.Tally tally) {}

    /**
     * 一段程序跑完了。
     *
     * @param receipt 回执:成败与文字({@code success}、{@code message}),交给模型的全部
     * @param ending  怎么结束的(跑完、出错、被停下),出错时错误值的种类;客户端与评测读它,不读回执的文字
     * @param calls   每次 API 调用的结局,按先后
     * @param used    用到的每个模块和这段程序的结局
     * @param stoppedFor 程序是被叫停的(主人开口、急件、切断)时,为什么停,一句话,含等着的那件活怎样了("your owner spoke; t8 keeps
     *                   running");别的结局是 null。客户端据此给同一批里没执行的调用写原因,不去读回执的文字
     * @param returned   程序 {@code return} 的值原样;只在程序跑的进程里有(同进程的测试与工具读),不上网线,从线上读回来的是 null
     * @param failure    出错时完整的错误值({@code kind}、{@code message}、{@code hint}、{@code fn}、{@code data});同 {@code returned},不上网线
     */
    public record Outcome(String receipt, ScriptCall.Ending ending, List<ScriptCall.Called> calls, List<Used> used,
                          String stoppedFor, Object returned, java.util.Map<String, Object> failure) {

        /** 从线上读回来的结局:没有 {@code returned} 与 {@code failure}。 */
        public Outcome(String receipt, ScriptCall.Ending ending, List<ScriptCall.Called> calls, List<Used> used,
                       String stoppedFor) {
            this(receipt, ending, calls, used, stoppedFor, null, null);
        }
    }

    private final String id;
    private final String code;
    private final Port port;
    private final Executor serial;
    private final Consumer<Outcome> ended;
    private final List<ScriptCall.Called> calls = new ArrayList<>();
    private final List<Used> used = new ArrayList<>();

    private ScriptCall script;
    /** 程序接下来要做的事;没有是 null。 */
    private ScriptCall.Next next;
    /** 派出去、结果还没回来的那次调用的编号;没有是 null。 */
    private String inFlight;
    /** 程序正在等哪件身体任务收尾;不在等是 null。 */
    private String awaiting;
    /** 一次调用在跑时来的急件:它的结果到了就停;没有是 null。 */
    private String interruptedBy;
    private int callSeq;
    /** 正在 {@link #advance} 的那一圈里:当场回来的结果不递归,由这一圈接着往下走。 */
    private boolean advancing;
    private boolean done;

    /**
     * @param id     这段程序的编号;它里面第 n 次调用的编号是 {@code <id>#<n>}
     * @param serial 这段程序的执行体,调用方保证同一时刻至多一个任务在上面跑({@link SerialExecutor});测试用当场执行的
     * @param ended  跑完时交出结局,恰好一次,在 {@code serial} 上
     */
    public Program(String id, String code, Port port, Executor serial, Consumer<Outcome> ended) {
        this.id = id;
        this.code = code;
        this.port = port;
        this.serial = serial;
        this.ended = ended;
    }

    /** 开跑。 */
    public void start() {
        confined(() -> {
            script = ScriptCall.inline(code, new Recording());
            next = script.begin();
            collect();
            advance();
        });
    }

    /**
     * 服务端发出了一条事件。它是程序等着的那件活的收尾,程序就接着走;是急件就让程序停在调用之间;都不是就不管。主人的话、客户端
     * 合成的事件不经这里:客户端那边判,上行 {@link #interrupt}。
     */
    public void arrived(EventQueue.Entry entry) {
        confined(() -> observe(entry));
    }

    /**
     * 程序等着的那件活收尾了,并且这条事件归了这段程序(不再另送给主人的大脑,一件活的收尾只说一次):结局交给程序,账写进回执。
     * 到的时候程序不在等它了(停了、结束了),就转交 {@link Port#forward},收尾不能就此消失。
     */
    public void taskFinished(EventQueue.Entry entry) {
        confined(() -> {
            if (!observe(entry)) {
                port.forward(entry);
            }
        });
    }

    /** 让程序停在调用之间,{@code why} 是给模型的原因(主人开口、客户端那边来了急件)。 */
    public void interrupt(String why) {
        confined(() -> {
            if (!done) {
                urgent(why);
            }
        });
    }

    /**
     * 这一轮被切断(主人按停止、死亡、登出、外接接管):程序当场停下,回执写明停在哪一行。在飞的那次调用的结果不再要。
     *
     * @param stopBody 身体活是不是一起叫停(叫停本身由调用方做):回执照实说那件活停了还是照常跑
     */
    public void cancel(boolean stopBody) {
        confined(() -> {
            if (done) {
                return;
            }
            String body = awaiting == null ? "" : "; " + awaiting + (stopBody ? " was stopped too" : " keeps running");
            inFlight = null;
            awaiting = null;
            String why = "this turn was cut off" + body;
            end(script.stop(why), why);
        });
    }

    /** 来的是什么:主人开口,或某种急事。 */
    public static String what(EventQueue.Entry entry) {
        return EventTypes.get(entry.type()).ownerWords()
                ? "your owner spoke"
                : "an urgent " + entry.type() + " event arrived";
    }

    // ---- 在 serial 上 ----

    /** 交给执行体、还没做完的活有几件。 */
    private final java.util.concurrent.atomic.AtomicInteger outstanding = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 程序此刻是不是闲着:执行体上没有活在做、也没有排着的——它停在等一次调用的结果、等一件身体活收尾上,或者已经结束。没有谁在替它算。
     */
    public boolean idle() {
        return outstanding.get() == 0;
    }

    private void confined(Runnable work) {
        outstanding.incrementAndGet();
        serial.execute(() -> {
            try {
                work.run();
            } catch (RuntimeException broke) {
                AiLog.LOG.error("[numen-program] {} broke", id, broke);
                if (!done) {
                    done = true;
                    ended.accept(new Outcome(ToolOutcome.failure("the program could not be run: " + broke),
                            new ScriptCall.Ending(ScriptCall.Status.ERROR, ErrorKind.FAILED.wire()),
                            List.copyOf(calls), List.copyOf(used), null));
                }
            } finally {
                outstanding.decrementAndGet();
            }
        });
    }

    /** 往下走:没有在飞的、也不在等身体收尾,就做程序的下一步。当场回来的结果由这一圈接着走,不递归。 */
    private void advance() {
        if (advancing) {
            return;
        }
        advancing = true;
        try {
            while (!done && inFlight == null && awaiting == null) {
                ScriptCall.Next step = next;
                next = null;
                switch (step) {
                    case ScriptCall.Next.Dispatch d -> {
                        String callId = id + "#" + (++callSeq);
                        inFlight = callId;
                        port.dispatch(callId, d.invocation(), json -> confined(() -> lineResult(callId, json)));
                    }
                    case ScriptCall.Next.Await a -> awaiting = a.task();
                    case ScriptCall.Next.Done d -> end(d.end());
                }
            }
        } finally {
            advancing = false;
        }
    }

    private void lineResult(String callId, String resultJson) {
        if (done || !callId.equals(inFlight)) {
            return;   // 已经被放弃,或者重复、迟到的结果
        }
        inFlight = null;
        if (interruptedBy != null) {
            String why = interruptedBy;
            interruptedBy = null;
            ApiReply.Parsed reply = ApiReply.parse(resultJson);
            String body = reply.ok() && reply.job() != null ? "; " + reply.job() + " keeps running" : "";
            end(script.stop(why + body), why + body);
        } else {
            next = script.result(resultJson);
            collect();
        }
        advance();
    }

    /** 来了急件:等着身体收尾就不再等;一次调用在跑就等它回来再停。 */
    private void urgent(String why) {
        if (awaiting != null) {
            String waited = awaiting;
            awaiting = null;
            String stopped = why + "; " + waited + " keeps running";
            end(script.stop(stopped), stopped);
        } else if (inFlight != null && interruptedBy == null) {
            interruptedBy = why;
        }
    }

    private void end(ScriptCall.End end) {
        end(end, null);
    }

    private void end(ScriptCall.End end, String stoppedFor) {
        collect();
        done = true;
        ended.accept(new Outcome(end.receipt(), end.ending(), List.copyOf(calls), List.copyOf(used), stoppedFor,
                end.returned(), end.failure()));
    }

    /**
     * 一条事件到了:是等着的那件活的收尾就接着走,是急件就停在调用之间。
     *
     * @return 这条事件是不是等着的那件活的收尾,程序用上了
     */
    private boolean observe(EventQueue.Entry entry) {
        if (done) {
            return false;
        }
        ScriptCall.Finish finish = port.finish(entry);
        if (finish != null && finish.task().equals(awaiting)) {
            awaiting = null;
            next = script.finished(finish);
            collect();
            advance();
            return true;
        }
        if (EventQueue.isUrgent(entry.type(), entry.urgent())) {
            urgent(what(entry));
        }
        return false;
    }

    /** 有了结局的调用逐个收起来。 */
    private void collect() {
        calls.addAll(script.drainCalled());
    }

    /** 程序结束时 {@link ScriptCall} 把用到的每个模块报过来,记下:客户端据此记战绩。 */
    private final class Recording implements ScriptCall.Host {

        @Override
        public ScriptCatalog catalog() {
            return port.catalog();
        }

        @Override
        public Invocation invocation(ScriptRun.Call call) {
            return port.invocation(call);
        }

        @Override
        public void tally(String module, ScriptCall.Tally tally) {
            used.add(new Used(module, tally));
        }

        @Override
        public long now() {
            return port.now();
        }
    }
}
