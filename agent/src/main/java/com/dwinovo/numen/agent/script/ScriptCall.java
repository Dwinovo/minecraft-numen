package com.dwinovo.numen.agent.script;

import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次调用里跑的一段程序:她这一轮写的那段 {@code code}。程序每调一个 API 函数,这里把它换成一次调用({@link Invocation})交给
 * 派发的一方({@link Next.Dispatch}),那次调用的结果({@link ApiReply})、要等的身体活的收尾再交回来,程序从调用处接着跑;跑完、出错、到了
 * 上限或被打断时写成一张回执。派发、等待与打断的时机在 {@code SerialCalls},这里只管程序走到哪、回执怎么写。模块里的函数是程序的一部分,
 * 它们的 API 调用照样一次一次地交出来;程序结束时,用到的每个模块记一次战绩。
 *
 * <h2>回执:标准管道加结局</h2>
 * 第一行是结局,像进程的退出状态:跑完是 {@code ok · N calls · T s};出错写停在哪一行与那个错误值的文字(种类、消息、hint),被停下写停在
 * 哪一行为什么。之后是三条管道:{@code stderr}(API 与运行时主动报告的事,{@link Stderr})、脚本 {@code return} 的值
 * {@code returned},和 {@code stdout}(她 {@code print} 的字)。API 调用本身不逐次进回执:返回值是给程序用的,只读的查询没什么可报告的就
 * 什么也不写,每次调用的完整记录({@link Called})留在结构化数据里,给评测和日志用。程序等着收尾的身体活,它整段实际账(路上挖了、放了什么)
 * 写成 stderr 的一条——这件活的收尾只在这里说,不另发事件。结构化的结局({@link End})另放:怎么结束的、{@code return} 的值原样、出错时完整的错误值,不在回执里。
 */
public final class ScriptCall {

    /** 派发的一方给脚本的几样东西。 */
    public interface Host {

        /** 脚本能调的函数与库。 */
        ScriptCatalog catalog();

        /**
         * 脚本里的一次 API 调用读成一个动作和它的参数。
         *
         * @throws ApiError 读不成(没有这个动作、对象多了、选项名不对、值读不成):抛给脚本的就是它
         */
        Invocation invocation(ScriptRun.Call call);

        /** 一段用到了这个模块的程序结束了(跑完、出错或被停下),记进这个模块的战绩。 */
        void tally(String module, Tally tally);

        /** 墙钟,毫秒。 */
        long now();
    }

    /**
     * 用到某个模块的一段程序的结局,记战绩用。
     *
     * @param ok    程序跑到了最后
     * @param line  没跑完时停在程序的哪一行;跑完是 0
     * @param error 没跑完的原因;跑完是 null
     */
    public record Tally(boolean ok, int line, String error) {}

    /**
     * 一件身体活收尾了。
     *
     * @param task   编号
     * @param status {@code done}、{@code failed}、{@code timeout}、{@code stopped}、{@code interrupted}
     * @param words  它交代的话
     * @param result 它的结果({@link ApiReply#value}/{@link ApiReply#error} 写的那一份),随事件一起到;没带(重启前派的活补发的收尾)是 null
     */
    public record Finish(String task, String status, String words, JsonObject result) {}

    /** 接下来该做什么。 */
    public sealed interface Next {
        /** 执行这次 API 调用,把结果交回 {@link #result}。 */
        record Dispatch(Invocation invocation) implements Next {}

        /** 那次调用留下了一件还在跑的身体活:等它的收尾,交回 {@link #finished}。 */
        record Await(String task) implements Next {}

        /** 脚本结束了,这是它的结局。 */
        record Done(End end) implements Next {}
    }

    /**
     * 一次 API 调用的结局,按调用记(评测按函数统计用):调了哪个函数、怎么写的、成了还是哪一种失败。参数读不成、没有这个函数,当场失败的
     * 也算一次。
     *
     * @param call  这次调用写成的文字({@link ScriptEngine#call});长过 {@link ScriptLimits#CALL_TEXT_CHARS} 的,头部留下、尾部换成整段
     *              文字的摘要,同样的调用得到同样的文字
     * @param first 第一个对象是一段文字时的它(查帮助查的是谁);不是是 null
     * @param kind  失败的种类({@link ErrorKind#wire});成了是 null
     */
    public record Called(String function, String call, String first, String kind) {}

    /** 一段程序怎么结束的:跑到了最后、出错停在某一行、被停下(叫停、上限)。 */
    public enum Status {
        OK, ERROR, STOPPED;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * 结构化的结局里能上网线的部分,给客户端和评测读(不进模型的文字,那是回执):怎么结束的,出错时错误值的种类
     * ({@link ErrorKind#wire});没出错是 null。
     */
    public record Ending(Status status, String errorKind) {}

    /**
     * 一段程序结束了。{@code receipt} 是交给模型的全部(成败与文字);{@code returned} 与 {@code failure} 是程序 {@code return} 的值和出错时
     * 完整的错误值({@code kind}、{@code message}、{@code hint}、{@code fn}、{@code data}),只存在于程序跑的那个进程里,给同进程的测试和工具读,
     * 不上网线。
     */
    public record End(String receipt, Ending ending, Object returned, java.util.Map<String, Object> failure) {}

    private final Host host;
    private final long started;
    private final ScriptRun run;
    /** API 与运行时主动报告的事,按先后。 */
    private final Stderr stderr = new Stderr();
    private final StringBuilder printed = new StringBuilder();
    /** 超过 {@link ScriptLimits#PRINTED_CHARS} 之后没能写进 stdout 的字数。 */
    private int printedCut;
    private int calls;
    /** 交出去、还没有结局的那一次。 */
    private Pending pending;
    /** 有了结局、还没被取走的调用,按先后。 */
    private final List<Called> called = new ArrayList<>();

    private ScriptCall(Host host, String code) {
        this.host = host;
        this.started = host.now();
        ScriptEngine engine = ScriptEngine.IN_USE;
        this.run = engine.start(engine.toolName(), code, host.catalog(), this::print);
    }

    /** 她这一轮写的一段。 */
    public static ScriptCall inline(String code, Host host) {
        return new ScriptCall(host, code);
    }

    /** 开跑,走到第一次要执行的调用或结束。 */
    public Next begin() {
        return advance(run.start());
    }

    /**
     * 交出去的那次调用有了结果({@link ApiReply} 写的那一份)。受理了一件占身体的活({@code job}),程序接着等它的收尾;否则结果交回程序。
     */
    public Next result(String replyJson) {
        ApiReply.Parsed reply = ApiReply.parse(replyJson);
        if (reply.ok() && reply.job() != null) {
            return new Next.Await(reply.job());
        }
        Pending p = pending;
        pending = null;
        settled(p, reply.ok() ? null : (String) reply.error().get(ScriptRun.KIND));
        if (reply.ok()) {
            // API 对这次调用的报告(身体在等的这段时间里做了什么);没有就什么也不写,值只给程序
            stderr.write(p.call.line(), p.call.function(), reply.stderr());
            return advance(run.resume(ScriptRun.Result.ok(reply.value())));
        }
        failed(p.call, (String) reply.error().get(ScriptRun.KIND), String.valueOf(reply.error().get(ScriptRun.MESSAGE)));
        return advance(run.resume(ScriptRun.Result.failed(reply.error())));
    }

    /**
     * 等的那件身体活收尾了:{@code done} 算成功,程序拿到它的值;失败的种类是结果说的那一种,被叫停是 {@link ErrorKind#INTERRUPTED}、
     * 到了期限是 {@link ErrorKind#TIMEOUT}。整段实际账写成 stderr 的一条:这件活的收尾只在这张回执里说。
     */
    public Next finished(Finish finish) {
        Pending p = pending;
        pending = null;
        ApiReply.Parsed reply = finish.result() == null ? null : ApiReply.parse(finish.result());
        boolean ok = "done".equals(finish.status());
        String kind = ok ? null : switch (finish.status()) {
            case "timeout" -> ErrorKind.TIMEOUT.wire();
            case "stopped", "interrupted" -> ErrorKind.INTERRUPTED.wire();
            default -> reply != null && !reply.ok() ? (String) reply.error().get(ScriptRun.KIND)
                    : ErrorKind.FAILED.wire();
        };
        settled(p, kind);
        String words = finish.words().strip();
        stderr.write(p.call.line(), p.call.function(), ok ? words : kind + (words.isEmpty() ? "" : " — " + words));
        if (ok) {
            return advance(run.resume(ScriptRun.Result.ok(reply == null ? null : reply.value())));
        }
        java.util.Map<String, Object> error = reply != null && !reply.ok()
                ? new java.util.LinkedHashMap<>(reply.error())
                : ScriptRun.failure(kind, finish.words(), null, null, null);
        error.put(ScriptRun.KIND, kind);
        return advance(run.resume(ScriptRun.Result.failed(error)));
    }

    /**
     * 停在调用之间:主人说话、来了急件、这一轮被切断。用到的每个模块记一次没跑完;回执写明停在哪一行、为什么。
     *
     * @param why 为什么停,一句话(含那件还在跑的活怎样了)
     */
    public End stop(String why) {
        tally(new Tally(false, pending == null ? 0 : pending.call.line(), why));
        run.close();
        return stopped(why);
    }

    /** 这段程序用到的每个模块记一次。 */
    private void tally(Tally tally) {
        for (String module : run.modules()) {
            host.tally(module, tally);
        }
    }

    // ---- 往下走 ----

    private Next advance(ScriptRun.Step step) {
        while (true) {
            if (step instanceof ScriptRun.Done done) {
                tally(done.ok() ? new Tally(true, 0, null) : new Tally(false, done.line(), done.error()));
                return new Next.Done(finalEnd(done));
            }
            ScriptRun.Call call = (ScriptRun.Call) step;
            if (calls >= ScriptLimits.COMMANDS) {
                pending = new Pending(call);
                return new Next.Done(stop("it reached the limit of " + ScriptLimits.COMMANDS + " calls per run"));
            }
            if (host.now() - started > ScriptLimits.WALL_MILLIS) {
                pending = new Pending(call);
                return new Next.Done(stop("it ran past the limit of " + ScriptLimits.WALL_MILLIS / 60_000
                        + " minutes per run"));
            }
            Invocation invocation;
            try {
                invocation = host.invocation(call);
            } catch (ApiError wrong) {
                called.add(called(call, wrong.kind().wire()));
                failed(call, wrong.kind().wire(), wrong.getMessage());
                step = run.refuse(wrong);
                continue;
            }
            calls++;
            pending = new Pending(call);
            return new Next.Dispatch(invocation);
        }
    }

    /** stdout:她 {@code print} 的字。满了之后放不下的部分不写、只数着,回执里写明少了多少。 */
    private void print(String line) {
        String text = line + '\n';
        int room = ScriptLimits.PRINTED_CHARS - printed.length();
        if (text.length() <= room) {
            printed.append(text);
            return;
        }
        printed.append(text, 0, room);   // 放得下的头部留下
        printedCut += text.length() - room;
    }

    // ---- 记录与回执 ----

    /** 一次调用失败:不管程序接没接住,都往 stderr 写它的种类与错误的第一行(全文在错误值里,接住了的程序自己读)。 */
    private void failed(ScriptRun.Call call, String kind, String message) {
        stderr.write(call.line(), call.function(), kind + " — " + firstLine(message));
    }

    /** 交出去的那一次有了结局。 */
    private void settled(Pending p, String kind) {
        called.add(called(p.call, kind));
    }

    /** 一次调用的结局记录:调用写成的文字太长就留头部加摘要。 */
    private static Called called(ScriptRun.Call call, String kind) {
        String text = ScriptEngine.IN_USE.call(call.function(), call.args(), call.options());
        if (text.length() > ScriptLimits.CALL_TEXT_CHARS) {
            text = text.substring(0, ScriptLimits.CALL_TEXT_CHARS) + "…#" + digest(text);
        }
        String first = call.args().isEmpty() || !(call.args().get(0) instanceof String name) ? null
                : name.length() <= ScriptLimits.CALL_TEXT_CHARS ? name : name.substring(0, ScriptLimits.CALL_TEXT_CHARS);
        return new Called(call.function(), text, first, kind);
    }

    private static String digest(String text) {
        try {
            byte[] sum = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(sum, 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 上次取走之后有了结局的调用,按先后;取走就清空。 */
    public List<Called> drainCalled() {
        List<Called> out = List.copyOf(called);
        called.clear();
        return out;
    }

    private End finalEnd(ScriptRun.Done done) {
        String head;
        if (done.ok()) {
            head = "ok · " + calls + " call" + (calls == 1 ? "" : "s") + " · " + seconds() + " s";
        } else {
            head = name() + " stopped at line " + done.line() + " after " + calls + " call" + (calls == 1 ? "" : "s")
                    + ": " + done.error();
        }
        return end(done.ok() ? Status.OK : Status.ERROR, head, done.value(), done.failure());
    }

    /** 停下的结局。 */
    private End stopped(String why) {
        String at = pending == null ? "" : " at " + where(pending.call.line()) + " ("
                + pending.call.function() + ")";
        String head = name() + " stopped" + at + " after " + calls + " call" + (calls == 1 ? "" : "s")
                + ": " + why + ". Nothing after that ran.";
        return end(Status.STOPPED, head, null, null);
    }

    /**
     * 结局:回执是结局一行,再是 stderr、返回值、stdout 三栏,哪栏没有东西就不出现;成败与文字就是交给模型的全部。结构化的部分(怎么结束的、
     * 返回值原样、完整的错误值)另放在 {@link End} 里,不拼进文字。
     *
     * @param failure 出错时的错误值;别的是 null
     */
    private End end(Status status, String head, Object returned, java.util.Map<String, Object> failure) {
        StringBuilder msg = new StringBuilder(head);
        if (!stderr.isEmpty()) {
            msg.append("\nstderr:\n").append(stderr.text());
        }
        if (returned != null) {
            // 一段文字原样写,别的值写成给模型读的样子(缩略的判据和 print 同一处);太长的截掉并说明
            String shown = returned instanceof String text ? text : ScriptEngine.IN_USE.display(returned);
            int whole = shown.length();
            if (whole > ScriptLimits.RETURNED_CHARS) {
                shown = shown.substring(0, ScriptLimits.RETURNED_CHARS) + "\n[returned value cut at "
                        + ScriptLimits.RETURNED_CHARS + " characters; it was " + whole + "]";
            }
            msg.append("\nreturned: ").append(shown);
        }
        if (!printed.isEmpty()) {
            msg.append("\nstdout:\n").append(printed.toString().stripTrailing());
            if (printedCut > 0) {
                msg.append("\n[stdout cut at ").append(ScriptLimits.PRINTED_CHARS).append(" characters; ")
                        .append(printedCut).append(" more were not shown — print less, or filter in the program "
                                + "before you print]");
            }
        }
        String kind = failure == null ? null : String.valueOf(failure.getOrDefault(ScriptRun.KIND, ErrorKind.RUNTIME.wire()));
        String receipt = status == Status.OK ? ToolOutcome.success(msg.toString()) : ToolOutcome.failure(msg.toString());
        return new End(receipt, new Ending(status, kind), returned, failure);
    }
    private String name() {
        return "The script";
    }

    private long seconds() {
        return Math.round((host.now() - started) / 1000.0);
    }

    /** 行的写法:{@code line 3}(程序里的那一行;经模块函数调到的,是程序里调那个函数的那一行)。 */
    private static String where(int line) {
        return "line " + line;
    }

    private static String firstLine(String text) {
        return text == null ? "" : text.strip().split("\n", 2)[0];
    }

    // ---- 小件 ----

    private static final class Pending {
        final ScriptRun.Call call;

        Pending(ScriptRun.Call call) {
            this.call = call;
        }
    }
}
