package com.dwinovo.lua;

import com.dwinovo.lua.vm.Allocation;
import com.dwinovo.lua.vm.Globals;
import com.dwinovo.lua.vm.LuaClosure;
import com.dwinovo.lua.vm.LuaError;
import com.dwinovo.lua.vm.LuaTable;
import com.dwinovo.lua.vm.LuaValue;
import com.dwinovo.lua.vm.Prototype;
import com.dwinovo.lua.vm.Varargs;
import com.dwinovo.lua.vm.compiler.LuaC;
import com.dwinovo.lua.vm.lib.BaseLib;
import com.dwinovo.lua.vm.lib.JseMathLib;
import com.dwinovo.lua.vm.lib.StringLib;
import com.dwinovo.lua.vm.lib.TableLib;
import com.dwinovo.lua.vm.lib.VarArgFunction;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 沙箱里的 Lua 5.2:一段脚本在它自己的虚拟线程上跑,宿主登记的函数可以阻塞(等一件慢事做完),不碰调用方的线程。
 *
 * <h2>沙箱</h2>
 * 全局只有基本函数(去掉了 load、loadstring、dofile、loadfile、require、collectgarbage、print 的上游实现)、string、table、math,
 * 加上宿主登记的函数与 {@code print}(交给宿主)。没有 io、os、debug、package、coroutine、luajava,也装载不了二进制块。字符串的
 * 元表与 string 库全 JVM 一份、只读,一段脚本改不了另一段看到的。
 *
 * <h2>预算</h2>
 * 每段脚本一份 {@link Limits}:两次调宿主函数之间的指令数、总指令数、字符串分配的总字节数、墙钟。到了就停下,停的方式脚本接
 * 不住(不是 Lua 错误,{@code pcall} 包着死循环也停),结局说哪一条、停在哪一行。{@link Running#interrupt} 随时喊停:在下一条
 * 指令或正在阻塞的宿主函数处停下。
 *
 * <h2>库</h2>
 * 宿主可以登记几段库({@link Builder#library}):每段脚本开跑之前,它们按登记顺序在同一个全局环境里先跑一遍,定义的函数脚本里直接
 * 能调(库可以往宿主函数的表里加函数,{@code function move.goto_(...) ... end})。行号只记脚本自己那一段:库里的函数调宿主函数时,
 * {@link #currentLine} 说的是脚本里调这个库函数的那一行,结局停在的也是脚本里的那一行。
 *
 * <h2>桥接</h2>
 * 宿主函数按 {@code 表名.函数名}(或全局名)登记,收按顺序的参数、交回一个值;值在两边按 {@link #toJava}/{@link #toLua} 换:nil 是
 * null,布尔、数(整数是 Long,其余是 Double)、字符串,表是列表(键恰好 1..n)或名字到值的表。宿主函数抛 {@link ScriptError}
 * 就是脚本在调用处得到的一个 Lua 错误({@code pcall} 接得住)。
 *
 * <h2>错误值</h2>
 * 宿主函数可以用一张名字到值的表做错误值({@link ScriptError#ScriptError(Map)}):脚本 {@code pcall} 接住的就是这张表,按字段分支;
 * 它带着沙箱的错误元表,{@code tostring} 得到 {@link Builder#errors} 给的那段文字。没接住时结局的那句话也是这段文字,结局另带上这张表
 * ({@link Outcome#error})。脚本自己 {@code error(表)} 抛出的表没接住时同样按这一处写成文字。
 *
 * <p>这个包与 {@code com.dwinovo.lua.vm} 不引用任何别的模组或游戏的类型。
 */
public final class LuaSandbox {

    /**
     * 一段脚本的预算。
     *
     * @param instructionsPerSlice 两次调宿主函数之间(以及开头到第一次)最多执行多少条指令
     * @param instructions         整段最多执行多少条指令
     * @param stringBytes          整段为字符串分配的字节数上限
     * @param wallClock            整段最长多久(含等宿主函数的时间)
     */
    public record Limits(long instructionsPerSlice, long instructions, long stringBytes, Duration wallClock) {}

    /** 宿主登记的一个函数。在脚本的线程上调,可以阻塞;被打断时抛 {@link InterruptedException}。 */
    @FunctionalInterface
    public interface HostFunction {
        /**
         * @param args 按顺序的参数,已换成 Java 值
         * @return 交回脚本的一个值(Java 值,见 {@link LuaSandbox});没有返回值给 null
         * @throws ScriptError 让这次调用在脚本里失败
         */
        Object call(List<Object> args) throws InterruptedException;
    }

    /**
     * 宿主函数让这次调用在脚本里失败:脚本在调用处得到一个 Lua 错误——一句话,或一张错误值的表(见类注释"错误值")。
     */
    public static final class ScriptError extends RuntimeException {

        private final transient Map<String, Object> value;

        /** 错误值是这句话。 */
        public ScriptError(String message) {
            super(message);
            this.value = null;
        }

        /** 错误值是这张表(名字到 Java 值),带沙箱的错误元表。 */
        public ScriptError(Map<String, Object> value) {
            super(String.valueOf(value));
            this.value = Collections.unmodifiableMap(new LinkedHashMap<>(value));
        }

        /** 错误值的表;错误值是一句话时为 null。 */
        public Map<String, Object> value() {
            return value;
        }
    }

    /** 一段脚本怎样结束的。 */
    public enum Ending {
        /** 跑到了最后。 */
        FINISHED,
        /** 读不通(语法错)。 */
        UNREADABLE,
        /** 脚本自己的错误没人接住(含 error(...))。 */
        ERROR,
        /** 两次调宿主函数之间的指令数超了。 */
        SLICE,
        /** 总指令数超了。 */
        INSTRUCTIONS,
        /** 字符串分配的字节数超了。 */
        STRINGS,
        /** 墙钟超了。 */
        WALL_CLOCK,
        /** 宿主喊了停。 */
        INTERRUPTED,
        /** 调用栈太深。 */
        STACK
    }

    /**
     * 一段脚本的结局。
     *
     * @param ending  怎样结束的
     * @param line    结束在哪一行(脚本自己那一段里的);跑完是 0,说不出是 0
     * @param message 给人看的那句话(Lua 的报错原话,或哪一条预算到了);跑完是 null
     * @param value   跑完时脚本 {@code return} 的第一个值,换成 Java 值(见 {@link LuaSandbox});没有返回值或没跑完是 null。
     *                函数这类换不了的值是它的 {@code tostring}
     * @param error   没接住的错误值是一张表时,那张表(名字到 Java 值);错误值是一句话、或不是出错结束的是 null
     */
    public record Outcome(Ending ending, int line, String message, Object value, Map<String, Object> error) {

        public Outcome(Ending ending, int line, String message, Object value) {
            this(ending, line, message, value, null);
        }

        public boolean finished() {
            return ending == Ending.FINISHED;
        }
    }

    /** Lua 5.2 的保留字:表名、函数名撞上它们,在脚本里点不出来({@code move.goto} 是语法错)。 */
    public static final Set<String> KEYWORDS = Collections.unmodifiableSet(new TreeSet<>(List.of(
            "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in", "local", "nil",
            "not", "or", "repeat", "return", "then", "true", "until", "while")));

    /** 沙箱里本来就有的全局名:宿主登记的名字不能占用它们。 */
    public static final Set<String> STANDARD_GLOBALS;

    /** Lua 报错的开头 {@code 块名:行号:}。 */
    private static final Pattern WHERE = Pattern.compile("^[^:\\n]*:(\\d+):");

    /** 当前线程上正在跑的那一段(宿主函数里问行号用);不在脚本线程上是 null。 */
    private static final ThreadLocal<Run> CURRENT = new ThreadLocal<>();

    static {
        Globals g = standardGlobals();
        Set<String> names = new TreeSet<>();
        LuaValue k = LuaValue.NIL;
        while (true) {
            Varargs next = g.next(k);
            k = next.arg1();
            if (k.isnil()) {
                break;
            }
            names.add(k.tojstring());
        }
        names.add("print");
        STANDARD_GLOBALS = Collections.unmodifiableSet(names);
    }

    private final Limits limits;
    private final Consumer<String> print;
    private final Map<String, HostFunction> globals;
    private final Map<String, Map<String, HostFunction>> tables;
    /** 库:块名 → 正文,按登记顺序。 */
    private final Map<String, String> libraries;
    /** 脚本读宿主函数表里没有的名字时报的那句话。 */
    private final Missing missing;
    /** 错误值的表写成文字:错误元表的 {@code __tostring},没接住时结局的那句话。 */
    private final java.util.function.Function<Map<String, Object>, String> errors;
    /** {@code print} 一张没有 {@code __tostring} 的表时怎么写它(换成 Java 值之后)。 */
    private final java.util.function.Function<Object, String> show;

    /** 脚本读宿主函数表里没有的名字({@code area.hsa}):报什么错。 */
    @FunctionalInterface
    public interface Missing {

        /**
         * @param table   表名
         * @param key     读的名字
         * @param present 表里此刻有的名字(宿主函数与库加进去的)
         * @return 停在那一行的错误:一句话,或一张错误值的表
         */
        ScriptError error(String table, String key, List<String> present);
    }

    private LuaSandbox(Builder b) {
        this.limits = b.limits;
        this.print = b.print;
        this.globals = Map.copyOf(b.globals);
        this.libraries = Collections.unmodifiableMap(new LinkedHashMap<>(b.libraries));
        this.missing = b.missing;
        this.errors = b.errors;
        this.show = b.show;
        Map<String, Map<String, HostFunction>> t = new LinkedHashMap<>();
        b.tables.forEach((name, fns) -> t.put(name, Map.copyOf(fns)));
        this.tables = Collections.unmodifiableMap(t);
    }

    public static Builder builder(Limits limits) {
        return new Builder(limits);
    }

    /** 造一个沙箱:登记宿主函数与 print。 */
    public static final class Builder {
        private final Limits limits;
        private Consumer<String> print = line -> { };
        private final Map<String, HostFunction> globals = new LinkedHashMap<>();
        private final Map<String, Map<String, HostFunction>> tables = new LinkedHashMap<>();
        private final Map<String, String> libraries = new LinkedHashMap<>();
        private Missing missing = (table, key, present) -> new ScriptError("there is no function " + table + "." + key);
        private java.util.function.Function<Map<String, Object>, String> errors = String::valueOf;
        private java.util.function.Function<Object, String> show = String::valueOf;

        private Builder(Limits limits) {
            this.limits = limits;
        }

        /** 错误值的表怎样写成文字:{@code tostring(err)} 与没接住时结局的那句话都经它。 */
        public Builder errors(java.util.function.Function<Map<String, Object>, String> errors) {
            this.errors = errors;
            return this;
        }

        /** 脚本读宿主函数表里没有的名字时报的错:按它给的那句话停在那一行。 */
        public Builder missing(Missing missing) {
            this.missing = missing;
            return this;
        }

        /**
         * {@code print} 一张表(没有自己的 {@code __tostring})时写成什么:收它换成的 Java 值(列表、名字到值的表)。不设就是 Java 的
         * {@code toString}。
         */
        public Builder show(java.util.function.Function<Object, String> show) {
            this.show = show;
            return this;
        }

        /** {@code print(...)} 写出的每一行(参数按 tostring 写、制表符隔开)交给它。 */
        public Builder print(Consumer<String> print) {
            this.print = print;
            return this;
        }

        /** 登记一个全局函数。 */
        public Builder function(String name, HostFunction fn) {
            checkName(name);
            if (tables.containsKey(name) || globals.put(name, fn) != null) {
                throw new IllegalArgumentException("全局名 " + name + " 登记了两次");
            }
            return this;
        }

        /** 登记 {@code table.name} 这个函数;表没有就新建。 */
        public Builder function(String table, String name, HostFunction fn) {
            checkName(table);
            checkName(name);
            if (globals.containsKey(table)) {
                throw new IllegalArgumentException("全局名 " + table + " 已经是一个函数");
            }
            if (tables.computeIfAbsent(table, t -> new LinkedHashMap<>()).put(name, fn) != null) {
                throw new IllegalArgumentException(table + "." + name + " 登记了两次");
            }
            return this;
        }

        /**
         * 登记一段库:每段脚本开跑之前先跑它,按登记顺序。它的报错开头是 {@code chunkName}。库在宿主函数之后装,可以往宿主函数的
         * 表里加函数。
         */
        public Builder library(String chunkName, String code) {
            if (libraries.put(chunkName, code) != null) {
                throw new IllegalArgumentException("库 " + chunkName + " 登记了两次");
            }
            return this;
        }

        private static void checkName(String name) {
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || KEYWORDS.contains(name)) {
                throw new IllegalArgumentException("在 Lua 里点不出来的名字: " + name);
            }
            if (STANDARD_GLOBALS.contains(name)) {
                throw new IllegalArgumentException("名字 " + name + " 是沙箱自带的全局");
            }
        }

        public LuaSandbox build() {
            return new LuaSandbox(this);
        }
    }

    /**
     * 读一段脚本,不运行:读不通返回 Lua 的报错原话({@code mine:3: '=' expected near 'x'}),读得通是 null。和运行时是同一个编译器。
     */
    public static String check(String chunkName, String code) {
        try {
            compile(chunkName, code);
            return null;
        } catch (LuaError e) {
            return e.getMessage();
        }
    }

    /** Lua 报错原话开头的行号({@code mine:3: ...} 是 3);说不出是 0。 */
    public static int lineOf(String message) {
        Matcher m = WHERE.matcher(message == null ? "" : message);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /**
     * 在宿主函数里问:脚本是在哪一行调的这个函数——脚本自己那一段里的行;经库里的函数调到这里的,是脚本里调那个库函数的那一行。
     * 不在脚本的线程上是 0。
     */
    public static int currentLine() {
        Run run = CURRENT.get();
        return run == null ? 0 : run.line;
    }

    /**
     * 开跑:新起一个虚拟线程跑这段脚本,当场返回。
     *
     * @param chunkName 报错开头的那个名字({@code mine:3: ...})
     * @param args      运行参数:脚本里的 {@code ...} 与 {@code arg[1]}…
     * @param done      结束时在脚本的线程上调一次
     */
    public Running start(String chunkName, String code, List<String> args, Consumer<Outcome> done) {
        Run run = new Run(chunkName, code, args, done);
        Thread thread = Thread.ofVirtual().name("lua-" + chunkName).unstarted(run);
        run.thread = thread;
        thread.start();
        return run;
    }

    /** 一段在跑的脚本。 */
    public interface Running {
        /** 喊停:在下一条指令或正在阻塞的宿主函数处停下,结局是 {@link Ending#INTERRUPTED}。跑完了再喊没有作用。 */
        void interrupt();

        /** 等它结束,返回结局。 */
        Outcome await() throws InterruptedException;
    }

    // ---- 一次运行 ----

    /** 预算到了、或被喊停:沿 Lua 调用栈一路往外抛,pcall 接不住(它只接 Exception)。 */
    private static final class Stop extends Error {
        final Ending ending;

        Stop(Ending ending, String message) {
            super(message, null, false, false);
            this.ending = ending;
        }
    }

    private final class Run implements Runnable, Running, Globals.Hook, Allocation.Meter {

        private final String chunkName;
        private final String code;
        private final List<String> args;
        private final Consumer<Outcome> done;
        private final long started = System.nanoTime();
        private final CountDownLatch finished = new CountDownLatch(1);
        private volatile boolean interrupted;
        private volatile Outcome outcome;
        Thread thread;

        private long slice;
        private long total;
        private long stringBytes;
        /** 脚本自己那一段里最近执行的那条指令在哪一行(库里的指令不算)。 */
        int line;
        /** 脚本自己那一段的块名,{@link Prototype#source} 的写法:库里的指令据此不记行号。 */
        private LuaValue mainSource;

        Run(String chunkName, String code, List<String> args, Consumer<Outcome> done) {
            this.chunkName = chunkName;
            this.code = code;
            this.args = List.copyOf(args);
            this.done = done;
        }

        @Override
        public void run() {
            CURRENT.set(this);
            Allocation.bind(this);
            Outcome result;
            try {
                result = execute();
            } finally {
                Allocation.unbind();
                CURRENT.remove();
            }
            outcome = result;
            finished.countDown();
            done.accept(result);
        }

        private Outcome execute() {
            LuaValue main;
            Globals g;
            List<LuaValue> libs = new ArrayList<>();
            try {
                g = standardGlobals();
                install(g);
                for (Map.Entry<String, String> lib : libraries.entrySet()) {
                    libs.add(g.load(lib.getValue(), "=" + lib.getKey()));
                }
                main = g.load(code, "=" + chunkName);
            } catch (LuaError e) {
                return new Outcome(Ending.UNREADABLE, lineOf(e.getMessage()), e.getMessage(), null);
            }
            mainSource = ((LuaClosure) main).p.source;
            g.hook = this;
            LuaValue[] values = new LuaValue[args.size()];
            LuaTable arg = new LuaTable();
            for (int i = 0; i < values.length; i++) {
                values[i] = LuaValue.valueOf(args.get(i));
                arg.rawset(i + 1, values[i]);
            }
            g.rawset("arg", arg);
            try {
                for (LuaValue lib : libs) {
                    lib.call();
                }
                Varargs returned = main.invoke(LuaValue.varargsOf(values));
                return new Outcome(Ending.FINISHED, 0, null, returned(returned.arg1()));
            } catch (Stop stop) {
                return new Outcome(stop.ending, line, chunkName + ":" + line + ": " + stop.getMessage(), null);
            } catch (LuaError e) {
                LuaValue thrown = e.getMessageObject();
                if (thrown != null && thrown.istable()) {
                    Map<String, Object> value = errorValue(thrown);
                    return new Outcome(Ending.ERROR, line, errors.apply(value), null, value);
                }
                return new Outcome(Ending.ERROR, line, e.getMessage(), null);
            } catch (StackOverflowError deep) {
                return new Outcome(Ending.STACK, line, chunkName + ":" + line + ": stack overflow (a function that "
                        + "calls itself without end?)", null);
            }
        }

        /** 一张错误值的表换成 Java 值;里面有换不了的值(函数)的那几项写成它们的 tostring。 */
        private static Map<String, Object> errorValue(LuaValue table) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Varargs kv = table.next(LuaValue.NIL); !kv.arg1().isnil(); kv = table.next(kv.arg1())) {
                out.put(kv.arg1().tojstring(), returned(kv.arg(2)));
            }
            return out;
        }

        /** 脚本 return 的值换成 Java 值;函数这类换不了的,是它的 tostring。 */
        private static Object returned(LuaValue value) {
            try {
                return toJava(value);
            } catch (LuaError notAValue) {
                return value.tojstring();
            }
        }

        /** 宿主抛出的错误值的表共用的元表:{@code tostring} 按 {@link Builder#errors} 写。 */
        private LuaTable errorMeta;

        private void install(Globals g) {
            errorMeta = new LuaTable();
            errorMeta.rawset("__tostring", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs in) {
                    return LuaValue.valueOf(errors.apply(errorValue(in.arg1())));
                }
            });
            // 错误值拼进字符串("could not dig: " .. err)时按它的文字拼,和一句话的错误值一样用
            errorMeta.rawset("__concat", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs in) {
                    return LuaValue.valueOf(text(in.arg(1)) + text(in.arg(2)));
                }

                private String text(LuaValue v) {
                    return v.istable() && v.getmetatable() == errorMeta ? errors.apply(errorValue(v)) : v.tojstring();
                }
            });
            g.rawset("print", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs in) {
                    LuaValue tostring = g.get("tostring");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i <= in.narg(); i++) {
                        if (i > 1) {
                            sb.append('\t');
                        }
                        LuaValue v = in.arg(i);
                        LuaValue meta = v.getmetatable();
                        boolean plain = v.istable() && (meta == null || meta.rawget("__tostring").isnil());
                        sb.append(plain ? show.apply(returned(v)) : tostring.call(v).tojstring());
                    }
                    print.accept(sb.toString());
                    return NONE;
                }
            });
            globals.forEach((name, fn) -> g.rawset(name, host(fn)));
            tables.forEach((group, fns) -> {
                LuaTable t = new LuaTable();
                fns.forEach((fnName, fn) -> t.rawset(fnName, host(fn)));
                // 读表里没有的名字:当场报那句话,不让它成 nil 再在调用处报"调了一个 nil"
                LuaTable meta = new LuaTable();
                meta.rawset("__index", new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs in) {
                        List<String> present = new ArrayList<>();
                        LuaValue k = LuaValue.NIL;
                        while (true) {
                            Varargs next = t.next(k);
                            if ((k = next.arg1()).isnil()) {
                                break;
                            }
                            present.add(k.tojstring());
                        }
                        throw luaError(missing.error(group, in.arg(2).tojstring(), present));
                    }
                });
                t.setmetatable(meta);
                g.rawset(group, t);
            });
        }

        /** 宿主给的错误换成脚本里的 Lua 错误:一句话照原样,一张表带上错误元表。 */
        private LuaError luaError(ScriptError e) {
            if (e.value() == null) {
                return new LuaError(e.getMessage());
            }
            LuaValue value = toLua(e.value());
            value.setmetatable(errorMeta);
            return new LuaError(value);
        }

        private LuaValue host(HostFunction fn) {
            return new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs in) {
                    List<Object> javaArgs = new ArrayList<>();
                    for (int i = 1; i <= in.narg(); i++) {
                        javaArgs.add(toJava(in.arg(i)));
                    }
                    Object out;
                    try {
                        out = fn.call(javaArgs);
                    } catch (ScriptError e) {
                        throw luaError(e);
                    } catch (InterruptedException e) {
                        throw new Stop(Ending.INTERRUPTED, "the script was stopped");
                    } finally {
                        slice = 0;
                    }
                    if (interrupted) {
                        throw new Stop(Ending.INTERRUPTED, "the script was stopped");
                    }
                    checkWall();
                    return toLua(out);
                }
            };
        }

        // ---- 钩子与计量 ----

        @Override
        public void onInstruction(Prototype p, int pc) {
            if (p.lineinfo != null && pc < p.lineinfo.length && mainSource.raweq(p.source)) {
                line = p.lineinfo[pc];
            }
            if (++slice > limits.instructionsPerSlice()) {
                throw new Stop(Ending.SLICE, "ran " + limits.instructionsPerSlice()
                        + " instructions without calling a host function; a loop that never calls one never ends");
            }
            if (++total > limits.instructions()) {
                throw new Stop(Ending.INSTRUCTIONS, "ran past " + limits.instructions() + " instructions in all");
            }
            if (interrupted) {
                throw new Stop(Ending.INTERRUPTED, "the script was stopped");
            }
            if ((total & 1023) == 0) {
                checkWall();
            }
        }

        private void checkWall() {
            if (System.nanoTime() - started > limits.wallClock().toNanos()) {
                throw new Stop(Ending.WALL_CLOCK, "ran past " + limits.wallClock().toSeconds() + " seconds");
            }
        }

        @Override
        public void charge(long bytes) {
            stringBytes += bytes;
            if (stringBytes > limits.stringBytes()) {
                throw new Stop(Ending.STRINGS, "made more than " + limits.stringBytes()
                        + " bytes of strings; build long text in pieces, or not at all");
            }
        }

        // ---- Running ----

        @Override
        public void interrupt() {
            interrupted = true;
            thread.interrupt();
        }

        @Override
        public Outcome await() throws InterruptedException {
            finished.await();
            return outcome;
        }
    }

    // ---- 环境与值 ----

    /** 沙箱的标准环境:基本函数(上游 BaseLib 已去掉装载代码与碰 JVM 的那些)、string、table、math;编译器只收文本。 */
    private static Globals standardGlobals() {
        Globals g = new Globals();
        g.load(new BaseLib());
        g.load(new TableLib());
        g.load(new StringLib());
        g.load(new JseMathLib());
        LuaC.install(g);
        return g;
    }

    private static void compile(String chunkName, String code) {
        standardGlobals().load(code, "=" + chunkName);
    }

    /** Lua 值换成 Java 值。函数、userdata 换不了,抛一个脚本接得住的错误。 */
    public static Object toJava(LuaValue v) {
        switch (v.type()) {
            case LuaValue.TNIL:
                return null;
            case LuaValue.TBOOLEAN:
                return v.toboolean();
            case LuaValue.TNUMBER: {
                double d = v.todouble();
                return d == Math.rint(d) && Math.abs(d) < 9.0e15 ? (Object) (long) d : (Object) d;
            }
            case LuaValue.TSTRING:
                return v.tojstring();
            case LuaValue.TTABLE: {
                LuaTable t = (LuaTable) v;
                int n = t.length();
                int keys = 0;
                for (LuaValue k = t.next(LuaValue.NIL).arg1(); !k.isnil(); k = t.next(k).arg1()) {
                    keys++;
                }
                if (keys == n) {
                    List<Object> list = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) {
                        list.add(toJava(t.get(i)));
                    }
                    return list;
                }
                // 键按名字排:表里的先后是散列的先后,每次不一样;排好了写出来、读回来都是同一个样子
                Map<String, Object> map = new java.util.TreeMap<>();
                for (Varargs kv = t.next(LuaValue.NIL); !kv.arg1().isnil(); kv = t.next(kv.arg1())) {
                    map.put(kv.arg1().tojstring(), toJava(kv.arg(2)));
                }
                return map;
            }
            default:
                throw new LuaError("a " + v.typename() + " cannot be passed to the host");
        }
    }

    /** Java 值换成 Lua 值:null、布尔、数、字符串、列表、名字到值的表。 */
    public static LuaValue toLua(Object o) {
        if (o == null) {
            return LuaValue.NIL;
        }
        if (o instanceof Boolean b) {
            return LuaValue.valueOf(b);
        }
        if (o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte) {
            long l = ((Number) o).longValue();
            return l == (int) l ? LuaValue.valueOf((int) l) : LuaValue.valueOf((double) l);
        }
        if (o instanceof Number n) {
            return LuaValue.valueOf(n.doubleValue());
        }
        if (o instanceof String s) {
            return LuaValue.valueOf(s);
        }
        if (o instanceof List<?> list) {
            LuaTable t = new LuaTable(list.size(), 0);
            for (int i = 0; i < list.size(); i++) {
                t.rawset(i + 1, toLua(list.get(i)));
            }
            return t;
        }
        if (o instanceof Map<?, ?> map) {
            LuaTable t = new LuaTable();
            map.forEach((k, v) -> t.rawset(String.valueOf(k), toLua(v)));
            return t;
        }
        throw new IllegalArgumentException("not a value Lua can hold: " + o.getClass().getName());
    }
}
