package com.dwinovo.numen.agent.script;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次调用里跑的一段程序:她这一轮写的那段 {@code code}。程序每调一个 API 函数,这里把它换成一次调用({@link Invocation})交给
 * 派发的一方({@link Next.Dispatch}),那次调用的结果({@link ApiReply})、要等的身体活的收尾再交回来,程序从调用处接着跑;跑完、出错、到了
 * 上限或被打断时写成一张回执。派发、等待与打断的时机在 {@code SerialCalls},这里只管程序走到哪、回执怎么写。模块里的函数是程序的一部分,
 * 它们的 API 调用照样一次一次地交出来;程序结束时,用到的每个模块记一次战绩。
 *
 * <h2>回执</h2>
 * 第一行一句话说结局(跑完、出错在哪一行与那个错误值的文字、停在哪一行为什么);之后每次 API 调用一行——在哪一行、哪个函数、
 * {@code ok} 与它返回的值写成的字面量(截断),或失败的种类与错误的第一行;再是脚本 {@code return} 的值;最后是 {@code print} 写的字。
 * 脚本拿到的是值,给她看的那一行由同一个值写成,没有第二份文字。程序等着收尾的身体活,那一行是它的编号、结局与整段实际账(路上挖了、
 * 放了什么)——这件活的收尾只在这里说,不另发事件。出错时数据里的 {@code error} 是那个错误值({@code kind}、{@code message}……)。
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
     * @param result 它的结果({@link ApiReply#ended} 写的那一份),随事件一起到;没带(重启前派的活补发的收尾)是 null
     */
    public record Finish(String task, String status, String words, JsonObject result) {}

    /** 接下来该做什么。 */
    public sealed interface Next {
        /** 执行这次 API 调用,把结果交回 {@link #result}。 */
        record Dispatch(Invocation invocation) implements Next {}

        /** 那次调用留下了一件还在跑的身体活:等它的收尾,交回 {@link #finished}。 */
        record Await(String task) implements Next {}

        /** 脚本结束了,这是这次调用的结果。 */
        record Done(String receipt) implements Next {}
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

    /** 回执数据里程序 {@code return} 的那个值。 */
    public static final String RETURNED = "returned";

    private static final int SAID = 160;
    private static final Gson GSON = new Gson();

    private final Host host;
    private final long started;
    private final ScriptRun run;
    /** 每次 API 调用一行,按先后。 */
    private final List<String> log = new ArrayList<>();
    private final StringBuilder printed = new StringBuilder();
    private boolean printedCut;
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
        if (reply.ok()) {
            // 等的这段时间里身体做了什么,整段写进这一行;没做什么的,写交回的值
            if (reply.account() != null && !reply.account().isBlank()) {
                logWhole(p, null, reply.account().strip());
            } else {
                log(p, null, reply.value() == null ? "" : ScriptEngine.IN_USE.value(reply.value()));
            }
            return advance(run.resume(ScriptRun.Result.ok(reply.value())));
        }
        log(p, (String) reply.error().get(ScriptRun.KIND), String.valueOf(reply.error().get(ScriptRun.MESSAGE)));
        return advance(run.resume(ScriptRun.Result.failed(reply.error())));
    }

    /**
     * 等的那件身体活收尾了:{@code done} 算成功,程序拿到它的值;失败的种类是结果说的那一种,被叫停是 {@link ErrorKind#INTERRUPTED}、
     * 到了期限是 {@link ErrorKind#TIMEOUT}。整段实际账写进这一行:这件活的收尾只在这张回执里说。
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
        logWhole(p, kind, finish.task() + " " + finish.status()
                + (finish.words().isBlank() ? "" : ": " + finish.words().strip()));
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
    public String stop(String why) {
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
                return new Next.Done(finalReceipt(done));
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
                log.add(where(call.line()) + " " + call.function() + ": " + wrong.kind().wire() + " — "
                        + firstLine(wrong.getMessage()));
                called.add(called(call, wrong.kind().wire()));
                step = run.refuse(wrong);
                continue;
            }
            calls++;
            pending = new Pending(call);
            return new Next.Dispatch(invocation);
        }
    }

    private void print(String line) {
        if (printedCut) {
            return;
        }
        if (printed.length() + line.length() + 1 > ScriptLimits.PRINTED_CHARS) {
            printedCut = true;
            printed.append("[print output cut at ").append(ScriptLimits.PRINTED_CHARS).append(" characters]\n");
            return;
        }
        printed.append(line).append('\n');
    }

    // ---- 记录与回执 ----

    /**
     * 一次调用的那一行:{@code kind} 是失败的种类,成功是 null(写 {@code ok});{@code text} 是返回值的字面量或错误的话,只留第一行、
     * 截断。
     */
    private void log(Pending p, String kind, String text) {
        settled(p, kind);
        String said = firstLine(text);
        log.add(where(p.call.line()) + " " + p.call.function() + ": " + (kind == null ? "ok" : kind)
                + (said.isEmpty() ? "" : " — " + said));
    }

    /**
     * 一件身体活收尾的那一行:整段交代原样写上,第二行起缩进两格,读得出还是这一行。交代长过 {@link ScriptLimits#ACCOUNT_CHARS}
     * 的,留下放得下的整行,其余写明"另外 N 行省略"。
     */
    private void logWhole(Pending p, String kind, String text) {
        settled(p, kind);
        log.add(where(p.call.line()) + " " + p.call.function() + ": " + (kind == null ? "ok" : kind) + " — "
                + bounded(text).replace("\n", "\n  "));
    }

    /** 按整行留下 {@link ScriptLimits#ACCOUNT_CHARS} 以内的部分(第一行总留),其余的行数写出来。 */
    private static String bounded(String text) {
        if (text.length() <= ScriptLimits.ACCOUNT_CHARS) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder kept = new StringBuilder(lines[0]);
        int shown = 1;
        while (shown < lines.length && kept.length() + 1 + lines[shown].length() <= ScriptLimits.ACCOUNT_CHARS) {
            kept.append('\n').append(lines[shown++]);
        }
        return kept + "\n[" + (lines.length - shown) + " more line(s) of this account left out]";
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

    private String finalReceipt(ScriptRun.Done done) {
        String head;
        if (done.ok()) {
            head = name() + " ran to the end: " + calls + " call" + (calls == 1 ? "" : "s") + " in " + seconds()
                    + " s.";
        } else {
            head = name() + " stopped at line " + done.line() + " after " + calls + " call" + (calls == 1 ? "" : "s")
                    + ": " + done.error();
        }
        return receipt(done.ok() ? "ok" : "error", head, done.value(), done.failure());
    }

    /** 停下的回执。 */
    private String stopped(String why) {
        String at = pending == null ? "" : " at " + where(pending.call.line()) + " ("
                + pending.call.function() + ")";
        String head = name() + " stopped" + at + " after " + calls + " call" + (calls == 1 ? "" : "s")
                + ": " + why + ". Nothing after that ran.";
        return receipt("stopped", head, null, null);
    }

    /** @param failure 出错时的错误值,进数据的 {@code error};别的是 null */
    private String receipt(String status, String head, Object returned, java.util.Map<String, Object> failure) {
        boolean ok = "ok".equals(status);
        StringBuilder msg = new StringBuilder(head);
        int spent = 0;
        int cut = 0;
        for (String line : log) {
            if (spent + line.length() + 1 > ScriptLimits.RECEIPT_LINES_CHARS) {
                cut++;
                continue;
            }
            spent += line.length() + 1;
            msg.append('\n').append(line);
        }
        if (cut > 0) {
            msg.append("\n[").append(cut).append(" more call line(s) left out: the receipt keeps the first ")
                    .append(ScriptLimits.RECEIPT_LINES_CHARS).append(" characters of them]");
        }
        JsonElement value = returned == null ? null : GSON.toJsonTree(returned);
        if (value != null) {
            // 一段文字原样写,别的值写成给模型读的样子(缩略的判据和 print 同一处);太长的截掉并说明。数据里的 returned 是
            // 给程序读的原值,另有一个大得多的上限
            String shown = returned instanceof String text ? text : ScriptEngine.IN_USE.display(returned);
            int whole = shown.length();
            if (value.toString().length() > ScriptLimits.RETURNED_DATA_CHARS) {
                value = new com.google.gson.JsonPrimitive(shown.substring(0, Math.min(whole,
                        ScriptLimits.RETURNED_DATA_CHARS)) + "\n[returned value cut at "
                        + ScriptLimits.RETURNED_DATA_CHARS + " characters; it was " + whole + "]");
            }
            if (whole > ScriptLimits.RETURNED_CHARS) {
                shown = shown.substring(0, ScriptLimits.RETURNED_CHARS) + "\n[returned value cut at "
                        + ScriptLimits.RETURNED_CHARS + " characters; it was " + whole + "]";
            }
            msg.append("\nreturned: ").append(shown);
        }
        if (!printed.isEmpty()) {
            msg.append("\nprinted:\n").append(printed.toString().stripTrailing());
        }
        JsonObject data = new JsonObject();
        data.addProperty("status", status);
        data.addProperty("calls", calls);
        if (value != null) {
            data.add(RETURNED, value);
        }
        if (failure != null) {
            data.add("error", GSON.toJsonTree(failure));
        }
        JsonObject result = new JsonObject();
        result.addProperty("success", ok);
        result.addProperty("message", msg.toString());
        result.add("data", data);
        return result.toString();
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
        String line = text == null ? "" : text.strip().split("\n", 2)[0];
        return line.length() <= SAID ? line : line.substring(0, SAID) + "...";
    }

    // ---- 小件 ----

    private static final class Pending {
        final ScriptRun.Call call;

        Pending(ScriptRun.Call call) {
            this.call = call;
        }
    }
}
