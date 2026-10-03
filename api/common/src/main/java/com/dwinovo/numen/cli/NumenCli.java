package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.script.Invocation;
import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.script.ScriptRun;
import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.agent.tool.ServerToolTransport;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.api.Internal;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.script.BuiltinModules;
import com.dwinovo.numen.script.Modules;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.ImmutableStringReader;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 她的 API:登记处、执行入口,以及共用的帮助与报错。设计稿见 {@code docs/shell.md}、{@code docs/cli.md}。
 *
 * <h2>一份登记,两个前端</h2>
 * 动作组的声明是进程级的静态表,由各模组的公共初始化代码登记——客户端进程与服务端进程各跑一遍同一份登记。她只经一个前端用它:
 * 脚本(跑脚本的那个工具)里的每个 API 函数 {@code 组.动作(...)} 就是一个动作。人(OP 的 {@code /numen drive}、重启后的重放、
 * 设计文件里的一步)经另一个前端写一行命令 {@code 组 动作 对象... --选项 值},在服务端那棵 Brigadier 树上读。两个前端读的是同一张
 * 参数表、同一种参数类型({@link ArgType}),帮助与报错也只有一份({@link CommandHelp}),写成脚本里的样子。
 *
 * <h2>脚本里的一次调用怎么走</h2>
 * <ol>
 *   <li>{@link #invocation}:按顺序的对象依次给位置参数,选项表按名字给标志,读成参数名到值的 JSON;当场按参数类型读一遍,读不成
 *       在调用处报错(error/usage/hint),不派出去。</li>
 *   <li>{@link #call}(主人客户端或评测的大脑):客户端动作当场执行;服务端动作把这次调用原样送去服务端。</li>
 *   <li>{@link #serve(String, JsonObject, NumenPlayer, String, Consumer)}(服务端):按同一张参数表、同一种参数类型把 JSON 读成值,
 *       交给动作的处理函数。</li>
 * </ol>
 */
public final class NumenCli {

    static final String HELP_FLAG = "--help";
    static final String HELP = "help";

    /** 按名字排序:帮助与系统提示索引的顺序不随插件的加载先后变,字节稳定。 */
    private static final Map<String, CommandGroup> GROUPS = new TreeMap<>();

    /** 服务端的那一棵:人写的一行命令在这里读、执行服务端动作。 */
    private static final CommandTree<ServerSource> SERVER =
            new CommandTree<ServerSource>(Action::runsOnServer).withRootHelp(NumenCli::rootListing);
    /**
     * 只读不执行的那一棵:两侧的动作都长着参数,{@link #read} 用它把一行读成动作与参数。和服务端那一棵同一个生成器,
     * 所以一行在这里读得通,在服务端也读得通。
     */
    private static final CommandTree<CommandSource> READ =
            new CommandTree<CommandSource>(action -> true).withRootHelp(NumenCli::rootListing);
    /** 各组到齐、相关动作与库查过了没有;查过之后登记的组在登记那一刻就查(见 {@link #inUse()})。 */
    private static boolean inUse;
    /** 声明了的类,按名字:{@link Shapes} 的几种,加各组声明的({@link CommandGroup#declare})。 */
    private static final Map<String, ScriptType.Class> CLASSES = new TreeMap<>();

    static {
        Shapes.CLASSES.forEach(c -> CLASSES.put(c.name(), c));
    }

    /** 声明一个类;名字不合规矩或已经有了当场抛出。 */
    static synchronized void declare(ScriptType.Class type) {
        if (!type.name().matches("[A-Z][A-Za-z0-9]*")) {
            throw new IllegalArgumentException("类名不合规(大写字母开头,只含字母数字): '" + type.name() + "'");
        }
        if (CLASSES.putIfAbsent(type.name(), type) != null) {
            throw new IllegalArgumentException("类 " + type.name() + " 已经有人声明过了");
        }
    }

    /** 这个名字的类;没有是 null。 */
    static ScriptType.Class classNamed(String name) {
        return CLASSES.get(name);
    }

    private NumenCli() {}

    /**
     * 登记一个动作组。{@code NumenApi.registerCommands} 背后就是它;插件经那扇门来,不直接调。
     *
     * <p>组名谁先登记归谁,撞了当场抛出——插件只在自己的组里加动作,碰不到别人的(见 {@link CommandGroup})。
     * 登记块跑完后:查过每个动作的例子(见 {@link CommandGroup#close}),组挂上树。各组已经到齐、查过相关动作之后才来的组,它的
     * 相关动作也在这时查(见 {@link #inUse()})。
     */
    @Internal
    public static synchronized void register(String name, String summary, Consumer<CommandGroup> actions) {
        if (name == null || !Action.NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("动作组名不合规(小写字母开头,只含 [a-z0-9_]): '" + name + "'");
        }
        if (HELP.equals(name) || GROUPS.containsKey(name)) {
            throw new IllegalArgumentException("动作组 " + name
                    + " 已经有主了——每个插件只在自己的组里加动作,不往别人的组下挂");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("动作组 " + name + " 没写一句话说明");
        }
        CommandGroup group = new CommandGroup(name, summary);
        actions.accept(group);
        group.close();
        if (group.actions().isEmpty()) {
            throw new IllegalArgumentException("动作组 " + name + " 一个动作都没有");
        }
        if (inUse) {
            Map<String, CommandGroup> known = new TreeMap<>(GROUPS);
            known.put(name, group);
            checkSeeAlso(List.of(group), known);
        }
        GROUPS.put(name, group);
        SERVER.add(group);
        READ.add(group);
    }

    /**
     * 系统提示里的 API 索引:每个组一行说明与函数名(和组同名的模块的函数也列在那一组下),再是模块:内置的在前、她的在后,一个一行。
     * 由登记处与模块现算,只在组与模块增减、改了说明时变,按名字排好,字节稳定,不打碎 prompt 缓存。一个组都没有时是空串。
     *
     * @param modules 她能用的模块(主人那一份)
     */
    public static String index(Modules modules) {
        inUse();
        if (GROUPS.isEmpty()) {
            return "";
        }
        return "<api>\n" + CommandHelp.index(GROUPS.values(), libraryFunctions(modules), modules) + "\n</api>";
    }

    /**
     * 把一行命令按命令树读一遍,不执行:写成它的样子的文字(设计文件里的一步)和执行时同一个解析器、同一个判据。读得通有两种:
     * 整行是一条能执行的命令,或整行只是一串名字。停在参数中间、多写了东西、写错了都读不通。
     *
     * @throws IllegalArgumentException 读不通;消息和执行时写错一样(Brigadier 的原话、那个动作的用法、你是不是要写)
     */
    public static Reading read(String line) {
        inUse();
        ParseResults<CommandSource> parse = READ.parse(line, null);
        List<String> path = literalPath(parse);
        if (parse.getContext().getCommand() == null) {
            boolean named = !parse.getReader().canRead() && parse.getExceptions().isEmpty() && !path.isEmpty()
                    && parse.getContext().getNodes().size() == path.size();
            if (!named) {
                // 没走到可执行的一格,Brigadier 执行前那道检查必然不过,problem 说的就是它
                throw new IllegalArgumentException(problem(parse, line));
            }
            return new Reading(String.join(" ", path), false, null);
        }
        String problem = problem(parse, line);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        Action action = path.size() == 2 ? GROUPS.get(path.get(0)).action(path.get(1)) : null;
        CommandArgs args = null;
        if (action != null) {
            CommandContext<?> ctx = parse.getContext().build(line);
            args = CommandArgs.fromCommand(action.positionals(), ctx, FlagsArgument.valuesIn(ctx));
        }
        return new Reading(String.join(" ", path), true, args);
    }

    /**
     * 一行命令读成什么,不执行。
     *
     * @param path     走过的字面节点,空格隔开:{@code build layer}、{@code build --help}、{@code help}、{@code build}
     * @param runnable 走到了可执行的一格(一个动作或一个帮助);否则这一行只点到一组或一个动作的名字
     * @param args     走到一个动作时读好的参数,和执行时处理函数拿到的是同一份;帮助与只提到名字的是 null
     */
    public record Reading(String path, boolean runnable, CommandArgs args) {}

    /**
     * 脚本里能调的:每个登记了的动作一个 {@code 组.动作},带上它声明的返回项({@link Action#returns}),加上模块。由登记表现算,
     * 不另记一份。
     *
     * @param modules 模块从哪来:她的那一份({@link Modules#of}),或只读随模组发布的文字时只有内置那一层({@link Modules#builtin})
     */
    public static ScriptCatalog scriptCatalog(Modules modules) {
        inUse();
        return catalog(modules);
    }

    private static ScriptCatalog catalog(Modules modules) {
        Map<String, Map<String, ScriptCatalog.Verb>> groups = new TreeMap<>();
        for (CommandGroup group : GROUPS.values()) {
            Map<String, ScriptCatalog.Verb> verbs = new TreeMap<>();
            for (Action action : group.actions()) {
                verbs.put(action.name(), action.verb());
            }
            groups.put(group.name(), verbs);
        }
        return new ScriptCatalog(groups, modules);
    }

    /**
     * 脚本里的一次调用读成一个动作和它的参数——脚本这个前端只有这一处换法。按顺序的对象依次给这个动作的位置参数,最后一个位置参数
     * 收下余下的全部对象(一串区域、余下整行);选项表的键是参数名({@code _} 与 {@code -} 同一)。脚本的值原样换成 JSON(表是对象、
     * 列表是数组),当场按参数类型读一遍({@link CommandArgs#fromJson},执行的那一侧读的也是它),读不成就在这里报。
     *
     * @throws ApiError 没有这个动作({@code no_function});对象多了、缺了必填的、选项名不对或值读不成({@code bad_argument}):错在哪
     *                  与用法,下一步是改好的那一行(看得出想写什么时)或怎么看全部帮助
     */
    public static Invocation invocation(ScriptRun.Call call) {
        inUse();
        CommandGroup group = GROUPS.get(call.group());
        Action action = group == null ? null : group.action(call.verb());
        if (action == null) {
            throw new ApiError(ErrorKind.NO_FUNCTION, "there is no API function " + call.function(),
                    "the <api> index lists every group; api.help(\"" + call.group() + "\") lists one.");
        }
        JsonObject json;
        try {
            json = jsonOf(action, call.args(), call.options());
            CommandArgs.fromJson(action.params(), json);
        } catch (IllegalArgumentException wrong) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, wrong.getMessage() + "\nusage: " + CommandHelp.usage(action),
                    corrected(action, call, wrong));
        }
        return new Invocation(group.name(), action.name(), action.function(), json);
    }

    /**
     * 参数错了的下一步:看得出她想写什么(三个数想写一格)而那是一个对象或一个选项时,是改好的那一整行调用(一串值的参数只收了这一个
     * 写错的对象时也是);是一串里的一项时,说那一项写成什么;都不是就是怎么看这个函数的全部帮助。
     */
    private static String corrected(Action action, ScriptRun.Call call, IllegalArgumentException wrong) {
        if (!(wrong instanceof CommandArgs.BadArgument bad) || bad.instead == null || bad.param == null) {
            return helpHint(action);
        }
        Param<?> p = bad.param;
        List<Param<?>> positionals = action.positionals();
        int index = positionals.indexOf(p);
        boolean alone = index >= 0 && index == call.args().size() - 1
                && !(call.args().get(index) instanceof List<?> list && !numbersOnly(list));
        boolean single = p.type().span() != ArgType.Span.SEVERAL || alone;
        if (single && index >= 0 && index < call.args().size()) {
            List<Object> objects = new ArrayList<>(call.args());
            objects.set(index, bad.instead);
            return ScriptEngine.IN_USE.call(action.function(), objects, call.options());
        }
        if (single && index < 0) {
            Map<String, Object> options = new LinkedHashMap<>();
            call.options().forEach((k, v) -> options.put(k, Param.nameOf(k).equals(p.name()) ? bad.instead : v));
            return ScriptEngine.IN_USE.call(action.function(), call.args(), options);
        }
        return "write each of " + p.name() + " like " + ScriptEngine.IN_USE.value(bad.instead) + ". "
                + helpHint(action);
    }

    /**
     * 脚本里的对象与选项写成参数名到值的 JSON——脚本这个前端的换法只在这里。值原样换:名字到值的表是 JSON 对象(一个 Pos、一只实体),
     * 列表是数组,nil 是 null(读参数时报是哪一个)。一串值的参数收下的对象:只有一个而它是一张列表,那张列表就是这一串
     * ({@code work.dig({b1, b2})});否则收下的那几个就是这一串({@code work.dig(b1, b2)})。
     */
    static JsonObject jsonOf(Action action, List<Object> objects, Map<String, Object> options) {
        List<Param<?>> positionals = action.positionals();
        if (positionals.isEmpty() && !objects.isEmpty()) {
            throw new IllegalArgumentException("takes no objects, got " + objects.size());
        }
        JsonObject json = new JsonObject();
        for (int i = 0; i < positionals.size() && i < objects.size(); i++) {
            Param<?> p = positionals.get(i);
            boolean last = i == positionals.size() - 1;
            List<Object> given = last ? objects.subList(i, objects.size()) : objects.subList(i, i + 1);
            if (p.type().span() == ArgType.Span.SEVERAL) {
                json.add(p.name(), several(given));
            } else if (given.size() > 1 && p.type().span() == ArgType.Span.REST) {
                json.add(p.name(), new JsonPrimitive(String.join(" ", given.stream().map(String::valueOf).toList())));
            } else if (given.size() > 1) {
                throw new IllegalArgumentException("takes " + positionals.size() + " object(s), got " + objects.size()
                        + "; a position is one table {x = …, y = …, z = …}");
            } else {
                json.add(p.name(), one(given.get(0)));
            }
        }
        for (Map.Entry<String, Object> option : options.entrySet()) {
            Param<?> p = action.params().stream().filter(q -> q.name().equals(Param.nameOf(option.getKey())))
                    .findFirst().orElse(null);
            if (p != null && p.positional()) {
                throw new IllegalArgumentException("'" + option.getKey() + "' is an object, not an option: give it in "
                        + "order before the options table");
            }
            json.add(option.getKey(), p != null && p.type().span() == ArgType.Span.SEVERAL
                    ? several(java.util.Collections.singletonList(option.getValue()))
                    : one(option.getValue()));
        }
        return json;
    }

    /** 一串值:只给了一张列表,就是它;否则给的那几个。 */
    private static JsonElement several(List<?> given) {
        JsonArray array = new JsonArray();
        if (given.size() == 1 && given.get(0) instanceof List<?> list) {
            list.forEach(v -> array.add(one(v)));
        } else {
            given.forEach(v -> array.add(one(v)));
        }
        return array;
    }

    /** 一到三个数的列表:读参数时可能是写成旧样子的一处坐标({@link ArgType#list} 认它),改写时整个换掉。 */
    private static boolean numbersOnly(List<?> list) {
        return !list.isEmpty() && list.size() <= 3 && list.stream().allMatch(v -> v instanceof Number);
    }

    /** 一个脚本的值写成 JSON,原样:表是对象、列表是数组、nil 是 null。 */
    private static JsonElement one(Object value) {
        return switch (value) {
            case null -> JsonNull.INSTANCE;
            case String s -> new JsonPrimitive(s);
            case Number n -> new JsonPrimitive(n);
            case Boolean b -> new JsonPrimitive(b);
            case List<?> list -> {
                JsonArray array = new JsonArray();
                list.forEach(v -> array.add(one(v)));
                yield array;
            }
            case Map<?, ?> map -> {
                JsonObject object = new JsonObject();
                map.forEach((k, v) -> object.add(String.valueOf(k), one(v)));
                yield object;
            }
            default -> throw new IllegalArgumentException("cannot pass " + value + " here");
        };
    }

    /**
     * 主人客户端(或评测的大脑)执行脚本里的一次调用:客户端动作当场读参数、当场执行;服务端动作把这次调用原样送去服务端
     * ({@link ServerToolTransport},它的名字是动作的路径、参数是读好的 JSON),由 {@link #serve(String, JsonObject, NumenPlayer,
     * String, Consumer)} 执行。结果经 {@code call} 恰好回一次。
     */
    public static void call(Invocation invocation, ToolCall call) {
        Action action = action(invocation.group(), invocation.verb());
        if (action.runsOnServer()) {
            ServerToolTransport.ship(call);
            return;
        }
        execute(action, ClientSource.of(call), invocation.args());
    }

    /** 脚本里的这次调用是不是服务端动作。 */
    public static boolean runsOnServer(Invocation invocation) {
        return action(invocation.group(), invocation.verb()).runsOnServer();
    }

    /** 脚本里的一次调用在主人客户端的那一侧送去服务端时用的名字:动作的路径。 */
    public static String pathOf(Invocation invocation) {
        return invocation.group() + " " + invocation.verb();
    }

    /**
     * 服务端执行脚本里的一次调用:{@code path} 是动作的路径({@link #pathOf}),{@code args} 是参数名到值的 JSON。按同一张参数表、
     * 同一种参数类型读成值,交给处理函数;读不成、没有这个动作都是一条失败回执。结果经 {@code reply} 恰好回一次。
     */
    public static void serve(String path, JsonObject args, NumenPlayer her, String callId, Consumer<String> reply) {
        inUse();
        String[] words = path.split(" ", 2);
        CommandGroup group = words.length == 2 ? GROUPS.get(words[0]) : null;
        Action action = group == null ? null : group.action(words[1]);
        if (action == null || !action.runsOnServer()) {
            reply.accept(TaskResult.fail(ErrorKind.NO_FUNCTION, "there is no API function " + path.replace(' ', '.')
                    + " on the server", null).toJson());
            return;
        }
        execute(action, new ServerSource(her, callId, reply), args);
    }

    /**
     * 读参数、执行。参数读不成,或处理函数说这些参数在此刻不成立({@link IllegalArgumentException}),都是一条三段的失败回执:
     * 错在哪、这个动作的用法、怎么看它的全部帮助。
     */
    private static void execute(Action action, CommandSource source, JsonObject json) {
        CommandArgs args;
        try {
            args = CommandArgs.fromJson(action.params(), json);
        } catch (IllegalArgumentException wrong) {
            source.reply(badArgument(action, wrong).toJson());
            return;
        }
        run(action, source, args);
    }

    /**
     * 读好的参数交给处理函数。处理函数当场说不成立的:抛 {@link ApiError} 的是它说的那一种(点名的区域不在是
     * {@code not_found}……);别的 {@link IllegalArgumentException} 是参数在此刻不成立,一次 {@code bad_argument}。
     */
    static void run(Action action, CommandSource source, CommandArgs args) {
        try {
            action.execute(source, args);
        } catch (ApiError failed) {
            source.reply(TaskResult.fail(failed.kind(), failed.getMessage(), failed.hint()).toJson());
        } catch (IllegalArgumentException wrong) {
            source.reply(badArgument(action, wrong).toJson());
        }
    }

    /** 参数不成立的失败:错在哪接这个动作的用法,下一步是怎么看它的全部帮助。 */
    private static TaskResult badArgument(Action action, IllegalArgumentException wrong) {
        return TaskResult.fail(ErrorKind.BAD_ARGUMENT, wrong.getMessage() + "\nusage: " + CommandHelp.usage(action),
                helpHint(action));
    }

    /** 一个动作的全部帮助怎么要,一行能照抄的程序:{@code print(api.help("work.dig"))}。 */
    static String helpHint(Action action) {
        return "print(" + HelpCommands.call(action.function()) + ")";
    }

    /** 这个动作;没有就抛出(脚本里的调用读成动作时已经认过它)。 */
    private static Action action(String group, String verb) {
        CommandGroup g = GROUPS.get(group);
        Action action = g == null ? null : g.action(verb);
        if (action == null) {
            throw new IllegalStateException("there is no action " + group + " " + verb);
        }
        return action;
    }

    /** 登记了的各组,按名字排序。 */
    static Collection<CommandGroup> groups() {
        inUse();
        return GROUPS.values();
    }

    /**
     * 名字({@code move}、{@code move.go}、{@code move.goto_}、{@code lumber}、{@code lumber.chop})指的组、模块或函数的帮助;不认得是
     * null。和组同名的模块的函数在那一组的帮助里。
     */
    static String help(String name, Modules modules) {
        inUse();
        Map<String, LibraryFunction> library = libraryFunctions(modules);
        for (CommandGroup group : GROUPS.values()) {
            if (ScriptEngine.IN_USE.functionName(group.name()).equals(name)) {
                return CommandHelp.group(group, library);
            }
            for (Action action : group.actions()) {
                if (action.function().equals(name)) {
                    return CommandHelp.action(action);
                }
            }
        }
        Modules.Module module = modules.get(name);
        if (module != null) {
            return CommandHelp.module(module, library);
        }
        LibraryFunction fn = library.get(name);
        return fn == null ? null : CommandHelp.library(fn);
    }

    /** 模块里定义的一个函数:定义它的模块,与它的定义。 */
    record LibraryFunction(String module, ScriptEngine.Defined defined) {}

    /** 模块里定义的函数:{@code 模块.函数} → 它,按模块名、模块里出现的顺序。 */
    static Map<String, LibraryFunction> libraryFunctions(Modules modules) {
        Map<String, LibraryFunction> out = new LinkedHashMap<>();
        modules.all().forEach((name, module) -> {
            for (ScriptEngine.Defined fn : ScriptEngine.IN_USE.functions(name, module.code())) {
                out.put(fn.name(), new LibraryFunction(name, fn));
            }
        });
        return out;
    }

    /** 这个词是不是一行命令的一级命令:一个组的名字,或根下的 {@code help}、{@code --help}。 */
    public static boolean isTopLevel(String word) {
        return HELP.equals(word) || HELP_FLAG.equals(word) || GROUPS.containsKey(word);
    }

    /** 服务端这一侧跑人写的一行命令:在服务端的树上解析、执行;写错了附用法。结果经 {@code call} 恰好回一次。 */
    static void serve(String line, ServerSource call) {
        inUse();
        ParseResults<ServerSource> parse = SERVER.parse(line, call);
        String problem = problem(parse, line);
        if (problem != null) {
            call.reply(TaskResult.fail(ErrorKind.BAD_ARGUMENT, problem, null).toJson());
            return;
        }
        try {
            SERVER.execute(parse);
        } catch (CommandSyntaxException e) {
            call.reply(TaskResult.fail(ErrorKind.BAD_ARGUMENT, Problem.of(e.getMessage(), usageAt(parse), hintAt(parse)),
                    null).toJson());
        }
    }

    /**
     * 各组到齐的那一刻把相关动作与库查一遍:第一次有人用登记处时(执行一次调用、系统提示要索引)。
     *
     * <p>为什么是这个时机:相关动作可以指向别的组,库可以调任何组的函数,而组谁先登记由加载器排模组的顺序决定——在引用方登记那一刻
     * 查,被指的组可能还没来,结论就随加载顺序变。各模组都在加载期登记,登记处却要等世界起来才第一次被用,那时加载期的组都已到齐,
     * 一次查全不会漏。查不过就抛出,而且不记作已查:下一次还会再查、再抛,不会带着断掉的引用接着用。在这之后才登记的组(测试夹具这类)
     * 在它自己登记那一刻查,它能指向的组那时都已经在了。
     */
    private static synchronized void inUse() {
        if (!inUse) {
            checkSeeAlso(GROUPS.values(), GROUPS);
            checkModules();
            checkClasses();
            inUse = true;
        }
    }

    /** 每个动作的参数与返回类型里按名字引用的类都得有人声明;找不到的一次列全,抛出。 */
    private static void checkClasses() {
        List<String> broken = new ArrayList<>();
        for (CommandGroup group : GROUPS.values()) {
            for (Action action : group.actions()) {
                for (String name : CommandHelp.named(action.doc())) {
                    if (!CLASSES.containsKey(name)) {
                        broken.add(action.path() + " -> " + name);
                    }
                }
            }
        }
        for (ScriptType.Class c : CLASSES.values()) {
            for (ScriptType.Field f : c.fields()) {
                for (String name : CommandHelp.named(f.type())) {
                    if (!CLASSES.containsKey(name)) {
                        broken.add("class " + c.name() + "." + f.name() + " -> " + name);
                    }
                }
            }
        }
        if (!broken.isEmpty()) {
            throw new IllegalStateException("引用了没人声明的类: " + String.join("; ", broken));
        }
    }

    /** 相关动作每条都要在 {@code known} 里找到它指的动作;找不到的一次列全,抛出。 */
    static void checkSeeAlso(Collection<CommandGroup> groups, Map<String, CommandGroup> known) {
        List<String> broken = new ArrayList<>();
        for (CommandGroup group : groups) {
            for (Action action : group.actions()) {
                for (String path : action.seeAlso()) {
                    if (resolve(path, known) == null && !definedInLibrary(path)) {
                        broken.add(action.path() + " -> " + path);
                    }
                }
            }
        }
        if (!broken.isEmpty()) {
            throw new IllegalStateException("相关动作指向不存在的函数: " + String.join("; ", broken));
        }
    }

    /** 内置模块里有没有定义 {@code 模块 函数} 这个函数(相关动作可以指向模块函数,写法同动作的路径:{@code "move goto_"})。 */
    private static boolean definedInLibrary(String path) {
        return libraryFunctions(Modules.builtin()).containsKey(path.replace(' ', '.'));
    }

    /**
     * 每个内置模块照运行时的装法装一次({@link ScriptEngine#checkModule}):返回一张表、不给第 ① 层的名字赋值(和组同名的模块不能
     * 换掉那一组的动作)。各组到齐才查得全,所以在这里查;不过的一次列全,抛出。
     */
    private static void checkModules() {
        ScriptCatalog catalog = catalog(Modules.builtin());
        List<String> broken = new ArrayList<>();
        BuiltinModules.all().forEach((name, module) -> {
            String problem = ScriptEngine.IN_USE.checkModule(name, module.code(), catalog);
            if (problem != null) {
                broken.add(name + ": " + problem);
            }
        });
        if (!broken.isEmpty()) {
            throw new IllegalStateException("内置模块装不上: " + String.join("; ", broken));
        }
    }

    /** 一条整路径指的动作:{@code <组> <动作>};没有是 null。 */
    private static Action resolve(String path, Map<String, CommandGroup> groups) {
        String[] words = path.split(" ");
        if (words.length != 2) {
            return null;
        }
        CommandGroup group = groups.get(words[0]);
        return group == null ? null : group.action(words[1]);
    }

    /**
     * 一行命令写不写得通:写得通是 null;写不通是三段——{@code error:} Brigadier 的原话与出错位置,{@code usage:} 出错那一层的
     * 正确写法({@link #usageAt}),{@code hint:} 能照抄的下一步:"你是不是要写"({@link Completions#didYouMean},和原版与模组的
     * 指令同一个函数),没有就是那一层的帮助。
     */
    static <S> String problem(ParseResults<S> parse, String line) {
        try {
            validate(parse, line);
            return null;
        } catch (CommandSyntaxException e) {
            String nearest = Completions.didYouMean(parse);
            return Problem.of(e.getMessage(), usageAt(parse), nearest.isEmpty() ? hintAt(parse) : nearest);
        }
    }

    /**
     * 和 Brigadier 执行前的那道检查同一个顺序(有没读完的字 → 报哪一个错;读完了 → 有没有走到可执行的一格),只多一条:
     * 余下的词在这一层连一个候选都对不上(组名、动作名写错,或多写了东西)时,Brigadier 报的是"参数不对",
     * 对只有字面子节点的那一层说"没有这个命令"才是实话。
     */
    private static void validate(ParseResults<?> parse, String line) throws CommandSyntaxException {
        ImmutableStringReader reader = parse.getReader();
        if (reader.canRead()) {
            if (parse.getExceptions().size() == 1) {
                throw parse.getExceptions().values().iterator().next();
            }
            CommandSyntaxException positional = positionalProblem(parse);
            if (positional != null) {
                throw positional;
            }
            if (parse.getExceptions().isEmpty() || parse.getContext().getRange().isEmpty()) {
                throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().createWithContext(reader);
            }
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().createWithContext(reader);
        }
        if (ContextChain.tryFlatten(parse.getContext().build(line)).isEmpty()) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().createWithContext(reader);
        }
    }

    /**
     * 可以不写的位置参数后面也能直接接标志尾巴,于是那一格有两条路都读不通:位置参数的与标志尾巴的。写下的不以 {@code --} 打头时
     * 写的是位置参数,报它的那一句;否则是 null,照常判。
     */
    private static CommandSyntaxException positionalProblem(ParseResults<?> parse) {
        ImmutableStringReader reader = parse.getReader();
        if (reader.getString().startsWith(FlagsArgument.PREFIX, reader.getCursor())) {
            return null;
        }
        CommandSyntaxException found = null;
        for (Map.Entry<? extends CommandNode<?>, CommandSyntaxException> e : parse.getExceptions().entrySet()) {
            if (e.getKey().getName().equals(FlagsArgument.NODE)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = e.getValue();
        }
        return found;
    }

    /**
     * 出错那一层的正确写法:沿着已解析的字面节点走——根、组、动作,走到哪层算哪层。卡在某个参数上时给的是那个动作的用法与例子;
     * 停在组或根上时给那一层的清单。
     */
    private static String usageAt(ParseResults<?> parse) {
        List<String> path = literalPath(parse);
        CommandGroup group = path.isEmpty() ? null : GROUPS.get(path.get(0));
        if (group == null) {
            return rootListing().first();
        }
        Action action = path.size() > 1 ? group.action(path.get(1)) : null;
        return action == null ? CommandHelp.group(group, libraryFunctions(Modules.builtin()))
                : CommandHelp.usage(action);
    }

    /** 出错那一层的帮助怎么要。 */
    private static String hintAt(ParseResults<?> parse) {
        List<String> path = literalPath(parse);
        CommandGroup group = path.isEmpty() ? null : GROUPS.get(path.get(0));
        if (group == null) {
            return "`" + HELP + "` lists the groups.";
        }
        Action action = path.size() > 1 ? group.action(path.get(1)) : null;
        return action == null ? "print(" + HelpCommands.call(group.name()) + ")" : helpHint(action);
    }

    /** 解析走过的字面节点的名字,从一级命令往下。 */
    static List<String> literalPath(ParseResults<?> parse) {
        return parse.getContext().getNodes().stream()
                .map(n -> (CommandNode<?>) n.getNode())
                .filter(n -> n instanceof LiteralCommandNode)
                .map(CommandNode::getName)
                .toList();
    }

    private static Listing rootListing() {
        Modules builtin = Modules.builtin();
        return CommandHelp.listing(CommandHelp.index(GROUPS.values(), libraryFunctions(builtin), builtin));
    }
}
