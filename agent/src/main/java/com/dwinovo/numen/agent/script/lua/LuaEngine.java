package com.dwinovo.numen.agent.script.lua;

import com.dwinovo.lua.LuaSandbox;
import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.script.ScriptLimits;
import com.dwinovo.numen.agent.script.ScriptRun;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.function.Consumer;

/**
 * 脚本语言是 Lua 5.2,跑在 {@code numen-lua} 的沙箱里({@link LuaSandbox}):沙箱的类型只在这个类里出现。
 *
 * <h2>命令函数怎么接</h2>
 * 每个命令是一个宿主函数 {@code 组.动作}(名字撞上 Lua 的保留字或沙箱自带的全局时加后缀 {@code _},{@link #functionName})。脚本
 * 跑在它自己的虚拟线程上;调一个命令函数,那个线程把这条命令交给驱动方({@link ScriptRun#start}/{@link ScriptRun#resume} 的
 * 返回值),然后停在那儿等结局。驱动方(大脑的派发器)把命令派出去、等身体收尾,再把结局交回来,脚本从调用处接着跑。驱动方
 * 只在脚本两次调命令之间等它算完(指令预算管着,几十毫秒以内),等身体干活的时候它不等,谁都不阻塞。
 *
 * <h2>返回什么</h2>
 * 成功直接返回值、失败抛错:命令登记时声明了返回项({@link ScriptCatalog.Verb#returns})而回执里有这一项,就返回它(成败都返回,
 * {@code area.has} 有没有剩都是一个布尔);否则失败就在调用处抛 Lua 错误({@code pcall} 接得住),成功返回回执数据(没有数据就
 * 返回回执那句话)。
 */
public final class LuaEngine implements ScriptEngine {

    private static final LuaSandbox.Limits LIMITS = new LuaSandbox.Limits(ScriptLimits.INSTRUCTIONS_PER_SLICE,
            ScriptLimits.INSTRUCTIONS, ScriptLimits.STRING_BYTES, Duration.ofMillis(ScriptLimits.WALL_MILLIS));

    @Override
    public String language() {
        return "Lua 5.2";
    }

    @Override
    public String toolName() {
        return "lua";
    }

    @Override
    public String extension() {
        return ".lua";
    }

    @Override
    public String howToCall() {
        return "Every command `<group> <action> <objects...> [--option value]` is the function "
                + "`group.action(objects..., {option = value})`: `work.dig(\"ores/g3\")`, "
                + "`move.goto_(\"ores/g3\", {arrive = \"dig\"})`, `move.goto_(120, 64, -35)`. A name Lua already uses "
                + "(a keyword such as goto, or a standard global) gets a trailing underscore: move.goto_. A switch is "
                + "`{sneak = true}`. A command function returns when the command is done (for work that occupies "
                + "your body, when that task has finished) and gives its result directly: a query returns its value "
                + "(`area.has(\"ores\")` is true or false, `area.parts(\"ores\")` a list of names), other commands "
                + "the data of their reply, or the reply's sentence when it has none. A command that fails raises an "
                + "error with its message; catch it with `pcall(function() ... end)` when the script should go on. "
                + "`print(...)` writes into the receipt; `...` and `arg` hold the arguments of a script run by name; "
                + "`error(\"why\")` ends it as failed.";
    }

    @Override
    public String comment(String text) {
        return "-- " + text;
    }

    @Override
    public String functionName(String name) {
        return LuaSandbox.KEYWORDS.contains(name) || LuaSandbox.STANDARD_GLOBALS.contains(name) ? name + "_" : name;
    }

    @Override
    public String check(String name, String code) {
        return LuaSandbox.check(name, code);
    }

    /** 正文开头那段注释的第一行({@code -- Dig out an area.});空行与 {@code #!} 行不算开头。 */
    @Override
    public String summary(String code) {
        for (String raw : code.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#!")) {
                continue;
            }
            if (!line.startsWith("--") || line.startsWith("--[[")) {
                return null;
            }
            String text = line.substring(2).strip();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    @Override
    public ScriptRun start(String name, String code, List<String> args, ScriptCatalog catalog,
                           Consumer<String> printer) {
        return new Run(name, code, args, catalog, printer);
    }

    // ---- 一次运行 ----

    /** 脚本线程交给驱动方的东西:一条命令,或者结局。 */
    private sealed interface Event permits Asked, Ended {}

    private record Asked(ScriptRun.Call call) implements Event {}

    private record Ended(LuaSandbox.Outcome outcome) implements Event {}

    /** 驱动方交回脚本线程的东西:一个值,或者让那次调用失败的一句话。 */
    private record Answer(Object value, String raise) {}

    private final class Run implements ScriptRun {

        private final String name;
        private final String code;
        private final List<String> args;
        private final ScriptCatalog catalog;
        private final Consumer<String> printer;
        private final LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<>();
        private final SynchronousQueue<Answer> answers = new SynchronousQueue<>();
        private LuaSandbox.Running running;
        /** 交出去、还没交回结局的那一条。 */
        private Call pending;

        Run(String name, String code, List<String> args, ScriptCatalog catalog, Consumer<String> printer) {
            this.name = name;
            this.code = code;
            this.args = List.copyOf(args);
            this.catalog = catalog;
            this.printer = printer;
        }

        @Override
        public Step start() {
            LuaSandbox.Builder sandbox = LuaSandbox.builder(LIMITS).print(printer);
            catalog.groups().forEach((group, verbs) -> verbs.keySet().forEach(verb ->
                    sandbox.function(functionName(group), functionName(verb), in -> ask(group, verb, in))));
            running = sandbox.build().start(name, code, args, outcome -> events.add(new Ended(outcome)));
            return next();
        }

        @Override
        public Step resume(Result result) {
            Call call = pending;
            pending = null;
            ScriptCatalog.Verb verb = catalog.verb(call.group(), call.verb());
            String key = verb == null ? null : verb.returns();
            Answer answer;
            if (key != null && result.data().has(key)) {
                answer = new Answer(toJava(result.data().get(key)), null);
            } else if (!result.ok()) {
                answer = new Answer(null, call.function() + ": " + result.text());
            } else if (result.data().size() > 0) {
                answer = new Answer(toJava(result.data()), null);
            } else {
                answer = new Answer(result.text(), null);
            }
            return answer(answer);
        }

        @Override
        public Step refuse(String why) {
            Call call = pending;
            pending = null;
            return answer(new Answer(null, call.function() + ": " + why));
        }

        @Override
        public void close() {
            if (running != null) {
                running.interrupt();
            }
        }

        private Step answer(Answer answer) {
            try {
                answers.put(answer);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while handing a result to the script", e);
            }
            return next();
        }

        /** 等脚本线程走到下一条命令或结束:中间只有两次调命令之间的计算,指令预算管着它。 */
        private Step next() {
            Event event;
            try {
                event = events.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the script", e);
            }
            return switch (event) {
                case Asked asked -> {
                    pending = asked.call();
                    yield asked.call();
                }
                case Ended ended -> done(ended.outcome());
            };
        }

        private Done done(LuaSandbox.Outcome outcome) {
            return outcome.finished() ? new Done(true, 0, null) : new Done(false, outcome.line(), outcome.message());
        }

        /** 脚本线程上:一个命令函数被调了。交给驱动方,等它交回结局。 */
        private Object ask(String group, String verb, List<Object> in) throws InterruptedException {
            List<Object> objects = new ArrayList<>(in);
            Map<String, Object> options = Map.of();
            if (!objects.isEmpty() && objects.get(objects.size() - 1) instanceof Map<?, ?> map) {
                objects.remove(objects.size() - 1);
                Map<String, Object> named = new LinkedHashMap<>();
                map.forEach((k, v) -> named.put(String.valueOf(k), v));
                options = named;
            }
            for (Object o : objects) {
                if (o instanceof Map<?, ?>) {
                    throw new LuaSandbox.ScriptError(group + "." + verb + ": options {name = value} go last");
                }
            }
            events.add(new Asked(new Call(LuaSandbox.currentLine(), group, verb, List.copyOf(objects), options)));
            Answer answer = answers.take();
            if (answer.raise() != null) {
                throw new LuaSandbox.ScriptError(answer.raise());
            }
            return answer.value();
        }
    }

    /** 回执里的 JSON 换成沙箱收的 Java 值:对象成名字到值的表、数组成列表、null 成 nil。 */
    static Object toJava(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e instanceof JsonPrimitive p) {
            if (p.isBoolean()) {
                return p.getAsBoolean();
            }
            if (p.isNumber()) {
                double d = p.getAsDouble();
                return d == Math.rint(d) && Math.abs(d) < 9.0e15 ? (Object) (long) d : (Object) d;
            }
            return p.getAsString();
        }
        if (e instanceof JsonArray a) {
            List<Object> list = new ArrayList<>(a.size());
            a.forEach(item -> list.add(toJava(item)));
            return list;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        ((JsonObject) e).entrySet().forEach(entry -> map.put(entry.getKey(), toJava(entry.getValue())));
        return map;
    }
}
