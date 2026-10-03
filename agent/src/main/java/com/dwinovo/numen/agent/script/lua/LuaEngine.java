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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 脚本语言是 Lua 5.2,跑在 {@code numen-lua} 的沙箱里({@link LuaSandbox}):沙箱的类型只在这个类里出现。
 *
 * <h2>API 函数怎么接</h2>
 * 每个动作是一个宿主函数 {@code 组.动作}(名字撞上 Lua 的保留字或沙箱自带的全局时加后缀 {@code _},{@link #functionName})。内置库
 * 每次运行开跑之前先跑,它们定义的函数(多半就在组的表里,{@code move.goto_})照常调宿主函数。脚本跑在它自己的虚拟线程上;调一个
 * API 函数,那个线程把这次调用交给驱动方({@link ScriptRun#start}/{@link ScriptRun#resume} 的返回值),然后停在那儿等结局。驱动方
 * (大脑的派发器)把它派出去、等身体收尾,再把结局交回来,脚本从调用处接着跑。驱动方只在脚本两次调用之间等它算完(指令预算管着,
 * 几十毫秒以内),等身体干活的时候它不等,谁都不阻塞。
 *
 * <h2>返回什么</h2>
 * 成功直接返回值、失败抛错:动作登记时声明了返回项({@link ScriptCatalog.Verb#returns})而回执里有这一项,就返回它(成败都返回,
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
        return "Every API function is `group.verb(objects..., {option = value})`: `scan.blocks(\"iron_ore\", "
                + "{radius = 12, into = \"ores\"})`, `work.dig(\"ores/g3\")`, `route.plan(\"home\")`. A cell is three "
                + "numbers, `{120, 64, -35}` or `\"120 64 -35\"`; a switch is `{sneak = true}`; a name Lua already uses "
                + "gets a trailing underscore (`move.goto_`). A call returns when it is done (for work that occupies "
                + "your body, when that task has finished) and gives its result directly: a query returns its value "
                + "(`area.has(\"ores\")` is true or false, `area.parts(\"ores\")` a list of names), other calls the "
                + "data of their reply, or the reply's sentence when it has none. A call that fails raises an error "
                + "with its message (for wrong arguments: error, usage, hint); `pcall(f, ...)` catches it when the "
                + "script should go on. `print(...)` writes into the receipt, `return value` hands a value back in it; "
                + "`...` holds the arguments of a script run by name; `error(\"why\", 0)` ends it as failed.";
    }

    @Override
    public String call(String function, List<Object> objects, Map<String, Object> options) {
        List<String> parts = new ArrayList<>();
        objects.forEach(o -> parts.add(literal(o)));
        if (!options.isEmpty()) {
            parts.add(table(options));
        }
        return function + "(" + String.join(", ", parts) + ")";
    }

    @Override
    public String table(Map<String, Object> options) {
        List<String> named = new ArrayList<>();
        options.forEach((k, v) -> named.add(k + " = " + literal(v)));
        return "{" + String.join(", ", named) + "}";
    }

    /** 一个值写成 Lua 的字面量。 */
    private static String literal(Object value) {
        return switch (value) {
            case String s -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
            case Double d -> d == Math.rint(d) && !d.isInfinite() ? String.valueOf(d.longValue()) : String.valueOf(d);
            case Number n -> String.valueOf(n);
            case Boolean b -> String.valueOf(b);
            case List<?> list -> "{" + String.join(", ", list.stream().map(LuaEngine::literal).toList()) + "}";
            default -> throw new IllegalArgumentException("cannot write " + value + " in Lua");
        };
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
    public boolean isKey(String name) {
        return !LuaSandbox.KEYWORDS.contains(name);
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

    /** 库里顶层的函数定义:{@code function work.collect(radius)}、{@code function sweep(a, b)}。 */
    private static final Pattern DEFINITION = Pattern.compile(
            "^function\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)?)\\s*\\(([^)]*)\\)");

    @Override
    public List<Defined> functions(String code) {
        List<Defined> out = new ArrayList<>();
        List<String> doc = new ArrayList<>();
        for (String raw : code.split("\n", -1)) {
            String line = raw.strip();
            if (line.startsWith("--") && !line.startsWith("--[[")) {
                doc.add(line.substring(2).strip());
                continue;
            }
            Matcher m = DEFINITION.matcher(raw);
            if (m.find()) {
                List<String> params = new ArrayList<>();
                for (String p : m.group(2).split(",")) {
                    if (!p.isBlank()) {
                        params.add(p.strip());
                    }
                }
                out.add(new Defined(m.group(1), List.copyOf(params), String.join(" ", doc).strip()));
            }
            doc.clear();
        }
        return List.copyOf(out);
    }

    /**
     * 脚本读了一组里没有的函数({@code area.hsa}):说没有这个 API 函数,名字差一两个字的给出最近的那个,再说怎么列这一组。
     * 停在读它的那一行,不让它成 nil 再在调用处报"调了一个 nil"。
     */
    static String missing(String table, String key, List<String> present) {
        String nearest = null;
        int best = Integer.MAX_VALUE;
        for (String name : present) {
            int d = distance(key, name);
            if (d < best) {
                best = d;
                nearest = name;
            }
        }
        boolean close = nearest != null && best <= Math.max(1, Math.min(2, key.length() / 3));
        return "there is no API function " + table + "." + key
                + (close ? "; did you mean " + table + "." + nearest + "?" : "")
                + " api.help(\"" + table + "\") lists the group's functions.";
    }

    /** 两个名字的编辑距离(增、删、改各算一步)。 */
    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int swap = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + swap);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    @Override
    public Reading calls(String name, String code, ScriptCatalog catalog) {
        String unreadable = check(name, code);
        if (unreadable != null) {
            throw new IllegalArgumentException(unreadable);
        }
        List<ScriptRun.Call> seen = new ArrayList<>();
        LuaSandbox.Builder sandbox = LuaSandbox.builder(LIMITS).missing(LuaEngine::missing);
        catalog.groups().forEach((group, verbs) -> verbs.keySet().forEach(verb ->
                sandbox.function(functionName(group), functionName(verb), in -> {
                    seen.add(call(group, verb, in));
                    return null;
                })));
        for (String library : catalog.libraries().values()) {
            for (Defined defined : functions(library)) {
                int dot = defined.name().indexOf('.');
                String table = dot < 0 ? "" : defined.name().substring(0, dot);
                String fn = defined.name().substring(dot + 1);
                LuaSandbox.HostFunction record = in -> {
                    seen.add(call(table, fn, in));
                    return null;
                };
                if (table.isEmpty()) {
                    sandbox.function(fn, record);
                } else {
                    sandbox.function(table, fn, record);
                }
            }
        }
        LuaSandbox.Outcome outcome;
        try {
            outcome = sandbox.build().start(name, code, List.of(), o -> { }).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while reading " + name, e);
        }
        return new Reading(List.copyOf(seen), outcome.finished() ? null : outcome.message());
    }

    /**
     * 一次调用的参数:按顺序的对象,最后一个是名字到值的表就是选项。选项表只能在最后。最后一个是空表 {@code {}} 时它是没写选项的
     * 选项表:空表分不出是列表还是名字表,而写在最后的那张表就是选项的位置。
     */
    private static ScriptRun.Call call(String group, String verb, List<Object> in) {
        List<Object> objects = new ArrayList<>(in);
        Map<String, Object> options = Map.of();
        Object last = objects.isEmpty() ? null : objects.get(objects.size() - 1);
        if (last instanceof Map<?, ?> map) {
            objects.remove(objects.size() - 1);
            Map<String, Object> named = new LinkedHashMap<>();
            map.forEach((k, v) -> named.put(String.valueOf(k), v));
            options = named;
        } else if (last instanceof List<?> list && list.isEmpty()) {
            objects.remove(objects.size() - 1);
        }
        for (Object o : objects) {
            if (o instanceof Map<?, ?>) {
                throw new LuaSandbox.ScriptError((group.isEmpty() ? "" : group + ".") + verb
                        + ": options {name = value} go last");
            }
        }
        // nil 照样交过去(位置要对得上),由读参数的那一处说是哪一个值是 nil
        return new ScriptRun.Call(LuaSandbox.currentLine(), group, verb, java.util.Collections.unmodifiableList(objects),
                options);
    }

    @Override
    public ScriptRun start(String name, String code, List<String> args, ScriptCatalog catalog,
                           Consumer<String> printer) {
        return new Run(name, code, args, catalog, printer);
    }

    // ---- 一次运行 ----

    /** 脚本线程交给驱动方的东西:一次 API 调用,或者结局。 */
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
            LuaSandbox.Builder sandbox = LuaSandbox.builder(LIMITS).print(printer).missing(LuaEngine::missing);
            catalog.groups().forEach((group, verbs) -> verbs.keySet().forEach(verb ->
                    sandbox.function(functionName(group), functionName(verb), in -> ask(group, verb, in))));
            catalog.libraries().forEach(sandbox::library);
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

        /** 等脚本线程走到下一次 API 调用或结束:中间只有两次调用之间的计算,指令预算管着它。 */
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
            return outcome.finished() ? new Done(true, 0, null, outcome.value())
                    : new Done(false, outcome.line(), outcome.message(), null);
        }

        /** 脚本线程上:一个 API 函数被调了。交给驱动方,等它交回结局。 */
        private Object ask(String group, String verb, List<Object> in) throws InterruptedException {
            events.add(new Asked(call(group, verb, in)));
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
