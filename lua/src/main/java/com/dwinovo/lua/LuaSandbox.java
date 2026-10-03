package com.dwinovo.lua;

import com.dwinovo.lua.vm.Allocation;
import com.dwinovo.lua.vm.FixedKeysTable;
import com.dwinovo.lua.vm.Globals;
import com.dwinovo.lua.vm.LuaClosure;
import com.dwinovo.lua.vm.LuaError;
import com.dwinovo.lua.vm.LuaString;
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
 * 虚拟机({@code com.dwinovo.lua.vm})是一份完整的 Lua 5.2 解释器,只带中立的挂点(逐条指令的钩子、字符串分配记账、定死几个键的表),
 * 默认什么都不限。装什么、限什么全在这里({@link #standardGlobals}):全局只有基本函数(装载代码、碰文件与 JVM 的 load、dofile、
 * loadfile、collectgarbage 不装,print 换成交给宿主的那一个)、string、table、math,加上宿主登记的函数。这一份虚拟机本来就不带 io、
 * os、debug、package、coroutine、luajava 与二进制块的装载。string 库表与字符串元表全 JVM 一份、只读({@link #STRING}),一段脚本改不了
 * 另一段看到的。
 *
 * <h2>预算</h2>
 * 每段脚本一份 {@link Limits}:两次调宿主函数之间的指令数、总指令数、字符串分配的总字节数、墙钟。到了就停下,停的方式脚本接
 * 不住(不是 Lua 错误,{@code pcall} 包着死循环也停),结局说哪一条、停在哪一行。{@link Running#interrupt} 随时喊停:在下一条
 * 指令或正在阻塞的宿主函数处停下。
 *
 * <h2>模块</h2>
 * 宿主可以给一个模块来源({@link Builder#modules}):模块是一段返回一张函数表的正文,脚本里以模块名作全局名直接用
 * ({@code lumber.chop(…)}),第一次用到时才向来源要正文、在同一个全局环境里跑一遍,所以每次运行拿到的都是来源此刻的那一份。和宿主
 * 函数表同名的模块不另占名字,它的函数加进那张表({@code move.to} 就是这样进 {@code move} 的)。没用到的模块不读;一个模块读不通、
 * 跑出错、没返回表,出错的是用到它的那一行。给了模块来源,读一个既不是全局、也不是模块的名字就是错(写错的模块名当场说有哪些)。
 * 行号只记脚本自己那一段:模块里的函数调宿主函数时,{@link #currentLine} 说的是脚本里调这个模块函数的那一行,结局停在的也是脚本里的
 * 那一行。
 *
 * <p>名字空间({@link Builder#namespace}):一个定死的全局表,下面一个名字一个模块——模块名 {@code my.lumber} 在脚本里就是
 * {@code my.lumber},同样第一次用到才装,写错了同样说有哪些。
 *
 * <h2>宿主登记的名字钉死</h2>
 * 宿主登记的全局函数、函数表,以及表里的每个宿主函数,脚本都换不掉、遮不住:{@code move = {}}、{@code function move.go() end}、
 * {@code rawset(move, "go", f)} 一律在那一行报错(那句话由 {@link Builder#redefined} 给)。往宿主的表里加别的名字照常(库就是这样往
 * {@code move} 里加 {@code move.to} 的)。
 *
 * <h2>桥接</h2>
 * 宿主函数按 {@code 表名.函数名}(或全局名)登记,收按顺序的参数、交回一个值;值在两边按 {@link #toJava}/{@link #toLua} 换:nil 是
 * null,布尔、数(整数是 Long,其余是 Double)、字符串,表是列表(键恰好 1..n)或名字到值的表。宿主函数抛 {@link ScriptError}
 * 就是脚本在调用处得到的一个 Lua 错误({@code pcall} 接得住)。宿主交来的 {@link Folded} 是一张收着几个字段的表:那几个字段挂在元表的
 * {@code __index} 上,脚本照常读得到,{@code pairs} 与印出来(回到 {@link #toJava})都不带它们。
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

    /** 基本库里沙箱不装的:装载代码、碰文件与 JVM 的那几个;print 换成交给宿主的那一个。 */
    private static final List<String> LEFT_OUT = List.of("load", "dofile", "loadfile", "collectgarbage", "print");

    /**
     * string 库表,全 JVM 一份、锁住;字符串的元表 {@code {__index = string}} 也一份、锁住({@link #STRING_META})。虚拟机里字符串的元表
     * 是 JVM 级的静态对象,谁先装 string 库谁的表进去,一个沙箱改了它(改 {@code string.rep}、改 {@code getmetatable("").__index}),
     * 别的沙箱跟着变;库里的函数都不带状态,所以共用一份只读的就够。脚本要自己的字符串工具就写成局部函数。
     */
    private static final ReadOnlyTable STRING = new ReadOnlyTable("the string library");
    private static final ReadOnlyTable STRING_META = new ReadOnlyTable("the string metatable");

    static {
        LuaValue plain = new StringLib().call(LuaValue.valueOf("string"), new LuaTable());
        for (Varargs kv = plain.next(LuaValue.NIL); !kv.arg1().isnil(); kv = plain.next(kv.arg1())) {
            STRING.rawset(kv.arg1(), kv.arg(2));
        }
        STRING.lock();
        STRING_META.rawset(LuaValue.INDEX, STRING);
        LuaString.s_metatable = STRING_META.lock();
    }

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
    /** 模块从哪来;没给是 null(读不认得的全局名照 Lua 的老样子是 nil)。 */
    private final ModuleSource modules;
    /** 开跑之前先装的模块,按顺序。 */
    private final List<String> preload;
    /** 读一个既不是全局也不是模块的名字时报的那句话。 */
    private final Unknown unknown;
    /** 名字空间:定死的全局表名,下面一个名字一个模块。 */
    private final List<String> namespaces;
    /** 脚本读宿主函数表里没有的名字时报的那句话。 */
    private final Missing missing;
    /** 脚本给宿主登记的名字赋值时报的那句话。 */
    private final Redefined redefined;
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

    /** 模块从哪来。两个方法都可能在脚本的线程上被调。 */
    public interface ModuleSource {

        /** 叫这个名字的模块的正文;没有是 null。用到时才问,所以拿到的是此刻的那一份。 */
        String code(String name);

        /** 有哪些模块,按名字排(写错名字时列给脚本看)。 */
        List<String> names();
    }

    /** 脚本读一个既不是全局、也不是模块的名字:报什么错。 */
    @FunctionalInterface
    public interface Unknown {

        /**
         * @param name    读的名字
         * @param modules 有哪些模块
         * @return 停在那一行的错误:一句话,或一张错误值的表
         */
        ScriptError error(String name, List<String> modules);
    }

    /** 脚本给宿主登记的名字赋值({@code function move.go() end}、{@code move = {}}):报什么错。 */
    @FunctionalInterface
    public interface Redefined {

        /**
         * @param table 表名;改的是一个全局名时是 null
         * @param key   改的名字
         * @return 停在那一行的错误:一句话,或一张错误值的表
         */
        ScriptError error(String table, String key);
    }

    private LuaSandbox(Builder b) {
        this.limits = b.limits;
        this.print = b.print;
        this.globals = Map.copyOf(b.globals);
        this.modules = b.modules;
        this.preload = List.copyOf(b.preload);
        this.unknown = b.unknown;
        this.namespaces = List.copyOf(b.namespaces);
        this.missing = b.missing;
        this.redefined = b.redefined;
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
        private ModuleSource modules;
        private final List<String> preload = new ArrayList<>();
        private Unknown unknown = (name, present) -> new ScriptError("there is no module named " + name);
        private final List<String> namespaces = new ArrayList<>();
        private Missing missing = (table, key, present) -> new ScriptError("there is no function " + table + "." + key);
        private Redefined redefined = (table, key) -> new ScriptError((table == null ? "" : table + ".") + key
                + " is a host function and cannot be replaced");
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

        /** 脚本给宿主登记的名字赋值时报的错:按它给的那句话停在那一行。 */
        public Builder redefined(Redefined redefined) {
            this.redefined = redefined;
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

        /** 模块从这里来:脚本第一次用到一个名字时问它(见类注释"模块")。 */
        public Builder modules(ModuleSource modules) {
            this.modules = modules;
            return this;
        }

        /** 开跑之前先装这个模块(不等用到):装不成,这段脚本就以那个错结束。 */
        public Builder preload(String module) {
            this.preload.add(module);
            return this;
        }

        /**
         * 一个名字空间:全局表 {@code name} 定死,模块名 {@code name.x} 的模块在脚本里就是 {@code name.x},第一次用到才装。
         */
        public Builder namespace(String name) {
            checkName(name);
            if (globals.containsKey(name) || tables.containsKey(name) || namespaces.contains(name)) {
                throw new IllegalArgumentException("名字空间 " + name + " 和已有的全局名撞了");
            }
            namespaces.add(name);
            return this;
        }

        /** 读一个既不是全局也不是模块的名字时报的错:按它给的那句话停在那一行。 */
        public Builder unknown(Unknown unknown) {
            this.unknown = unknown;
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

        /** 到此刻为止装上了的模块,按装上的先后。 */
        List<String> modules();
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
        /** 脚本自己那一段的块名,{@link Prototype#source} 的写法:模块里的指令据此不记行号。 */
        private LuaValue mainSource;
        /** 装上了的模块,按先后。 */
        private final List<String> loaded = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** 正在装的模块:装的时候又用到自己是一个环。 */
        private final java.util.Set<String> loading = new java.util.HashSet<>();
        private Globals g;

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
            try {
                g = standardGlobals();
                install(g);
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
                for (String module : preload) {
                    loadModule(LuaValue.valueOf(module));
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
            FixedKeysTable.Refusal global = key -> luaError(redefined.error(null, key.tojstring()));
            globals.forEach((name, fn) -> {
                g.rawset(name, host(fn));
                g.fix(LuaValue.valueOf(name), global);
            });
            tables.forEach((group, fns) -> {
                FixedKeysTable t = new FixedKeysTable();
                FixedKeysTable.Refusal member = key -> luaError(redefined.error(group, key.tojstring()));
                fns.forEach((fnName, fn) -> {
                    t.rawset(fnName, host(fn));
                    t.fix(LuaValue.valueOf(fnName), member);
                });
                // 读表里没有的名字:同名的模块还没装就先装上再找;还是没有就当场报那句话,不让它成 nil 再在调用处报
                // "调了一个 nil"
                LuaTable meta = new LuaTable();
                LuaValue groupName = LuaValue.valueOf(group);
                meta.rawset("__index", new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs in) {
                        if (modules != null && !loaded.contains(group) && modules.code(group) != null) {
                            loadModule(groupName);
                            LuaValue found = t.rawget(in.arg(2));
                            if (!found.isnil()) {
                                return found;
                            }
                        }
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
                g.fix(LuaValue.valueOf(group), global);
            });
            for (String space : namespaces) {
                // 名字空间里读一个名字:是模块就装上它;否则写错了,当场说有哪些模块
                FixedKeysTable ns = new FixedKeysTable();
                LuaTable meta = new LuaTable();
                meta.rawset("__index", new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs in) {
                        String module = space + "." + in.arg(2).tojstring();
                        if (modules != null && modules.code(module) != null) {
                            loadModule(LuaValue.valueOf(module));
                            return ns.rawget(in.arg(2));
                        }
                        throw luaError(unknown.error(module, modules == null ? List.of() : modules.names()));
                    }
                });
                ns.setmetatable(meta);
                g.rawset(space, ns);
                g.fix(LuaValue.valueOf(space), global);
            }
            if (modules != null) {
                // 读一个不在全局里的名字:是模块就装上它;否则写错了,当场说有哪些模块
                LuaTable meta = new LuaTable();
                meta.rawset("__index", new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs in) {
                        LuaValue name = in.arg(2);
                        if (name.isstring() && modules.code(name.tojstring()) != null) {
                            loadModule(name);
                            return g.rawget(name);
                        }
                        throw luaError(unknown.error(name.tojstring(), modules.names()));
                    }
                });
                g.setmetatable(meta);
            }
        }

        /**
         * 装上一个模块:向来源要此刻的正文,在这个全局环境里跑一遍,它返回的表就是这个模块。和宿主函数表同名的,表里的函数加进那张表
         * (宿主的名字定死,撞了就是 {@link Builder#redefined} 那个错);否则这张表占下模块名这个全局。读不通、跑出错、没返回表都在用到它
         * 的那一行报错,没装上的下次用到再装。
         */
        private void loadModule(LuaValue name) {
            String module = name.tojstring();
            if (!loading.add(module)) {
                throw new LuaError("module " + module + " uses itself while it is loading");
            }
            try {
                String code = modules.code(module);
                if (code == null) {
                    throw luaError(unknown.error(module, modules.names()));
                }
                LuaValue chunk;
                try {
                    chunk = g.load(code, "=" + module);
                } catch (LuaError unreadable) {
                    throw new LuaError("module " + module + " does not compile: " + unreadable.getMessage());
                }
                LuaValue returned = chunk.call();
                if (!returned.istable()) {
                    throw new LuaError("module " + module + " returned " + returned.typename() + ", not a table of its "
                            + "functions; end it with: return M");
                }
                int dot = module.indexOf('.');
                LuaValue group = tables.containsKey(module) ? g.rawget(name) : LuaValue.NIL;
                if (dot > 0) {
                    g.rawget(module.substring(0, dot)).rawset(module.substring(dot + 1), returned);
                } else if (group.istable()) {
                    for (Varargs kv = returned.next(LuaValue.NIL); !kv.arg1().isnil(); kv = returned.next(kv.arg1())) {
                        group.rawset(kv.arg1(), kv.arg(2));
                    }
                } else {
                    g.rawset(name, returned);
                }
                loaded.add(module);
            } finally {
                loading.remove(module);
            }
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

        @Override
        public List<String> modules() {
            return List.copyOf(loaded);
        }
    }

    // ---- 环境与值 ----

    /**
     * 沙箱的标准环境:基本函数去掉 {@link #LEFT_OUT}、只读的 string({@link #STRING})、table、math;编译器只收文本。字符串的元表
     * 在类初始化时已换成只读的那一份,虚拟机的 string 库再装也不会动它(它只在还没有元表时放进去)。
     */
    private static Globals standardGlobals() {
        Globals g = new Globals();
        g.load(new BaseLib());
        g.load(new TableLib());
        g.load(new StringLib());
        g.load(new JseMathLib());
        LuaC.install(g);
        LEFT_OUT.forEach(name -> g.rawset(name, LuaValue.NIL));
        g.rawset("string", STRING);
        g.finder = null;
        g.STDIN = null;
        g.STDOUT = null;
        g.STDERR = null;
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

    /**
     * 一张收着几个字段的表:{@code shown} 是表自己的字段,{@code folded} 里的字段读得到({@code t.path}),不出现在 {@code pairs}
     * 与印出来的样子里——大而少用的字段(一条路的每一步)不把回执撑满。
     */
    public record Folded(Map<String, Object> shown, Map<String, Object> folded) {}

    /** Java 值换成 Lua 值:null、布尔、数、字符串、列表、名字到值的表、收着几个字段的表({@link Folded})。 */
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
        if (o instanceof Folded folded) {
            LuaTable t = (LuaTable) toLua(folded.shown());
            LuaTable meta = new LuaTable();
            meta.rawset(LuaValue.INDEX, toLua(folded.folded()));
            t.setmetatable(meta);
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
