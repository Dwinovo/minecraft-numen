package com.dwinovo.numen.agent.lua;

import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次调用里跑的一段脚本:她当场写的一段 Lua,或按名字跑的一份({@code script run})。它把脚本每调一个命令函数变成一行命令交给
 * 派发的一方({@link Next.Dispatch}),那一行的回执、要等的身体活的收尾再交回来,脚本从调用处接着跑;跑完、出错、到了上限或被
 * 打断时写成一张回执。派发、等待与打断的时机在 {@code SerialCalls},这里只管脚本走到哪、回执怎么写。
 *
 * <h2>脚本里再跑脚本</h2>
 * {@code script.run("mine", "ores")} 是一条普通命令:命令层按名字找到那份脚本,回执里带上它的正文({@link #toRun})。这里见到
 * 这样的回执就在原地开一层,跑完那一层,它的结局就是那次调用的结局。上限算整段调用的总数,打断时每一层都停。
 *
 * <h2>回执</h2>
 * 第一行一句话说结局(跑完、出错在哪一行、停在哪一行为什么);之后按脚本的行各一句——调了几次、成败、最后一次那句话的第一行,
 * 不重复命令全文;最后是 {@code print} 写的字。身体活的实际账照旧在它自己的 task_finished 里说一次,这里只点它的编号与结局。
 */
public final class ScriptCall {

    /** 派发的一方给脚本的几样东西。 */
    public interface Host {

        /** 脚本能调的函数。 */
        LuaCatalog catalog();

        /**
         * 一个命令函数的调用写成一行命令,和她在命令行上写的一样。
         *
         * @throws IllegalArgumentException 写不成(没有这个动作、选项名不对);消息是给脚本的那句话
         */
        String line(LuaRun.Call call);

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
        /** 执行这一行命令,把回执交回 {@link #result}。 */
        record Dispatch(String line) implements Next {}

        /** 那一行留下了一件还在跑的身体活:等它的收尾,交回 {@link #finished}。 */
        record Await(String task) implements Next {}

        /** 脚本结束了,这是这次调用的结果。 */
        record Done(String receipt) implements Next {}
    }

    /** 回执与 {@code script run} 回执里的键:命令层按它们写({@code ScriptCommands}),这里按它们读。 */
    public static final String RUN = "run";
    public static final String RUN_SCRIPT = "script";
    public static final String RUN_CODE = "code";
    public static final String RUN_ARGS = "args";

    /** 她当场写的那一段在报错里叫什么。 */
    private static final String INLINE = "lua";

    private static final int SAID = 160;

    private final Host host;
    private final long started;
    /** 正在跑的各层,最里面的在栈顶。 */
    private final Deque<Frame> frames = new ArrayDeque<>();
    /** 每一行的记录,按第一次调到它的先后。 */
    private final Map<String, LineLog> lines = new LinkedHashMap<>();
    private final StringBuilder printed = new StringBuilder();
    private boolean printedCut;
    /** 最外面那一层的名字:当场写的一段是 null。 */
    private final String topName;
    private int commands;
    /** 交出去、还没有结局的那一行。 */
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

    /** 按名字跑的一份({@code script run} 的回执带来的)。 */
    public static ScriptCall named(ToRun run, Host host) {
        return new ScriptCall(host, run.script(), run.code(), run.args());
    }

    /**
     * 一条回执是不是 {@code script run} 交来的"去跑这一份":是就返回它,否则 null。命令层只负责按名字找到脚本、交出正文,
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

    /** 开跑,走到第一条要执行的命令或结束。 */
    public Next begin() {
        return advance(frames.peek().run.start());
    }

    /**
     * 交出去的那一行有了回执。
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
        return advance(p.frame.run.resume(new LuaRun.Result(ok, text, dataOf(resultJson))));
    }

    /** 等的那件身体活收尾了:{@code done} 算成功。 */
    public Next finished(Finish finish) {
        Pending p = pending;
        pending = null;
        boolean ok = "done".equals(finish.status());
        log(p, ok, finish.task() + " " + finish.status() + (finish.words().isBlank() ? "" : ": " + finish.words()));
        JsonObject data = new JsonObject();
        data.addProperty("task", finish.task());
        data.addProperty("status", finish.status());
        return advance(p.frame.run.resume(new LuaRun.Result(ok, finish.words(), data)));
    }

    /**
     * 停在命令之间:主人说话、来了急件、这一轮被切断。每一层有名字的脚本记一次没跑完;回执写明停在哪一行、为什么。
     *
     * @param why 为什么停,一句话(含那件还在跑的活怎样了)
     */
    public String stop(String why) {
        for (Frame frame : frames) {
            if (frame.name != null) {
                host.tally(frame.name, new Tally(false, lineIn(frame), why));
            }
        }
        return stopped(why);
    }

    // ---- 往下走 ----

    private Next advance(LuaRun.Step step) {
        while (true) {
            Frame frame = frames.peek();
            if (step instanceof LuaRun.Done done) {
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
                step = caller.frame.run.resume(new LuaRun.Result(done.ok(), text, new JsonObject()));
                continue;
            }
            LuaRun.Call call = (LuaRun.Call) step;
            if (commands >= ScriptLimits.COMMANDS) {
                pending = new Pending(frame, call);
                return new Next.Done(stop("it reached the limit of " + ScriptLimits.COMMANDS + " commands per run"));
            }
            if (host.now() - started > ScriptLimits.WALL_MILLIS) {
                pending = new Pending(frame, call);
                return new Next.Done(stop("it ran past the limit of " + ScriptLimits.WALL_MILLIS / 60_000
                        + " minutes per run"));
            }
            String line;
            try {
                line = host.line(call);
            } catch (IllegalArgumentException wrong) {
                step = frame.run.refuse(wrong.getMessage());
                continue;
            }
            commands++;
            pending = new Pending(frame, call);
            return new Next.Dispatch(line);
        }
    }

    private LuaRun run(String name, String code, List<String> args) {
        return new LuaRun(name == null ? INLINE : name, code, args, host.catalog(), this::print);
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
        String key = where(p.frame, p.call.line()) + " " + p.call.function();
        lines.computeIfAbsent(key, k -> new LineLog()).add(ok, text);
    }

    private String finalReceipt(LuaRun.Done done) {
        String head;
        if (done.ok()) {
            head = name() + " ran to the end: " + commands + " command" + (commands == 1 ? "" : "s") + " in "
                    + seconds() + " s.";
        } else {
            head = name() + " stopped at line " + done.line() + " after " + commands + " command"
                    + (commands == 1 ? "" : "s") + ": " + done.error();
        }
        return receipt(done.ok(), done.ok() ? "ok" : "error", head);
    }

    /** 停下的回执。 */
    private String stopped(String why) {
        String at = pending == null ? "" : " at " + where(pending.frame, pending.call.line()) + " ("
                + pending.call.function() + ")";
        String head = name() + " stopped" + at + " after " + commands + " command" + (commands == 1 ? "" : "s")
                + ": " + why + ". Nothing after that ran.";
        return receipt(false, "stopped", head);
    }

    /** 这一层停在哪一行:手上那一行在它里面就是那一行,否则是它调起里面那一层的那一行。 */
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

    private String receipt(boolean ok, String status, String head) {
        StringBuilder msg = new StringBuilder(head);
        lines.forEach((key, log) -> msg.append('\n').append(key).append(": ").append(log.sentence()));
        if (!printed.isEmpty()) {
            msg.append("\nprinted:\n").append(printed.toString().stripTrailing());
        }
        JsonObject data = new JsonObject();
        if (topName != null) {
            data.addProperty("script", topName);
        }
        data.addProperty("status", status);
        data.addProperty("commands", commands);
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

    private static JsonObject dataOf(String resultJson) {
        try {
            JsonElement parsed = JsonParser.parseString(resultJson);
            if (parsed.isJsonObject() && parsed.getAsJsonObject().get("data") instanceof JsonObject data) {
                return data;
            }
        } catch (RuntimeException notJson) {
            // 不是 JSON 的结果(接进来的外部工具的原文)没有数据
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
        final LuaRun run;
        /** 这一层是哪一行的 {@code script.run} 调起的;最外面一层是 null。 */
        Pending calledFrom;

        Frame(String name, LuaRun run) {
            this.name = name;
            this.run = run;
        }
    }

    private static final class Pending {
        final Frame frame;
        final LuaRun.Call call;

        Pending(Frame frame, LuaRun.Call call) {
            this.frame = frame;
            this.call = call;
        }
    }

    /** 一行调了几次、败了几次、最后一次怎样。 */
    private static final class LineLog {
        int calls;
        int failed;
        boolean lastOk;
        String lastText;

        void add(boolean ok, String text) {
            calls++;
            if (!ok) {
                failed++;
            }
            lastOk = ok;
            lastText = text;
        }

        String sentence() {
            String said = firstLine(lastText);
            String last = (lastOk ? "ok" : "failed") + (said.isEmpty() ? "" : " — " + said);
            if (calls == 1) {
                return last;
            }
            return calls + " calls, " + (failed == 0 ? "none failed" : failed + " failed") + "; last " + last;
        }

        private static String firstLine(String text) {
            String line = text == null ? "" : text.strip().split("\n", 2)[0];
            return line.length() <= SAID ? line : line.substring(0, SAID) + "...";
        }
    }
}
