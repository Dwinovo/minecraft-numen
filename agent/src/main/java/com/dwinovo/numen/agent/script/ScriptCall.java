package com.dwinovo.numen.agent.script;

import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 一次调用里跑的一段脚本:她当场写的一段程序,或按名字跑的一份({@code script.run})。脚本每调一个 API 函数,这里把它换成一次
 * 动作调用({@link Invocation})交给派发的一方({@link Next.Dispatch}),那次调用的回执、要等的身体活的收尾再交回来,脚本从调用处
 * 接着跑;跑完、出错、到了上限或被打断时写成一张回执。派发、等待与打断的时机在 {@code SerialCalls},这里只管脚本走到哪、回执
 * 怎么写。
 *
 * <h2>脚本里再跑脚本</h2>
 * {@code script.run("mine", "ores")} 是一次普通的 API 调用:命令层按名字找到那份脚本,回执里带上它的正文({@link #toRun})。这里见到
 * 这样的回执就在原地开一层,跑完那一层,它的结局就是那次调用的结局。上限算整段调用的总数,打断时每一层都停。
 *
 * <h2>回执</h2>
 * 第一行一句话说结局(跑完、出错在哪一行与原话、停在哪一行为什么);之后每次 API 调用一行——在哪一行、哪个函数、成败、回执那句话
 * 的第一行;再是脚本 {@code return} 的值;最后是 {@code print} 写的字。身体活的实际账照旧在它自己的 task_finished 里说一次,这里只
 * 点它的编号与结局。
 */
public final class ScriptCall {

    /** 派发的一方给脚本的几样东西。 */
    public interface Host {

        /** 脚本能调的函数与库。 */
        ScriptCatalog catalog();

        /**
         * 脚本里的一次 API 调用读成一个动作和它的参数。
         *
         * @throws IllegalArgumentException 读不成(没有这个动作、对象多了、选项名不对、值读不成);消息是给脚本的那句话
         */
        Invocation invocation(ScriptRun.Call call);

        /** 一份有名字的脚本跑完了一次(跑完、出错或被停下),记进它的战绩。 */
        void tally(String script, Tally tally);

        /** 墙钟,毫秒。 */
        long now();
    }

    /**
     * 一次运行的结局,记战绩用。
     *
     * @param ok    跑到了最后
     * @param line  没跑完时停在哪一行;跑完是 0
     * @param error 没跑完的原因;跑完是 null
     */
    public record Tally(boolean ok, int line, String error) {}

    /** 一件身体活收尾了:编号、状态({@code done}、{@code failed}、{@code timeout}、{@code stopped})、它交代的话。 */
    public record Finish(String task, String status, String words) {}

    /** 要按名字跑的一份脚本:名字、正文、参数。 */
    public record ToRun(String script, String code, List<String> args) {}

    /** 接下来该做什么。 */
    public sealed interface Next {
        /** 执行这次 API 调用,把回执交回 {@link #result}。 */
        record Dispatch(Invocation invocation) implements Next {}

        /** 那次调用留下了一件还在跑的身体活:等它的收尾,交回 {@link #finished}。 */
        record Await(String task) implements Next {}

        /** 脚本结束了,这是这次调用的结果。 */
        record Done(String receipt) implements Next {}
    }

    /** 回执与 {@code script.run} 回执里的键:命令层按它们写({@code Scripts}),这里按它们读。 */
    public static final String RUN = "run";
    public static final String RUN_SCRIPT = "script";
    public static final String RUN_CODE = "code";
    public static final String RUN_ARGS = "args";
    /** 回执数据里脚本 {@code return} 的那个值;按名字跑的脚本,它就是 {@code script.run} 那次调用直接返回的。 */
    public static final String RETURNED = "returned";
    /**
     * 回执数据里留下参数的那几次调用(动作登记时声明了 {@code echoed}),按先后,每次 {@code {"function": …, "args": {…}}}:
     * 对话流读它画出这次运行写下的东西(她的计划清单)。
     */
    public static final String ECHOED = "echoed";

    private static final int SAID = 160;
    private static final Gson GSON = new Gson();

    private final Host host;
    private final long started;
    /** 正在跑的各层,最里面的在栈顶。 */
    private final Deque<Frame> frames = new ArrayDeque<>();
    /** 每次 API 调用一行,按先后。 */
    private final List<String> log = new ArrayList<>();
    /** 留下参数的那几次成功的调用,见 {@link #ECHOED}。 */
    private final JsonArray echoed = new JsonArray();
    private final StringBuilder printed = new StringBuilder();
    private boolean printedCut;
    /** 最外面那一层的名字:当场写的一段是 null。 */
    private final String topName;
    private int calls;
    /** 交出去、还没有结局的那一次。 */
    private Pending pending;

    private ScriptCall(Host host, String name, String code, List<String> args) {
        this.host = host;
        this.started = host.now();
        this.topName = name;
        frames.push(new Frame(name, run(name, code, args)));
    }

    /** 她当场写的一段。 */
    public static ScriptCall inline(String code, Host host) {
        return new ScriptCall(host, null, code, List.of());
    }

    /**
     * 一条回执是不是 {@code script.run} 交来的"去跑这一份":是就返回它,否则 null。命令层只负责按名字找到脚本、交出正文,
     * 跑它的是派发这次调用的大脑。
     */
    public static ToRun toRun(String resultJson) {
        JsonObject data = dataOf(resultJson);
        if (ToolOutcome.failed(resultJson) || !(data.get(RUN) instanceof JsonObject run)) {
            return null;
        }
        List<String> args = new ArrayList<>();
        if (run.get(RUN_ARGS) instanceof JsonArray array) {
            array.forEach(e -> args.add(e.getAsString()));
        }
        return new ToRun(run.get(RUN_SCRIPT).getAsString(), run.get(RUN_CODE).getAsString(), List.copyOf(args));
    }

    /** 开跑,走到第一次要执行的调用或结束。 */
    public Next begin() {
        return advance(frames.peek().run.start());
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
        ToRun nested = toRun(resultJson);
        if (nested != null) {
            frames.push(new Frame(nested.script(), run(nested.script(), nested.code(), nested.args())));
            frames.peek().calledFrom = p;
            return advance(frames.peek().run.start());
        }
        boolean ok = !ToolOutcome.failed(resultJson);
        String text = messageOf(resultJson);
        log(p, ok, text);
        if (ok && p.invocation != null) {
            ScriptCatalog.Verb verb = host.catalog().verb(p.invocation.group(), p.invocation.verb());
            if (verb != null && verb.echoed()) {
                JsonObject echo = new JsonObject();
                echo.addProperty("function", p.invocation.function());
                echo.add("args", p.invocation.args());
                echoed.add(echo);
            }
        }
        return advance(p.frame.run.resume(new ScriptRun.Result(ok, text, dataOf(resultJson))));
    }

    /** 等的那件身体活收尾了:{@code done} 算成功。 */
    public Next finished(Finish finish) {
        Pending p = pending;
        pending = null;
        boolean ok = "done".equals(finish.status());
        log(p, ok, finish.task() + " " + finish.status() + (finish.words().isBlank() ? "" : ": " + finish.words()));
        return advance(p.frame.run.resume(new ScriptRun.Result(ok, finish.words(), new JsonObject())));
    }

    /**
     * 停在调用之间:主人说话、来了急件、这一轮被切断。每一层有名字的脚本记一次没跑完;回执写明停在哪一行、为什么。
     *
     * @param why 为什么停,一句话(含那件还在跑的活怎样了)
     */
    public String stop(String why) {
        for (Frame frame : frames) {
            if (frame.name != null) {
                host.tally(frame.name, new Tally(false, lineIn(frame), why));
            }
            frame.run.close();
        }
        return stopped(why);
    }

    // ---- 往下走 ----

    private Next advance(ScriptRun.Step step) {
        while (true) {
            Frame frame = frames.peek();
            if (step instanceof ScriptRun.Done done) {
                if (frame.name != null) {
                    host.tally(frame.name, done.ok() ? new Tally(true, 0, null)
                            : new Tally(false, done.line(), done.error()));
                }
                frames.pop();
                if (frames.isEmpty()) {
                    return new Next.Done(finalReceipt(done));
                }
                Pending caller = frame.calledFrom;
                String text = done.ok() ? frame.name + " ran to the end" : done.error();
                log(caller, done.ok(), text);
                JsonObject data = new JsonObject();
                if (done.ok() && done.value() != null) {
                    data.add(RETURNED, GSON.toJsonTree(done.value()));
                }
                step = caller.frame.run.resume(new ScriptRun.Result(done.ok(), text, data));
                continue;
            }
            ScriptRun.Call call = (ScriptRun.Call) step;
            if (calls >= ScriptLimits.COMMANDS) {
                pending = new Pending(frame, call);
                return new Next.Done(stop("it reached the limit of " + ScriptLimits.COMMANDS + " calls per run"));
            }
            if (host.now() - started > ScriptLimits.WALL_MILLIS) {
                pending = new Pending(frame, call);
                return new Next.Done(stop("it ran past the limit of " + ScriptLimits.WALL_MILLIS / 60_000
                        + " minutes per run"));
            }
            Invocation invocation;
            try {
                invocation = host.invocation(call);
            } catch (IllegalArgumentException wrong) {
                log.add(where(frame, call.line()) + " " + call.function() + ": failed — " + firstLine(wrong.getMessage()));
                step = frame.run.refuse(wrong.getMessage());
                continue;
            }
            calls++;
            pending = new Pending(frame, call);
            pending.invocation = invocation;
            return new Next.Dispatch(invocation);
        }
    }

    private ScriptRun run(String name, String code, List<String> args) {
        ScriptEngine engine = ScriptEngine.IN_USE;
        return engine.start(name == null ? engine.toolName() : name, code, args, host.catalog(), this::print);
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

    private void log(Pending p, boolean ok, String text) {
        String said = firstLine(text);
        log.add(where(p.frame, p.call.line()) + " " + p.call.function() + ": " + (ok ? "ok" : "failed")
                + (said.isEmpty() ? "" : " — " + said));
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
        return receipt(done.ok(), done.ok() ? "ok" : "error", head, done.value());
    }

    /** 停下的回执。 */
    private String stopped(String why) {
        String at = pending == null ? "" : " at " + where(pending.frame, pending.call.line()) + " ("
                + pending.call.function() + ")";
        String head = name() + " stopped" + at + " after " + calls + " call" + (calls == 1 ? "" : "s")
                + ": " + why + ". Nothing after that ran.";
        return receipt(false, "stopped", head, null);
    }

    /** 这一层停在哪一行:手上那一次在它里面就是那一行,否则是它调起里面那一层的那一行。 */
    private int lineIn(Frame frame) {
        if (pending != null && pending.frame == frame) {
            return pending.call.line();
        }
        for (Frame inner : frames) {
            if (inner.calledFrom != null && inner.calledFrom.frame == frame) {
                return inner.calledFrom.call.line();
            }
        }
        return 0;
    }

    private String receipt(boolean ok, String status, String head, Object returned) {
        StringBuilder msg = new StringBuilder(head);
        log.forEach(line -> msg.append('\n').append(line));
        JsonElement value = returned == null ? null : GSON.toJsonTree(returned);
        if (value != null) {
            msg.append("\nreturned: ").append(value);
        }
        if (!printed.isEmpty()) {
            msg.append("\nprinted:\n").append(printed.toString().stripTrailing());
        }
        JsonObject data = new JsonObject();
        if (topName != null) {
            data.addProperty("script", topName);
        }
        data.addProperty("status", status);
        data.addProperty("calls", calls);
        if (value != null) {
            data.add(RETURNED, value);
        }
        if (!echoed.isEmpty()) {
            data.add(ECHOED, echoed);
        }
        JsonObject result = new JsonObject();
        result.addProperty("success", ok);
        result.addProperty("message", msg.toString());
        result.add("data", data);
        return result.toString();
    }

    private String name() {
        return topName == null ? "The script" : "Script " + topName;
    }

    private long seconds() {
        return Math.round((host.now() - started) / 1000.0);
    }

    /** 行的写法:最外面那一段是 {@code line 3},里面跑的一份带上名字 {@code mine line 3}。 */
    private String where(Frame frame, int line) {
        return (frame == frames.peekLast() ? "" : frame.name + " ") + "line " + line;
    }

    private static String firstLine(String text) {
        String line = text == null ? "" : text.strip().split("\n", 2)[0];
        return line.length() <= SAID ? line : line.substring(0, SAID) + "...";
    }

    private static JsonObject dataOf(String resultJson) {
        try {
            JsonElement parsed = JsonParser.parseString(resultJson);
            if (parsed.isJsonObject() && parsed.getAsJsonObject().get("data") instanceof JsonObject data) {
                return data;
            }
            // 直接回一份数据的查询(status.self 这类,不带 success):那一整份就是数据
            if (parsed.isJsonObject() && !parsed.getAsJsonObject().has("success")) {
                return parsed.getAsJsonObject();
            }
        } catch (RuntimeException notJson) {
            // 不是 JSON 的结果没有数据
        }
        return new JsonObject();
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

    private static final class Frame {
        final String name;
        final ScriptRun run;
        /** 这一层是哪一次 {@code script.run} 调起的;最外面一层是 null。 */
        Pending calledFrom;

        Frame(String name, ScriptRun run) {
            this.name = name;
            this.run = run;
        }
    }

    private static final class Pending {
        final Frame frame;
        final ScriptRun.Call call;
        /** 交出去的那个动作;停在调用之前(到了上限)的是 null。 */
        Invocation invocation;

        Pending(Frame frame, ScriptRun.Call call) {
            this.frame = frame;
            this.call = call;
        }
    }
}
