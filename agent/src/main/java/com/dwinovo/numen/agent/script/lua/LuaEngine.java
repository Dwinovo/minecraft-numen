package com.dwinovo.numen.agent.script.lua;

import com.dwinovo.lua.LuaSandbox;
import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.script.FunctionDoc;
import com.dwinovo.numen.agent.script.JsonValues;
import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.script.ScriptLimits;
import com.dwinovo.numen.agent.script.ScriptRun;
import com.dwinovo.numen.agent.script.ScriptType;

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
 * 成功直接返回数据:回执的数据换成 Lua 的表(声明了返回项的,{@link ScriptCatalog.Verb#returns},是数据里的那一项),没有数据是 nil。
 * 回执那句话不交给脚本,只进整段程序的回执。失败在调用处抛一个错误值:一张表 {@code {kind, message, hint, fn, data}}
 * ({@link ScriptRun#failure}),带着错误元表,{@code tostring(err)} 是一段可读的文字({@link #render}),{@code pcall} 接住后按
 * {@code err.kind} 分支。全局函数 {@code raise(kind, message, hint)} 抛同一种错误值,库与脚本自己的失败也这样说。
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

    /** 抛同一种错误值的全局函数:{@code raise("failed", "why", "what to do next")}。 */
    static final String RAISE = "raise";

    /**
     * 一个错误值写成文字:{@code work.dig: out_of_reach — 那句话},下一行 {@code hint: …}。{@code tostring(err)} 与没接住时整段回执里的
     * 那句话都是它。
     */
    static String render(Map<String, Object> error) {
        Object fn = error.get(ScriptRun.FN);
        Object kind = error.get(ScriptRun.KIND);
        Object message = error.get(ScriptRun.MESSAGE);
        Object hint = error.get(ScriptRun.HINT);
        StringBuilder sb = new StringBuilder();
        if (fn != null) {
            sb.append(fn).append(": ");
        }
        sb.append(kind == null ? ErrorKind.RUNTIME.wire() : kind);
        if (message != null) {
            sb.append(" — ").append(message);
        }
        if (hint != null) {
            sb.append("\nhint: ").append(hint);
        }
        return sb.toString();
    }

    /** {@code raise(kind, message, hint, data)}:kind 与 message 是字符串,hint(字符串)与 data(一张表)可以不写。 */
    private static Object raise(List<Object> in) {
        Object kind = in.isEmpty() ? null : in.get(0);
        Object message = in.size() < 2 ? null : in.get(1);
        Object hint = in.size() < 3 ? null : in.get(2);
        Object data = in.size() < 4 ? null : in.get(3);
        if (!(kind instanceof String k) || !(message instanceof String m) || (hint != null && !(hint instanceof String))
                || (data != null && !(data instanceof Map<?, ?> || data instanceof List<?>))) {
            throw new LuaSandbox.ScriptError(ScriptRun.failure(ErrorKind.BAD_ARGUMENT.wire(),
                    "raise takes a kind and a message (strings), then an optional hint (a string) and data (a table)",
                    "raise(\"failed\", \"why it stopped\", \"what to do next\")", RAISE, null));
        }
        throw new LuaSandbox.ScriptError(ScriptRun.failure(k, m, (String) hint, null, data));
    }

    @Override
    public String howToCall() {
        return "Every API function is `group.verb(objects..., {option = value})`: `scan.blocks(\"iron_ore\", "
                + "{radius = 12, into = \"ores\"})`, `work.dig(\"ores/g3\")`. A position is a table with named "
                + "fields, `{x = 120, y = 64, z = -35}` (a Pos); anything a call returns that has a `pos` (a Block, an "
                + "Entity, an Item) goes where a position goes, as it is: `local e = scan.entities(\"hostile\")[1]; "
                + "fight.attack(e)`, and the same with move.goto_(e.pos). A switch is `{sneak = true}`; a name Lua "
                + "already uses gets "
                + "a trailing underscore (`move.goto_`). A call returns when it is done (work that occupies your body: "
                + "when it has finished) and returns data, never sentences: `area.has(\"ores\")` is true or false, "
                + "`status.self()` a table whose pos is a Pos, `work.dig(\"ores\")` a table with what it dug. A call "
                + "that fails raises an error value: `local ok, err = pcall(work.dig, \"ores\")` catches it, `err.kind` "
                + "says what kind "
                + "(bad_argument, not_found, out_of_reach, no_path, denied, ...), `err.hint` is a line to run next; "
                + "`raise(kind, message, hint)` raises your own. To read a value, `print(x)` (a table prints as a Lua "
                + "table) or `return x`; the receipt shows one line per call and what you printed or returned. `...` "
                + "holds the arguments of a script run by name. `api.help(\"work\")` lists a group's typed "
                + "signatures, `api.help(\"work.dig\")` explains one.";
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
        return literal(options);
    }

    @Override
    public String value(Object value) {
        return literal(value);
    }

    /** 一个值写成 Lua 的字面量;名字到值的表按迭代顺序写,键不是合法名字的写成 {@code ["键"] = 值}。 */
    private static String literal(Object value) {
        return switch (value) {
            case null -> "nil";
            case String s -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
            case Double d -> d == Math.rint(d) && !d.isInfinite() ? String.valueOf(d.longValue()) : String.valueOf(d);
            case Number n -> String.valueOf(n);
            case Boolean b -> String.valueOf(b);
            case List<?> list -> "{" + String.join(", ", list.stream().map(LuaEngine::literal).toList()) + "}";
            case Map<?, ?> map -> {
                List<String> named = new ArrayList<>();
                map.forEach((k, v) -> named.add(key(String.valueOf(k)) + " = " + literal(v)));
                yield "{" + String.join(", ", named) + "}";
            }
            default -> throw new IllegalArgumentException("cannot write " + value + " in Lua");
        };
    }

    /** 表的一个键:合法的名字原样,别的写成 {@code ["键"]}。 */
    private static String key(String k) {
        return k.matches("[A-Za-z_][A-Za-z0-9_]*") && !LuaSandbox.KEYWORDS.contains(k) ? k : "[" + literal(k) + "]";
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
                doc.add(line);
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
                out.add(new Defined(m.group(1), params, doc));
            }
            doc.clear();
        }
        return List.copyOf(out);
    }

    // ---- 签名:LuaLS 的类型注解 ----

    @Override
    public String typeText(ScriptType type) {
        return switch (type) {
            case ScriptType.Simple s -> s.name();
            case ScriptType.Named n -> n.name();
            case ScriptType.ListOf l -> (l.item() instanceof ScriptType.Union ? "(" + typeText(l.item()) + ")"
                    : typeText(l.item())) + "[]";
            case ScriptType.Union u -> String.join("|", u.options().stream().map(this::typeText).toList());
            case ScriptType.Choice c -> String.join("|", c.values().stream().map(LuaEngine::literal).toList());
            case ScriptType.Table t -> "{" + String.join(", ", t.fields().stream()
                    .map(f -> f.name() + (f.optional() ? "?" : "") + ": " + typeText(f.type())).toList()) + "}";
        };
    }

    @Override
    public String classText(ScriptType.Class type) {
        StringBuilder sb = new StringBuilder();
        if (type.doc() != null) {
            for (String line : type.doc().split("\n")) {
                sb.append("---").append(line).append('\n');
            }
        }
        sb.append("---@class ").append(type.name());
        if (type.parent() != null) {
            sb.append(": ").append(type.parent());
        }
        fields(sb, type.fields());
        return sb.toString();
    }

    /** 一个类的字段,每个一行 {@code ---@field 名字? 类型 说明}。 */
    private void fields(StringBuilder sb, List<ScriptType.Field> fields) {
        for (ScriptType.Field f : fields) {
            sb.append("\n---@field ").append(f.name()).append(f.optional() ? "? " : " ").append(typeText(f.type()));
            if (f.doc() != null) {
                sb.append(' ').append(f.doc());
            }
        }
    }

    /** 一个参数的类型:收一个或几个的写成 {@code T|T[]}。 */
    private String paramType(FunctionDoc.Param p) {
        return p.several() ? typeText(p.type()) + "|" + typeText(new ScriptType.ListOf(p.type())) : typeText(p.type());
    }

    @Override
    public String functionText(FunctionDoc fn) {
        StringBuilder sb = new StringBuilder("---").append(fn.summary());
        List<String> names = new ArrayList<>();
        List<String> classes = new ArrayList<>();
        for (FunctionDoc.Param p : fn.params()) {
            names.add(p.name());
            String type = paramType(p);
            if (p.type() instanceof ScriptType.Table t) {
                // 选项表的字段各带一句说明:写成一个类,参数引用它
                String cls = fn.name() + "." + p.name();
                classes.add(classText(new ScriptType.Class(cls, null, null, t.fields())));
                type = cls;
            }
            sb.append("\n---@param ").append(p.name()).append(p.optional() ? "? " : " ").append(type);
            if (p.doc() != null) {
                sb.append(' ').append(p.doc());
            }
        }
        String returns = typeText(fn.returns());
        if (fn.returns() instanceof ScriptType.Table t) {
            String cls = fn.name() + ".result";
            classes.add(classText(new ScriptType.Class(cls, null, null, t.fields())));
            returns = cls;
        }
        if (fn.returns() != ScriptType.NOTHING) {
            sb.append("\n---@return ").append(returns);
        }
        sb.append("\nfunction ").append(fn.name()).append('(').append(String.join(", ", names)).append(") end");
        for (String c : classes) {
            sb.append("\n\n").append(c);
        }
        block(sb, "Examples:", fn.examples());
        block(sb, "Notes:", fn.notes());
        if (!fn.seeAlso().isEmpty()) {
            sb.append("\n-- See also: ").append(String.join(", ", fn.seeAlso()));
        }
        return sb.toString();
    }

    /** 带标题的一块注释,一行一条;没有条目时整块不出现。 */
    private static void block(StringBuilder sb, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        sb.append("\n-- ").append(title);
        for (String line : lines) {
            sb.append("\n--   ").append(line.replace("\n", "\n--   "));
        }
    }

    /** 清单里一张就地写出的表最多几个字段;再多就按名字引用({@code move.go.opts}),全部字段在这个函数自己的帮助里。 */
    private static final int INLINE_FIELDS = 5;

    @Override
    public String functionLine(FunctionDoc fn) {
        List<String> params = new ArrayList<>();
        for (FunctionDoc.Param p : fn.params()) {
            String type = p.type() instanceof ScriptType.Table t && t.fields().size() > INLINE_FIELDS
                    ? fn.name() + "." + p.name() : paramType(p);
            params.add(p.name() + (p.optional() ? "?" : "") + ": " + type);
        }
        String returns = fn.returns() == ScriptType.NOTHING ? null
                : fn.returns() instanceof ScriptType.Table t && t.fields().size() > INLINE_FIELDS ? fn.name() + ".result"
                : typeText(fn.returns());
        return line(fn.name(), String.join(", ", params), returns, fn.summary());
    }

    /** 清单里的一行:{@code ---@field 名字 fun(参数): 返回 说明},写成这一组那张表的一个字段。 */
    private static String line(String name, String params, String returns, String summary) {
        String field = name.substring(name.indexOf('.') + 1);
        return "---@field " + field + " fun(" + params + ")" + (returns == null ? "" : ": " + returns)
                + (summary == null || summary.isEmpty() ? "" : " " + summary);
    }

    @Override
    public String groupText(String group, String summary, List<String> lines) {
        StringBuilder sb = new StringBuilder("---").append(summary).append("\n---@class ").append(group);
        lines.forEach(l -> sb.append('\n').append(l));
        return sb.append('\n').append(group).append(" = {}").toString();
    }

    @Override
    public String libraryText(Defined fn) {
        StringBuilder sb = new StringBuilder();
        fn.doc().forEach(l -> sb.append(l).append('\n'));
        return sb.append("function ").append(fn.name()).append('(').append(String.join(", ", fn.params()))
                .append(") end").toString();
    }

    /** 库里注释的一行类型注解:{@code ---@param 名字? 类型 说明}、{@code ---@return 类型 说明}。 */
    private static final Pattern PARAM_DOC = Pattern.compile("^---@param\\s+(\\S+)\\s+(.*)$");
    private static final Pattern RETURN_DOC = Pattern.compile("^---@return\\s+(.*)$");

    @Override
    public String libraryLine(Defined fn) {
        List<String> params = new ArrayList<>();
        String returns = null;
        for (String l : fn.doc()) {
            Matcher p = PARAM_DOC.matcher(l);
            Matcher r = RETURN_DOC.matcher(l);
            if (p.find()) {
                String name = p.group(1);
                boolean optional = name.endsWith("?");
                params.add((optional ? name.substring(0, name.length() - 1) + "?" : name) + ": "
                        + leadingType(p.group(2)));
            } else if (r.find()) {
                returns = leadingType(r.group(1));
            }
        }
        return line(fn.name(), String.join(", ", params), returns, summaryOf(fn));
    }

    /** 一行类型注解里打头的那个类型(括号配平地读到第一个括号外的空格为止)。 */
    private static String leadingType(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '(' || c == '<' || c == '[') {
                depth++;
            } else if (c == '}' || c == ')' || c == '>' || c == ']') {
                depth--;
            } else if (c == ' ' && depth == 0) {
                return text.substring(0, i);
            }
        }
        return text;
    }

    @Override
    public String summaryOf(Defined fn) {
        StringBuilder text = new StringBuilder();
        for (String l : fn.doc()) {
            String line = l.replaceFirst("^-+", "").strip();
            if (line.startsWith("@")) {
                break;
            }
            if (!line.isEmpty()) {
                text.append(text.isEmpty() ? "" : " ").append(line);
            }
        }
        int end = text.indexOf(". ");
        return end < 0 ? text.toString() : text.substring(0, end + 1);
    }

    /**
     * 脚本读了一组里没有的函数({@code area.hsa}):说没有这个 API 函数,名字差一两个字的给出最近的那个,再说怎么列这一组。
     * 停在读它的那一行,不让它成 nil 再在调用处报"调了一个 nil"。
     */
    static LuaSandbox.ScriptError missing(String table, String key, List<String> present) {
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
        return new LuaSandbox.ScriptError(ScriptRun.failure(ErrorKind.NO_FUNCTION.wire(),
                "there is no API function " + table + "." + key
                        + (close ? "; did you mean " + table + "." + nearest + "?" : ""),
                "api.help(\"" + table + "\") lists the group's functions.", null, null));
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
        LuaSandbox.Builder sandbox = LuaSandbox.builder(LIMITS).missing(LuaEngine::missing).errors(LuaEngine::render)
                .function(RAISE, LuaEngine::raise);
        catalog.groups().forEach((group, verbs) -> verbs.forEach((verb, declared) ->
                sandbox.function(functionName(group), functionName(verb), in -> {
                    seen.add(call(group, verb, in, declared));
                    return declared.sample();
                })));
        for (String library : catalog.libraries().values()) {
            for (Defined defined : functions(library)) {
                int dot = defined.name().indexOf('.');
                String table = dot < 0 ? "" : defined.name().substring(0, dot);
                String fn = defined.name().substring(dot + 1);
                LuaSandbox.HostFunction record = in -> {
                    seen.add(call(table, fn, in, null));
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
    private static ScriptRun.Call call(String group, String verb, List<Object> in, ScriptCatalog.Verb declared) {
        List<Object> objects = new ArrayList<>(in);
        Map<String, Object> options = Map.of();
        Object last = objects.isEmpty() ? null : objects.get(objects.size() - 1);
        boolean optionsTable = last instanceof Map<?, ?> table
                && (declared == null || declared.optionsTable(table, objects.size() - 1));
        if (optionsTable && last instanceof Map<?, ?> map) {
            objects.remove(objects.size() - 1);
            Map<String, Object> named = new LinkedHashMap<>();
            map.forEach((k, v) -> named.put(String.valueOf(k), v));
            options = named;
        } else if (last instanceof List<?> list && list.isEmpty()) {
            objects.remove(objects.size() - 1);
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

    /** 驱动方交回脚本线程的东西:一个值,或者让那次调用失败的错误值。 */
    private record Answer(Object value, Map<String, Object> raise) {}

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
            LuaSandbox.Builder sandbox = LuaSandbox.builder(LIMITS).print(printer).missing(LuaEngine::missing)
                    .errors(LuaEngine::render).show(LuaEngine::literal).function(RAISE, LuaEngine::raise);
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
            if (!result.ok()) {
                return answer(new Answer(null, ScriptRun.failure(result.kind(), result.text(), result.hint(),
                        call.function(), result.data().size() > 0 ? JsonValues.toJava(result.data()) : null)));
            }
            ScriptCatalog.Verb verb = catalog.verb(call.group(), call.verb());
            String key = verb == null ? null : verb.returns();
            if (key != null) {
                return answer(new Answer(JsonValues.toJava(result.data().get(key)), null));
            }
            return answer(new Answer(result.data().size() > 0 ? JsonValues.toJava(result.data()) : null, null));
        }

        @Override
        public Step refuse(ApiError why) {
            Call call = pending;
            pending = null;
            return answer(new Answer(null, ScriptRun.failure(why.kind().wire(), why.getMessage(), why.hint(),
                    call.function(), null)));
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
            if (outcome.finished()) {
                return new Done(true, 0, null, outcome.value(), null);
            }
            Map<String, Object> failure;
            if (outcome.error() != null && outcome.error().get(ScriptRun.KIND) instanceof String) {
                failure = outcome.error();
            } else {
                ErrorKind kind = switch (outcome.ending()) {
                    case UNREADABLE -> ErrorKind.SYNTAX;
                    case ERROR -> ErrorKind.RUNTIME;
                    case INTERRUPTED -> ErrorKind.INTERRUPTED;
                    default -> ErrorKind.LIMIT;
                };
                failure = ScriptRun.failure(kind.wire(), outcome.message(), null, null, null);
            }
            return new Done(false, outcome.line(), outcome.message(), null, failure);
        }

        /** 脚本线程上:一个 API 函数被调了。交给驱动方,等它交回结局。 */
        private Object ask(String group, String verb, List<Object> in) throws InterruptedException {
            events.add(new Asked(call(group, verb, in, catalog.verb(group, verb))));
            Answer answer = answers.take();
            if (answer.raise() != null) {
                throw new LuaSandbox.ScriptError(answer.raise());
            }
            return answer.value();
        }
    }
}
