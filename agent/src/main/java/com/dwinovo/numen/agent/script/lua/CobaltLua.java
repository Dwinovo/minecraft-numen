package com.dwinovo.numen.agent.script.lua;

import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.script.ScriptLimits;
import com.dwinovo.numen.agent.script.ScriptRun;
import com.dwinovo.numen.agent.script.ScriptRun.Call;
import com.dwinovo.numen.agent.script.ScriptRun.Done;
import com.dwinovo.numen.agent.script.ScriptRun.Result;
import com.dwinovo.numen.agent.script.ScriptRun.Step;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.squiddev.cobalt.Constants;
import org.squiddev.cobalt.LuaError;
import org.squiddev.cobalt.LuaState;
import org.squiddev.cobalt.LuaTable;
import org.squiddev.cobalt.LuaThread;
import org.squiddev.cobalt.LuaUserdata;
import org.squiddev.cobalt.LuaValue;
import org.squiddev.cobalt.OperationHelper;
import org.squiddev.cobalt.UnwindThrowable;
import org.squiddev.cobalt.ValueFactory;
import org.squiddev.cobalt.Varargs;
import org.squiddev.cobalt.compiler.CompileException;
import org.squiddev.cobalt.compiler.LoadState;
import org.squiddev.cobalt.debug.DebugFrame;
import org.squiddev.cobalt.debug.DebugHook;
import org.squiddev.cobalt.debug.DebugState;
import org.squiddev.cobalt.function.LuaFunction;
import org.squiddev.cobalt.function.ResumableVarArgFunction;
import org.squiddev.cobalt.function.VarArgFunction;
import org.squiddev.cobalt.interrupt.InterruptAction;
import org.squiddev.cobalt.lib.CoreLibraries;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
/**
 * 脚本语言是 Lua 5.2,虚拟机是 Cobalt——Cobalt 的类型只在这个类里出现。
 *
 * <p>一次运行({@link Run}):脚本在自己的协程里跑;调一个命令函数({@code work.dig(...)})就在调用处让出,把这条命令交给驱动它的
 * 一方,拿到结局再从调用处接着跑。所以等身体干活时脚本不占任何线程,也不阻塞谁。
 *
 * <h2>沙箱</h2>
 * 每次运行一个新的虚拟机,只装 Lua 自己的安全标准库(基本函数、string、table、math、coroutine、utf8),拿掉能装载代码或改环境的
 * ({@code load}、{@code loadstring}、{@code setfenv}、{@code getfenv}、{@code string.dump});没有 io、os、debug、require——
 * Cobalt 本来就不装它们。再放进命令函数(每个命令组一张表)、{@code print}(写进回执)、{@code arg} 与 {@code ...}(运行参数)。
 *
 * <h2>指令预算</h2>
 * 两次调命令之间最多执行 {@link ScriptLimits#INSTRUCTIONS_PER_SLICE} 条指令。数指令的钩子挂在虚拟机主线程上,脚本的协程与
 * 脚本自己开的协程都继承它;超了就把整个虚拟机挂起(不是抛 Lua 错误,{@code pcall} 接不住),这次运行到此为止。
 */
public final class CobaltLua implements ScriptEngine {

    /** Lua 报错的开头 {@code 块名:行号:}。 */
    private static final Pattern WHERE = Pattern.compile("^[^:\\n]*:(\\d+):");
    /** 数指令的粒度:每执行这么多条查一次预算。 */
    private static final int INSTRUCTION_CHECK = 1_000;

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
                + "`move.goto(\"ores/g3\", {arrive = \"dig\"})`. An object written as several words on the command "
                + "line is a list here: `{120, 64, -35}`. A command function returns "
                + "`{ok = true or false, text = what the command said, data = {...}}`; a query whose help says so "
                + "returns its value directly, so `for _, p in ipairs(area.parts(\"ores\"))` loops over it, and raises "
                + "an error when it fails. `print(...)` writes into the receipt; `...` and `arg` hold the arguments "
                + "of a script run by name; `error(\"why\")` ends it as failed.";
    }

    @Override
    public String comment(String text) {
        return "-- " + text;
    }

    /**
     * 读不通返回 Lua 的报错原话({@code mine:3: '=' expected near 'x'})。存脚本、登记内置脚本之前都过这一道,和运行时读它是同一个
     * 编译器。
     */
    @Override
    public String check(String name, String code) {
        try {
            load(LuaState.builder().build(), name, code);
            return null;
        } catch (LuaError | CompileException e) {
            return e.getMessage();
        }
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

    /** Lua 报错原话里的行号({@code mine:3: ...} 是 3);说不出是 0。 */
    static int lineOf(String error) {
        Matcher m = WHERE.matcher(error == null ? "" : error);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** 一次运行。 */
    private static final class Run implements ScriptRun {
        private final String name;
        private final ScriptCatalog catalog;
        private final Consumer<String> printer;
        private final LuaState state;
        /** 脚本自己的协程;读不通时是 null。 */
        private final LuaThread co;
        /** 读不通的结局;读通了是 null。 */
        private final Done unreadable;
        private final Varargs args;

        /** 交出去、还没拿回结局的那一条。 */
        private Call pending;
        /** 这一段(两次调命令之间)执行了多少条指令。 */
        private long instructions;
        /** 超了预算,挂起在哪一行;没超是 0。 */
        private int overBudgetAt;

        /**
         * @param name    脚本名,进报错的开头({@code mine:3:});她当场写的一段是 {@code lua}
         * @param args    运行参数:{@code ...} 与 {@code arg[1]}…
         * @param printer {@code print} 写出的每一行
         */
        Run(String name, String code, List<String> args, ScriptCatalog catalog, Consumer<String> printer) {
            this.name = name;
            this.catalog = catalog;
            this.printer = printer;
            this.state = LuaState.builder()
                    .interruptHandler(() -> overBudgetAt != 0 ? InterruptAction.SUSPEND : InterruptAction.CONTINUE)
                    .build();
            LuaValue[] values = new LuaValue[args.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = ValueFactory.valueOf(args.get(i));
            }
            this.args = ValueFactory.varargsOf(values);
            LuaThread thread = null;
            Done failed = null;
            try {
                LuaFunction main = load(state, name, code);
                sandbox();
                countInstructions();
                // 主线程上挂了钩子之后再开协程:新协程从当前线程继承钩子
                thread = new LuaThread(state, main);
            } catch (LuaError | CompileException e) {
                failed = failure(e.getMessage());
            }
            this.co = thread;
            this.unreadable = failed;
        }

        /** 开跑,直到第一条命令或结束。 */
        @Override
        public Step start() {
            if (unreadable != null) {
                return unreadable;
            }
            return run(args);
        }

        /**
         * 交回上一条命令的结局,接着跑到下一条命令或结束。声明了回值的命令成功时函数返回回执数据里的那一项,失败时在调用处抛出
         * Lua 错误(查询失败了没有值可循环,照常往下走只会在错的东西上空转);其余命令函数返回结果表 {@code {ok, text, data}}。
         */
        @Override
        public Step resume(Result result) {
            Call call = pending;
            pending = null;
            ScriptCatalog.Verb verb = catalog.verb(call.group(), call.verb());
            LuaValue back;
            if (verb != null && verb.returns() != null) {
                back = result.ok()
                        ? toLua(result.data().get(verb.returns()))
                        : new LuaUserdata(new Raise(call.function() + ": " + result.text()));
            } else {
                LuaTable table = new LuaTable();
                table.rawset("ok", ValueFactory.valueOf(result.ok()));
                table.rawset("text", ValueFactory.valueOf(result.text()));
                table.rawset("data", toLua(result.data()));
                back = table;
            }
            return run(ValueFactory.varargsOf(back));
        }

        /** 让一条交出去的命令在调用处失败,不执行它(写法不合这个动作的参数表)。 */
        @Override
        public Step refuse(String why) {
            Call call = pending;
            pending = null;
            return run(ValueFactory.varargsOf(new LuaUserdata(new Raise(call.function() + ": " + why))));
        }

        private Step run(Varargs in) {
            instructions = 0;
            Varargs out;
            try {
                out = LuaThread.run(co, in);
            } catch (LuaError e) {
                String said = e.getValue() == null ? e.getMessage() : OperationHelper.toStringDirect(e.getValue()).toString();
                // error(msg, 0) 不带位置:行号从虚拟机自己写的调用栈里取最里面那一层脚本
                int line = lineOf(said);
                if (line == 0) {
                    Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(name) + ":(\\d+):").matcher(e.getMessage());
                    line = m.find() ? Integer.parseInt(m.group(1)) : 0;
                }
                return new Done(false, line, said);
            }
            if (overBudgetAt != 0) {
                return new Done(false, overBudgetAt, name + ":" + overBudgetAt + ": ran " + ScriptLimits.INSTRUCTIONS_PER_SLICE
                        + " instructions without calling a command; a loop that never calls a command never ends");
            }
            if (co.getStatus() == LuaThread.Status.DEAD) {
                return new Done(true, 0, null);
            }
            if (out != null && out.first() instanceof LuaUserdata u && u.instance instanceof Call call) {
                pending = call;
                return call;
            }
            return failure(name + ": coroutine.yield is for coroutines the script creates itself; the script was yielded "
                    + "outside of one");
        }

        private Done failure(String error) {
            return new Done(false, lineOf(error), error);
        }

        private void sandbox() throws LuaError {
            LuaTable g = state.globals();
            for (String unsafe : List.of("load", "loadstring", "setfenv", "getfenv")) {
                g.rawset(unsafe, Constants.NIL);
            }
            ((LuaTable) g.rawget("string")).rawset("dump", Constants.NIL);
            g.rawset("print", new Print());
            LuaTable arg = new LuaTable();
            for (int i = 1; i <= args.count(); i++) {
                arg.rawset(i, args.arg(i));
            }
            g.rawset("arg", arg);
            for (Map.Entry<String, Map<String, ScriptCatalog.Verb>> group : catalog.groups().entrySet()) {
                if (!g.rawget(group.getKey()).isNil()) {
                    continue;   // 撞了 Lua 自己的全局(string、table……)的组不进脚本:标准库不让给命令
                }
                LuaTable verbs = new LuaTable();
                for (String verb : group.getValue().keySet()) {
                    verbs.rawset(verb, new Command(group.getKey(), verb));
                }
                g.rawset(group.getKey(), verbs);
            }
        }

        private void countInstructions() {
            DebugHook counter = new DebugHook() {
                @Override
                public void onCount(LuaState s, DebugState ds, DebugFrame frame) throws LuaError, UnwindThrowable {
                    instructions += INSTRUCTION_CHECK;
                    if (instructions > ScriptLimits.INSTRUCTIONS_PER_SLICE) {
                        overBudgetAt = Math.max(1, frame.currentLine());
                        s.handleInterrupt();   // 处理函数答 SUSPEND:整个虚拟机挂起,pcall 接不住
                    }
                }
            };
            state.getMainThread().getDebugState().setHook(counter, false, false, false, INSTRUCTION_CHECK);
        }

        // ---- 函数 ----

        /** 一个命令函数:收参数、在调用处让出这条命令,交回结局时从这里接着走。 */
        private final class Command extends ResumableVarArgFunction<Call> {

            private final String group;
            private final String verb;

            Command(String group, String verb) {
                this.group = group;
                this.verb = verb;
            }

            @Override
            protected Varargs invoke(LuaState s, DebugFrame di, Varargs in) throws LuaError, UnwindThrowable {
                if (s.getCurrentThread() != co) {
                    throw new LuaError(group + "." + verb + " can only be called by the script itself, not from inside "
                            + "a coroutine it created");
                }
                List<Object> objects = new ArrayList<>();
                Map<String, Object> options = Map.of();
                int n = in.count();
                for (int i = 1; i <= n; i++) {
                    LuaValue v = in.arg(i);
                    if (i == n && v instanceof LuaTable t && !isList(t)) {
                        options = options(t);
                    } else {
                        objects.add(fromLua(v, group + "." + verb + " argument " + i));
                    }
                }
                Call call = new Call(callerLine(s), group, verb, List.copyOf(objects), options);
                di.state = call;
                return LuaThread.yield(s, ValueFactory.varargsOf(new LuaUserdata(call)));
            }

            @Override
            public Varargs resume(LuaState s, Call call, Varargs value) throws LuaError {
                if (value.first() instanceof LuaUserdata u && u.instance instanceof Raise raise) {
                    throw new LuaError(raise.message());
                }
                return value;
            }
        }

        /** {@code print}:各参数按 Lua 的 {@code tostring} 写法(不调元方法),制表符隔开,写成回执里的一行。 */
        private final class Print extends VarArgFunction {

            @Override
            protected Varargs invoke(LuaState s, Varargs in) {
                StringBuilder line = new StringBuilder();
                for (int i = 1; i <= in.count(); i++) {
                    if (i > 1) {
                        line.append('\t');
                    }
                    line.append(OperationHelper.toStringDirect(in.arg(i)));
                }
                printer.accept(line.toString());
                return Constants.NONE;
            }
        }
    }

    /** 让命令函数在调用处抛出的那句话。 */
    private record Raise(String message) {}

    // ---- 装载与沙箱 ----

    private static LuaFunction load(LuaState state, String name, String code) throws LuaError, CompileException {
        CoreLibraries.standardGlobals(state);
        return LoadState.load(state, new ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8)), "=" + name,
                state.globals());
    }

    /** 调用处在脚本的哪一行:往外找第一层 Lua 函数。 */
    private static int callerLine(LuaState state) {
        DebugState ds = state.getCurrentThread().getDebugState();
        for (int i = 0; ; i++) {
            DebugFrame frame = ds.getFrame(i);
            if (frame == null) {
                return 0;
            }
            if (frame.closure != null) {
                return frame.currentLine();
            }
        }
    }

    // ---- 值的换算 ----

    /** 一张表是不是列表:键恰好是 1..n。空表算列表(空列表)。 */
    private static boolean isList(LuaTable t) throws LuaError {
        int n = t.length();
        int count = 0;
        LuaValue k = Constants.NIL;
        while (true) {
            Varargs next = t.next(k);
            k = next.first();
            if (k.isNil()) {
                return count == n;
            }
            count++;
        }
    }

    private static Map<String, Object> options(LuaTable t) throws LuaError {
        Map<String, Object> out = new LinkedHashMap<>();
        LuaValue k = Constants.NIL;
        while (true) {
            Varargs next = t.next(k);
            k = next.first();
            if (k.isNil()) {
                return out;
            }
            if (!k.isString() || k.type() == Constants.TNUMBER) {
                throw new LuaError("options are written {name=value}; got a key " + OperationHelper.toStringDirect(k));
            }
            out.put(k.toString(), fromLua(next.arg(2), "option " + k));
        }
    }

    private static Object fromLua(LuaValue v, String what) throws LuaError {
        return switch (v.type()) {
            case Constants.TSTRING -> v.toString();
            case Constants.TNUMBER -> {
                double d = v.toDouble();
                yield d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) (long) d : (Object) d;
            }
            case Constants.TBOOLEAN -> v.toBoolean();
            case Constants.TTABLE -> {
                LuaTable t = (LuaTable) v;
                if (!isList(t)) {
                    throw new LuaError(what + ": a table here is a list {a, b, c}; options {name=value} go last");
                }
                List<Object> list = new ArrayList<>();
                for (int i = 1; i <= t.length(); i++) {
                    list.add(fromLua(t.rawget(i), what));
                }
                yield List.copyOf(list);
            }
            default -> throw new LuaError(what + " is " + v.typeName() + "; commands take strings, numbers, "
                    + "booleans and lists");
        };
    }

    /** 回执里的 JSON 换成 Lua 值:对象成表、数组成从 1 开始的表、null 成 nil。 */
    static LuaValue toLua(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return Constants.NIL;
        }
        if (e instanceof JsonPrimitive p) {
            if (p.isBoolean()) {
                return ValueFactory.valueOf(p.getAsBoolean());
            }
            if (p.isNumber()) {
                double d = p.getAsDouble();
                return d == Math.rint(d) && Math.abs(d) < Integer.MAX_VALUE
                        ? ValueFactory.valueOf((int) d) : ValueFactory.valueOf(d);
            }
            return ValueFactory.valueOf(p.getAsString());
        }
        LuaTable t = new LuaTable();
        if (e instanceof JsonArray a) {
            for (int i = 0; i < a.size(); i++) {
                t.rawset(i + 1, toLua(a.get(i)));
            }
            return t;
        }
        for (Map.Entry<String, JsonElement> entry : ((JsonObject) e).entrySet()) {
            t.rawset(entry.getKey(), toLua(entry.getValue()));
        }
        return t;
    }
}
