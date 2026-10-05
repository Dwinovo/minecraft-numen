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
 * 每个动作是一个宿主函数 {@code 名字空间.组.动作}({@code numen.work.dig};名字撞上 Lua 的保留字或沙箱自带的全局时加后缀
 * {@code _},{@link #functionName})。模块按名字直接用,第一次用到才装(和组同名的给那一组加函数,{@code numen.move.to}),它们的
 * 函数照常调宿主函数;没有 {@code require}。
 * 脚本跑在它自己的虚拟线程上;调一个
 * API 函数,那个线程把这次调用交给驱动方({@link ScriptRun#start}/{@link ScriptRun#resume} 的返回值),然后停在那儿等结局。驱动方
 * (大脑的派发器)把它派出去、等身体收尾,再把结局交回来,脚本从调用处接着跑。驱动方只在脚本两次调用之间等它算完(指令预算管着,
 * 几十毫秒以内),等身体干活的时候它不等,谁都不阻塞。
 *
 * <h2>返回什么</h2>
 * 成功直接返回函数的值(不返回值的是 nil),带方法的类的值挂上它的元表({@link ScriptCatalog#mark})。失败在调用处抛一个错误值:
 * 一张表 {@code {kind, message, hint, fn, data}}
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
    /** 没有 require:模块按名字直接用。写了它就在那一行说怎么用。 */
    static final String REQUIRE = "require";

    private static Object require(List<Object> in) {
        throw new LuaSandbox.ScriptError(ScriptRun.failure(ErrorKind.NO_FUNCTION.wire(), "there is no require in "
                + "these programs", "modules are used by name: `numen.work.collect()`, `my.lumber.chop(...)`", REQUIRE,
                null));
    }

    /** 读了一个既不是全局、也不是模块的名字:多半是名字空间写错了,说有哪些模块。 */
    static LuaSandbox.ScriptError unknown(String name, List<String> modules) {
        return new LuaSandbox.ScriptError(ScriptRun.failure(ErrorKind.NO_FUNCTION.wire(), "there is no global, "
                + "namespace or module named " + name + "; the API is under numen (and each mod's id), the modules "
                + "are: " + String.join(", ", modules), "numen.module.list() lists the modules with what each does",
                null, null));
    }

    /** 模块来源接到沙箱上。 */
    private static LuaSandbox.ModuleSource source(ScriptCatalog.ModuleSource modules) {
        return new LuaSandbox.ModuleSource() {
            @Override
            public String code(String name) {
                return modules.code(name);
            }

            @Override
            public List<String> names() {
                return modules.names();
            }
        };
    }

    /**
     * 一个错误值写成文字:{@code numen.work.dig: out_of_reach — 那句话},下一行 {@code hint: …}。{@code tostring(err)} 与没接住时整段回执里的
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
        return "Every API function is written in full, `namespace.group.verb(objects..., {option = value})`: Numen's "
                + "own are under numen (`numen.scan.blocks(\"iron_ore\", {radius = 12})`, `numen.work.dig({x = 120, y = 12, z = -35})`), a "
                + "mod's under its id. A position is a table with named "
                + "fields, `{x = 120, y = 64, z = -35}` (a Pos); anything a call returns that has a `pos` (a Block, an "
                + "Entity, an Item) goes where a position goes, as it is: `local e = numen.scan.entities(\"hostile\")[1]; "
                + "numen.fight.attack(e)`, and the same with numen.move.to(e.pos). A switch is `{sneak = true}`; a "
                + "name Lua already uses gets a trailing underscore (`until_`). A call returns when it is done "
                + "(work that occupies your body: when it has finished) and returns data, never sentences: "
                + "`numen.status.self()` is a table whose pos is a Pos, numen.work.dig(b) a table with what it dug. A "
                + "call that fails raises an error value: `local ok, err = pcall(numen.work.dig, {x = 120, y = 12, "
                + "z = -35})` catches it, "
                + "`err.kind` says what kind "
                + "(bad_argument, not_found, out_of_reach, no_path, denied, ...), `err.hint` is a line to run next; "
                + "`raise(kind, message, hint)` raises your own. To read a value, `print(x)` (a table prints as a Lua "
                + "table) or `return x`; the receipt shows what you printed (stdout) or returned, a long table shortened with its size; what your body did and any call that failed are in its stderr. A module "
                + "is used by its name like a group, with no require: `numen.work.collect()`, `my.lumber.chop(t)`. "
                + "`numen.api.help(\"numen.work\")` lists a group's or a module's typed signatures, "
                + "`numen.api.help(\"numen.work.dig\")` explains one.";
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

    @Override
    public String display(Object value) {
        return LuaDisplay.of(value);
    }

    /** 一段文字写成 Lua 的字符串字面量。 */
    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    /** 一个值写成 Lua 的字面量;名字到值的表按迭代顺序写,键不是合法名字的写成 {@code ["键"] = 值}。 */
    private static String literal(Object value) {
        return switch (value) {
            case null -> "nil";
            case String s -> quote(s);
            case Double d -> LuaSandbox.number(d);
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
    static String key(String k) {
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

    /** 正文开头那段注释的第一行({@code -- Dig out the given blocks.});空行与 {@code #!} 行不算开头。 */
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

    /** 模块里给一个类定义的方法:{@code function M.Pos:offset(dx, dy, dz)}——类名大写开头,冒号后是方法名。 */
    private static final Pattern METHOD = Pattern.compile(
            "^function\\s+[A-Za-z_][A-Za-z0-9_]*\\.([A-Z][A-Za-z0-9_]*):([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)");

    /** 模块里顶层的函数定义:{@code function M.collect(radius)}——它返回的那张表里的一个函数。 */
    private static final Pattern DEFINITION = Pattern.compile(
            "^function\\s+[A-Za-z_][A-Za-z0-9_]*\\.([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)");

    /** 模块名每一段的写法:小写字母打头,小写字母、数字、下划线,最长 48。 */
    private static final Pattern MODULE_NAME = Pattern.compile("[a-z][a-z0-9_]{0,47}");

    /** 引擎自己占的全局名:名字空间不能占。 */
    private static final java.util.Set<String> ENGINE_GLOBALS = java.util.Set.of(RAISE, REQUIRE);

    @Override
    public String moduleName(String name) {
        String[] parts = name == null ? new String[0] : name.split("\\.", -1);
        if (parts.length != 2 || !MODULE_NAME.matcher(parts[0]).matches() || !MODULE_NAME.matcher(parts[1]).matches()) {
            return "a module name is two parts, namespace.group, each lowercase letters, digits and _, starting with a "
                    + "letter (a program writes it as it is: my.lumber.chop()); got \"" + name + "\"";
        }
        for (String part : parts) {
            if (LuaSandbox.KEYWORDS.contains(part)) {
                return part + " is a Lua keyword; pick another name";
            }
        }
        if (LuaSandbox.STANDARD_GLOBALS.contains(parts[0]) || ENGINE_GLOBALS.contains(parts[0])) {
            return parts[0] + " is a name Lua or the API already uses; pick another namespace";
        }
        return null;
    }

    @Override
    public List<Defined> functions(String module, String code) {
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
                out.add(new Defined(module + "." + m.group(1), params, doc));
            }
            doc.clear();
        }
        return List.copyOf(out);
    }

    @Override
    public List<Defined> methods(String type, String code) {
        List<Defined> out = new ArrayList<>();
        List<String> doc = new ArrayList<>();
        for (String raw : code.split("\n", -1)) {
            String line = raw.strip();
            if (line.startsWith("--") && !line.startsWith("--[[")) {
                doc.add(line);
                continue;
            }
            Matcher m = METHOD.matcher(raw);
            if (m.find() && m.group(1).equals(type)) {
                List<String> params = new ArrayList<>();
                for (String p : m.group(3).split(",")) {
                    if (!p.isBlank()) {
                        params.add(p.strip());
                    }
                }
                out.add(new Defined(m.group(2), params, doc));
            }
            doc = new ArrayList<>();
        }
        return List.copyOf(out);
    }

    /**
     * 一个返回值里带方法的值:换成 Lua 值时带上它的类的元表——模块里与类同名的那张表,按类名的最后一段认({@code numen.Cluster} 的方法写在
     * {@code M.Cluster} 上)。
     */
    private static Object instance(ScriptType.Class type, Object value) {
        return new LuaSandbox.Instance(type.name().substring(type.name().lastIndexOf('.') + 1), type.home(), value);
    }

    // ---- 签名:LuaLS 的类型注解 ----

    @Override
    public String typeText(ScriptType type) {
        return switch (type) {
            case ScriptType.Simple s -> s.name();
            case ScriptType.Named n -> n.name();
            case ScriptType.ListOf l -> (l.item() instanceof ScriptType.Union ? "(" + typeText(l.item()) + ")"
                    : typeText(l.item())) + "[]";
            case ScriptType.MapOf m -> "table<string, " + typeText(m.value()) + ">";
            case ScriptType.Union u -> String.join("|", u.options().stream().map(this::typeText).toList());
            case ScriptType.Choice c -> String.join("|", c.values().stream().map(LuaEngine::literal).toList());
            case ScriptType.Table t -> "{" + String.join(", ", t.fields().stream()
                    .map(f -> f.name() + (f.optional() ? "?" : "") + ": " + typeText(f.type())).toList()) + "}";
        };
    }

    @Override
    public String classText(ScriptType.Class type, List<Defined> methods) {
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
        if (type.items() != null) {
            sb.append("\n---@field [integer] ").append(typeText(type.items()));
        }
        fields(sb, type.fields());
        for (Defined method : methods) {
            sb.append('\n').append(methodLine(type.name(), method));
        }
        return sb.toString();
    }

    /** 类声明里一个方法的那一行:{@code ---@field offset fun(self: Pos, dx: number): Pos 说明},按它的类型注解写。 */
    private String methodLine(String type, Defined method) {
        String line = libraryLine(method);
        int open = line.indexOf("fun(") + "fun(".length();
        boolean none = line.charAt(open) == ')';
        return line.substring(0, open) + "self: " + type + (none ? "" : ", ") + line.substring(open);
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

    /** 清单里一张就地写出的表最多几个字段;再多就按名字引用({@code numen.move.go.opts}),全部字段在这个函数自己的帮助里。 */
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
        String field = name.substring(name.lastIndexOf('.') + 1);
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

    /**
     * 库里一个函数注释里声明的返回类型({@code ---@return Pos}):一个声明了的类、语言自带的一种,或它们的列表({@code Block[]});
     * 没写、或写的是就地的表与几种之一,是 null。
     */
    private static ScriptType returnType(Defined fn, ScriptCatalog catalog) {
        for (String l : fn.doc()) {
            Matcher r = RETURN_DOC.matcher(l);
            if (r.find()) {
                return declared(leadingType(r.group(1)), catalog);
            }
        }
        return null;
    }

    private static ScriptType declared(String text, ScriptCatalog catalog) {
        String type = text.endsWith("?") ? text.substring(0, text.length() - 1) : text;
        if (type.endsWith("[]")) {
            ScriptType item = declared(type.substring(0, type.length() - 2), catalog);
            return item == null ? null : ScriptType.listOf(item);
        }
        if (catalog.classes().containsKey(type)) {
            return new ScriptType.Named(type);
        }
        return switch (type) {
            case "integer" -> ScriptType.INTEGER;
            case "number" -> ScriptType.NUMBER;
            case "string" -> ScriptType.STRING;
            case "boolean" -> ScriptType.BOOLEAN;
            default -> null;
        };
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
     * 脚本读了一组里没有的函数({@code numen.work.dgi}),或一个名字空间里没有的组({@code numen.wrok}):说没有这个,名字差一两个字的
     * 给出最近的那个,再说怎么列。停在读它的那一行,不让它成 nil 再在调用处报"调了一个 nil"。
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
        boolean namespace = !table.contains(".");
        return new LuaSandbox.ScriptError(ScriptRun.failure(ErrorKind.NO_FUNCTION.wire(),
                "there is no " + (namespace ? "group or module " : "API function ") + table + "." + key
                        + (close ? "; did you mean " + table + "." + nearest + "?" : "")
                        + (namespace ? "; " + table + " has: " + String.join(", ", present) : ""),
                namespace ? "the <api> index lists every group." : "numen.api.help(\"" + table
                        + "\") lists the group's functions.", null, null));
    }

    /**
     * 程序或模块给第 ① 层的名字赋值({@code function numen.move.go() end}、{@code move = {}}、{@code raise = f}):登记的 API 函数与它们的表
     * 谁都换不掉、遮不住,停在那一行。往组里加别的名字照常({@code function numen.move.to(…)})。
     */
    static LuaSandbox.ScriptError redefined(String table, String key) {
        String name = table == null ? key : table + "." + key;
        boolean function = table != null && table.contains(".");
        return new LuaSandbox.ScriptError(ScriptRun.failure(ErrorKind.RUNTIME.wire(), name + " is "
                + (function ? "an API function" : "built into the API") + "; a program or module cannot redefine "
                + "or replace it", "give yours another name" + (function ? " (" + table + "." + key
                + "_mine, or a function of your own module)" : ""), null, null));
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
        LuaSandbox.Builder sandbox = reader(catalog, seen, LuaEngine::redefined);
        // 只读不跑:模块里的函数也只记下调用,返回它注释里声明的那种值的样子(和第 ① 层的函数一样),接着往下写的读得通
        for (String module : catalog.modules().names()) {
            for (Defined defined : functions(module, catalog.modules().code(module))) {
                String fn = defined.name().substring(module.length() + 1);
                ScriptType declared = returnType(defined, catalog);
                Object sample = declared == null ? null : catalog.mark(
                        ScriptType.sample(declared, catalog.classes()::get), declared, LuaEngine::instance);
                sandbox.function(module, fn, in -> {
                    seen.add(call(module, fn, in, null));
                    return sample;
                });
            }
        }
        LuaSandbox.Outcome outcome = read(sandbox, name, code);
        return new Reading(List.copyOf(seen), outcome.finished() ? null : outcome.message());
    }

    @Override
    public String checkModule(String name, String code, ScriptCatalog catalog) {
        String unreadable = check(name, code);
        if (unreadable != null) {
            return unreadable;
        }
        // 和运行时同一套装法装它一次:返回的是不是一张表、给第 ① 层的名字赋没赋值,都由沙箱那一处说
        LuaSandbox.Builder sandbox = reader(catalog, new ArrayList<>(), LuaEngine::redefined)
                .modules(source(new ScriptCatalog.ModuleSource() {
                    @Override
                    public String code(String module) {
                        return module.equals(name) ? code : null;
                    }

                    @Override
                    public List<String> names() {
                        return List.of(name);
                    }
                }))
                .preload(name);
        LuaSandbox.Outcome outcome = read(sandbox, "check", "");
        return outcome.finished() ? null : outcome.message();
    }

    /** 只读不跑用的沙箱:每个 API 函数只记下这次调用、返回它声明的样子。 */
    private LuaSandbox.Builder reader(ScriptCatalog catalog, List<ScriptRun.Call> seen,
                                      LuaSandbox.Redefined redefined) {
        LuaSandbox.Builder sandbox = LuaSandbox.builder(LIMITS).missing(LuaEngine::missing).errors(LuaEngine::render)
                .redefined(redefined).unknown(LuaEngine::unknown).function(RAISE, LuaEngine::raise)
                .function(REQUIRE, LuaEngine::require);
        catalog.groups().forEach((group, functions) -> functions.forEach((name, declared) ->
                sandbox.function(pathName(group), functionName(name), in -> {
                    seen.add(call(group, name, in, declared));
                    return catalog.mark(declared.sample(), declared.returns(), LuaEngine::instance);
                })));
        // 只读不跑时模块不放上路径(它们的函数只记下调用),带方法的值从各自的模块另装一份取元表
        return sandbox.classes(source(catalog.modules()));
    }

    private static LuaSandbox.Outcome read(LuaSandbox.Builder sandbox, String name, String code) {
        try {
            return sandbox.build().start(name, code, List.of(), o -> { }).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while reading " + name, e);
        }
    }

    /**
     * 一次调用的参数:按顺序的对象,最后一个是名字到值的表就是选项。选项表只能在最后。最后一个是空表 {@code {}} 时它是没写选项的
     * 选项表:空表分不出是列表还是名字表,而写在最后的那张表就是选项的位置。
     */
    private static ScriptRun.Call call(String group, String name, List<Object> in, ScriptCatalog.Function declared) {
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
        return new ScriptRun.Call(LuaSandbox.currentLine(), group, name, java.util.Collections.unmodifiableList(objects),
                options);
    }

    @Override
    public ScriptRun start(String name, String code, ScriptCatalog catalog, Consumer<String> printer) {
        return new Run(name, code, catalog, printer);
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
        private final ScriptCatalog catalog;
        private final Consumer<String> printer;
        private final LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<>();
        private final SynchronousQueue<Answer> answers = new SynchronousQueue<>();
        private LuaSandbox.Running running;
        /** 交出去、还没交回结局的那一条。 */
        private Call pending;

        Run(String name, String code, ScriptCatalog catalog, Consumer<String> printer) {
            this.name = name;
            this.code = code;
            this.catalog = catalog;
            this.printer = printer;
        }

        @Override
        public Step start() {
            LuaSandbox.Builder sandbox = LuaSandbox.builder(LIMITS).print(printer).missing(LuaEngine::missing)
                    .redefined(LuaEngine::redefined).unknown(LuaEngine::unknown).errors(LuaEngine::render)
                    .show(LuaDisplay::of).function(RAISE, LuaEngine::raise).function(REQUIRE, LuaEngine::require)
                    .modules(source(catalog.modules()));
            catalog.groups().forEach((group, functions) -> functions.keySet().forEach(name ->
                    sandbox.function(pathName(group), functionName(name), in -> ask(group, name, in))));
            running = sandbox.build().start(name, code, List.of(), outcome -> events.add(new Ended(outcome)));
            return next();
        }

        @Override
        public Step resume(Result result) {
            Call call = pending;
            pending = null;
            if (!result.ok()) {
                Map<String, Object> error = new LinkedHashMap<>(result.error());
                error.put(ScriptRun.FN, call.function());
                if (error.get(ScriptRun.DATA) != null) {
                    error.put(ScriptRun.DATA, lua(error.get(ScriptRun.DATA)));
                }
                return answer(new Answer(null, error));
            }
            ScriptCatalog.Function fn = catalog.function(call.group(), call.name());
            return answer(new Answer(lua(catalog.mark(result.value(), fn == null ? null : fn.returns(),
                    LuaEngine::instance)), null));
        }

        /**
         * 交给沙箱的值:收着几个字段的表({@link JsonValues.Folded})换成沙箱的那一种,带方法的值({@link LuaSandbox.Instance})里面也换,
         * 其余原样。
         */
        private static Object lua(Object value) {
            return switch (value) {
                case JsonValues.Folded f -> new LuaSandbox.Folded(luaMap(f), luaMap(f.folded()));
                case LuaSandbox.Instance i -> new LuaSandbox.Instance(i.type(), i.home(), lua(i.value()));
                case java.util.Map<?, ?> m -> luaMap(m);
                case java.util.List<?> l -> l.stream().map(Run::lua).toList();
                case null, default -> value;
            };
        }

        private static java.util.Map<String, Object> luaMap(java.util.Map<?, ?> map) {
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), lua(v)));
            return out;
        }

        @Override
        public Step refuse(ApiError why) {
            Call call = pending;
            pending = null;
            return answer(new Answer(null, ScriptRun.failure(why.kind().wire(), why.getMessage(), why.hint(),
                    call.function(), lua(why.data()))));
        }

        @Override
        public void close() {
            if (running != null) {
                running.interrupt();
            }
        }

        @Override
        public List<String> modules() {
            return running == null ? List.of() : running.modules();
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
        private Object ask(String group, String name, List<Object> in) throws InterruptedException {
            events.add(new Asked(call(group, name, in, catalog.function(group, name))));
            Answer answer = answers.take();
            if (answer.raise() != null) {
                throw new LuaSandbox.ScriptError(answer.raise());
            }
            return answer.value();
        }
    }
}
