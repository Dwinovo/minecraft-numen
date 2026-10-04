package com.dwinovo.numen.agent.script;

import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次调用里跑的一段程序:她这一轮写的那段 {@code code}。程序每调一个 API 函数,这里把它换成一次动作调用({@link Invocation})交给
 * 派发的一方({@link Next.Dispatch}),那次调用的回执、要等的身体活的收尾再交回来,程序从调用处接着跑;跑完、出错、到了上限或被打断时
 * 写成一张回执。派发、等待与打断的时机在 {@code SerialCalls},这里只管程序走到哪、回执怎么写。模块里的函数是程序的一部分,它们的
 * API 调用照样一次一次地交出来;程序结束时,用到的每个模块记一次战绩。
 *
 * <h2>回执</h2>
 * 第一行一句话说结局(跑完、出错在哪一行与那个错误值的文字、停在哪一行为什么);之后每次 API 调用一行——在哪一行、哪个函数、
 * {@code ok} 或失败的种类、回执那句话的第一行;再是脚本 {@code return} 的值;最后是 {@code print} 写的字。脚本拿到的是数据,给她看的
 * 文字只在这里,由同一张回执写成。程序等着收尾的身体活,那一行是它的编号、结局与整段交代(路上挖了、放了什么的实际账)——这件活的
 * 收尾只在这里说,不另发事件。出错时数据里的 {@code error} 是那个错误值({@code kind}、{@code message}……)。
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
     * @param result 它的结果({@code success}、{@code message}、失败时的 {@code kind} 与 {@code hint}、{@code data}),随事件一起到;
     *               没带(重启前派的活补发的收尾)是 null
     */
    public record Finish(String task, String status, String words, JsonObject result) {}

    /** 接下来该做什么。 */
    public sealed interface Next {
        /** 执行这次 API 调用,把回执交回 {@link #result}。 */
        record Dispatch(Invocation invocation) implements Next {}

        /** 那次调用留下了一件还在跑的身体活:等它的收尾,交回 {@link #finished}。 */
        record Await(String task) implements Next {}

        /** 脚本结束了,这是这次调用的结果。 */
        record Done(String receipt) implements Next {}
    }

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
     * 交出去的那次调用有了回执。
     *
     * @param runningTask 回执说它留下了一件会自己收尾的身体活:那件的编号;没有是 null
     */
    public Next result(String resultJson, String runningTask) {
        if (runningTask != null) {
            return new Next.Await(runningTask);
        }
        Pending p = pending;
        pending = null;
        boolean ok = !ToolOutcome.failed(resultJson);
        String text = messageOf(resultJson);
        JsonObject parsed = objectOf(resultJson);
        String kind = ok ? null : failureKind(parsed);
        log(p, ok ? null : kind, text);
        return advance(run.resume(new ScriptRun.Result(ok, text, dataOf(resultJson), kind,
                ok ? null : hintOf(parsed))));
    }

    /**
     * 等的那件身体活收尾了:{@code done} 算成功。脚本拿到的是它结果里的数据;失败的种类是结果说的那一种,被叫停是
     * {@link ErrorKind#INTERRUPTED}、到了期限是 {@link ErrorKind#TIMEOUT}。
     */
    public Next finished(Finish finish) {
        Pending p = pending;
        pending = null;
        boolean ok = "done".equals(finish.status());
        JsonObject result = finish.result() == null ? new JsonObject() : finish.result();
        String kind = ok ? null : switch (finish.status()) {
            case "timeout" -> ErrorKind.TIMEOUT.wire();
            case "stopped", "interrupted" -> ErrorKind.INTERRUPTED.wire();
            default -> failureKind(result);
        };
        // 整段交代都写进这一行:这件活的收尾只在这张回执里说
        logWhole(p, kind, finish.task() + " " + finish.status()
                + (finish.words().isBlank() ? "" : ": " + finish.words().strip()));
        JsonObject data = result.get("data") instanceof JsonObject d ? d : new JsonObject();
        return advance(run.resume(new ScriptRun.Result(ok, finish.words(), data, kind,
                ok ? null : hintOf(result))));
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

    /** 一次调用的那一行:{@code kind} 是失败的种类,成功是 null(写 {@code ok})。 */
    private void log(Pending p, String kind, String text) {
        String said = firstLine(text);
        log.add(where(p.call.line()) + " " + p.call.function() + ": " + (kind == null ? "ok" : kind)
                + (said.isEmpty() ? "" : " — " + said));
    }

    /** 一件身体活收尾的那一行:整段交代原样写上,第二行起缩进两格,读得出还是这一行。 */
    private void logWhole(Pending p, String kind, String text) {
        log.add(where(p.call.line()) + " " + p.call.function() + ": " + (kind == null ? "ok" : kind) + " — "
                + text.replace("\n", "\n  "));
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
        log.forEach(line -> msg.append('\n').append(line));
        JsonElement value = returned == null ? null : GSON.toJsonTree(returned);
        if (value != null) {
            // 一段文字原样写,别的值写成脚本里的样子,和 print 一样
            msg.append("\nreturned: ").append(returned instanceof String text ? text
                    : ScriptEngine.IN_USE.value(returned));
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

    /** 结果整个读成 JSON 对象;不是对象是空对象。 */
    private static JsonObject objectOf(String resultJson) {
        try {
            JsonElement parsed = JsonParser.parseString(resultJson);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException notJson) {
            return new JsonObject();
        }
    }

    /** 失败的结果说的种类;没说(接进来的外部工具的结果这类)是 {@link ErrorKind#FAILED}。 */
    private static String failureKind(JsonObject result) {
        return result.get("kind") instanceof JsonElement k && k.isJsonPrimitive() ? k.getAsString()
                : ErrorKind.FAILED.wire();
    }

    /** 失败的结果给的下一步;没有是 null。 */
    private static String hintOf(JsonObject result) {
        return result.get("hint") instanceof JsonElement h && h.isJsonPrimitive() ? h.getAsString() : null;
    }

    /** 回执里交给脚本的数据({@code data});没有是空对象。 */
    private static JsonObject dataOf(String resultJson) {
        return objectOf(resultJson).get("data") instanceof JsonObject data ? data : new JsonObject();
    }

    /** 回执那句话:{@code TaskResult} 的 message;不是这个形状的结果整段就是那句话。 */
    private static String messageOf(String resultJson) {
        try {
            JsonElement parsed = JsonParser.parseString(resultJson);
            if (parsed.isJsonObject() && parsed.getAsJsonObject().get("message") instanceof JsonElement m
                    && m.isJsonPrimitive()) {
                return m.getAsString();
            }
        } catch (RuntimeException notJson) {
            // 原文就是那句话
        }
        return resultJson;
    }

    // ---- 小件 ----

    private static final class Pending {
        final ScriptRun.Call call;

        Pending(ScriptRun.Call call) {
            this.call = call;
        }
    }
}
