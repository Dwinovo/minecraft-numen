package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.Internal;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.TaskResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.ImmutableStringReader;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Numen 的命令组:登记处、主人客户端那一侧的小表,以及两侧共用的路由与报错。设计稿见 {@code docs/cli.md}。
 *
 * <h2>两棵树,一份声明</h2>
 * 命令组的声明是进程级的静态表,由各模组的公共初始化代码登记——客户端进程与服务端进程各跑一遍同一份登记。
 * 由它长出两棵树({@link CommandTree}):
 * <ul>
 *   <li><b>主人客户端的小表</b>:本类自己的 Brigadier 调度器,根是 {@code numen},只有客户端动作可执行,帮助各层都在。
 *       它不注册成 MC 的客户端指令:MC 客户端指令的来源是主人自己,说不出"替哪一只同伴",还会出现在主人的聊天补全里。</li>
 *   <li><b>MC 的指令树</b>:服务端动作真实注册在 {@code /numen} 下({@link #herNodes},由 {@code NumenCommands}
 *       在加载器的指令注册事件里挂上,只给她看见)。</li>
 * </ul>
 *
 * <h2>路由只有一条规则</h2>
 * 模型的一行先在主人客户端这张小表上解析:解析到客户端动作或帮助,就在客户端当场执行(写错了当场回,附用法);
 * 否则原样送服务端,由那边唯一的执行入口({@link CommandRunner})以她的身份执行。客户端不认识原版和模组的指令,
 * 也不需要认识。
 */
public final class NumenCli {

    /** 根命令:Numen 自己的命令一律以它开头。 */
    public static final String ROOT = "numen";
    static final String HELP_FLAG = "--help";
    static final String HELP = "help";

    /** 按名字排序:根帮助与系统提示索引的顺序不随插件的加载先后变,字节稳定。 */
    private static final Map<String, CommandGroup> GROUPS = new TreeMap<>();

    /** 主人客户端的那一侧:源对象就是 {@link ClientSource},执行客户端动作。 */
    private static final CommandTree<ClientSource> CLIENT = new CommandTree<>() {
        @Override
        void handle(ClientSource source, Body body) throws CommandSyntaxException {
            body.run(source);
        }

        @Override
        boolean runs(Action action) {
            return !action.runsOnServer();
        }
    };

    /**
     * MC 指令树那一侧:源对象是她的 {@code CommandSourceStack},这次调用在它的回话去处 {@link Echo} 里。
     * 处理函数正常返回,就算这次调用由 Numen 答了,执行入口不再拿回显作回执。
     */
    private static final CommandTree<CommandSourceStack> HER = new CommandTree<>() {
        @Override
        void handle(CommandSourceStack source, Body body) throws CommandSyntaxException {
            Echo echo = Echo.of(source);
            body.run(echo.call());
            echo.answered();
        }

        @Override
        boolean runs(Action action) {
            return action.runsOnServer();
        }
    };

    private static final CommandDispatcher<ClientSource> DISPATCHER = new CommandDispatcher<>();
    private static final LiteralCommandNode<ClientSource> ROOT_NODE = register(DISPATCHER);
    /** 各组到齐、相关命令查过了没有;查过之后登记的组在登记那一刻就查(见 {@link #inUse()})。 */
    private static boolean inUse;

    private NumenCli() {}

    private static LiteralCommandNode<ClientSource> register(CommandDispatcher<ClientSource> dispatcher) {
        LiteralArgumentBuilder<ClientSource> root = LiteralArgumentBuilder.literal(ROOT);
        CLIENT.rootHelp(NumenCli::rootListing).forEach(root::then);
        return dispatcher.register(root);
    }

    /**
     * 登记一个命令组。{@code NumenApi.registerCommands} 背后就是它;插件经那扇门来,不直接调。
     *
     * <p>组名谁先登记归谁,撞了当场抛出——插件只在自己的组里加动作,碰不到别人的(见 {@link CommandGroup})。
     * 登记块跑完后:查过每个动作的例子(见 {@link CommandGroup#close}),组挂上主人客户端的小表(MC 指令树在服务器建
     * 指令树时从这里长,见 {@link #herNodes}),提升过的动作按登记顺序进工具表(工具名撞了由 {@link ToolRegistry}
     * 当场抛出)。各组已经到齐、查过相关命令之后才来的组,它的相关命令也在这时查(见 {@link #inUse()})。
     */
    @Internal
    public static synchronized void register(String name, String summary, Consumer<CommandGroup> actions) {
        if (name == null || !Action.NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("命令组名不合规(小写字母开头,只含 [a-z0-9_]): '" + name + "'");
        }
        if (HELP.equals(name) || GROUPS.containsKey(name)) {
            throw new IllegalArgumentException("命令组 " + ROOT + " " + name
                    + " 已经有主了——每个插件只在自己的组里加动作,不往别人的组下挂");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("命令组 " + name + " 没写一句话说明");
        }
        CommandGroup group = new CommandGroup(name, summary);
        actions.accept(group);
        group.close();
        if (group.actions().isEmpty()) {
            throw new IllegalArgumentException("命令组 " + name + " 一个动作都没有");
        }
        if (inUse) {
            Map<String, CommandGroup> known = new TreeMap<>(GROUPS);
            known.put(name, group);
            checkSeeAlso(List.of(group), known);
        }
        GROUPS.put(name, group);
        ROOT_NODE.addChild(CLIENT.group(group).build());
        for (Action a : group.actions()) {
            if (a.toolName() != null) {
                ToolRegistry.register(new PromotedTool(a));
            }
        }
    }

    /**
     * 她在 MC 指令树 {@code /numen} 下的节点:根上的帮助与每一个命令组,由已登记的声明现长出来。服务器每次建指令树
     * (开服、{@code /reload})都来取一次;谁能用这些节点由挂的人定({@code NumenCommands} 只给她)。这时各模组的组都
     * 已登记完,相关命令在这里一次查全({@link #inUse()}):断掉的引用在服务器启动时就报出来。
     */
    @Internal
    public static List<LiteralArgumentBuilder<CommandSourceStack>> herNodes() {
        inUse();
        return nodes(HER);
    }

    /** 一侧挂在 {@code numen} 下的节点:根上的帮助,与每一个命令组。 */
    static <S> List<LiteralArgumentBuilder<S>> nodes(CommandTree<S> side) {
        List<LiteralArgumentBuilder<S>> nodes = new ArrayList<>(side.rootHelp(NumenCli::rootListing));
        for (CommandGroup group : GROUPS.values()) {
            nodes.add(side.group(group));
        }
        return nodes;
    }

    /**
     * Numen 自己的参数类型登记进 MC 的指令参数类型注册表,两侧都要:服务器给每个玩家(她也是玩家)发指令树时,
     * 构造那个包就要把树上每种参数类型按类查出来序列化,查不到直接报错——她的假连接丢包也救不了,包在交给连接之前
     * 就造不出来。玩家收到的树里没有只给她的节点,这些类型永远不会发到任何一个客户端。
     */
    @Internal
    public static void registerArgumentTypes() {
        Services.PLATFORM.registerArgumentType("flags", FlagsArgument.class, new HerArgumentInfo<>());
        Services.PLATFORM.registerArgumentType("id", ArgType.IdArgument.class, new HerArgumentInfo<>());
        Services.PLATFORM.registerArgumentType("string", ArgType.ValueArgument.class, new HerArgumentInfo<>());
    }

    /** {@link #registerArgumentTypes} 登记的类:单元测试拿它核对树上的每种参数类型都有着落。 */
    static final List<Class<?>> OWN_ARGUMENT_TYPES =
            List.of(FlagsArgument.class, ArgType.IdArgument.class, ArgType.ValueArgument.class);

    /**
     * 系统提示里的一行索引:已登记的各组一句。只在组增减时变,按名字排好,字节稳定,不打碎 prompt 缓存。
     * 一个组都没有时是空串。
     */
    public static String index() {
        inUse();
        if (GROUPS.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<commands>\nNumen's command groups, run with the ")
                .append(CommandTool.NAME).append(" tool (").append(ROOT).append(" <group> ").append(HELP_FLAG)
                .append(" lists a group's actions):");
        for (CommandGroup g : GROUPS.values()) {
            sb.append('\n').append(CommandHelp.groupLine(g));
        }
        return sb.append("\n</commands>").toString();
    }

    /** 模型写的一行去掉首尾空白与前导 {@code /}:两侧读的是同一行。 */
    static String bare(String typed) {
        String line = typed.strip();
        return line.startsWith("/") ? line.substring(1).strip() : line;
    }

    /**
     * 主人客户端这一侧跑一行:解析到客户端动作或帮助就当场执行,写错了当场回并附用法;否则原样送服务端。
     * 结果经 {@code source} 恰好回一次。
     */
    static void run(String line, ClientSource source) {
        inUse();
        ParseResults<ClientSource> parse = DISPATCHER.parse(line, source);
        if (!answeredOnClient(parse)) {
            source.forwardToServer();
            return;
        }
        String problem = problem(parse, line);
        if (problem != null) {
            source.reply(TaskResult.fail(problem).toJson());
            return;
        }
        try {
            DISPATCHER.execute(parse);
        } catch (CommandSyntaxException e) {
            source.reply(TaskResult.fail(e.getMessage() + "\n" + helpAt(parse)).toJson());
        }
    }

    /**
     * 各组到齐的那一刻把相关命令查一遍:服务器建指令树时({@link #herNodes}),或者主人客户端这张小表第一次被读时
     * (执行一行、系统提示要索引;连着别人的服务器时客户端不建指令树)。
     *
     * <p>为什么是这个时机:相关命令可以指向别的组,而组谁先登记由加载器排模组的顺序决定——在引用方登记那一刻查,
     * 被指的组可能还没来,结论就随加载顺序变。各模组都在加载期登记,指令树与这张小表却要等世界起来才第一次被用,
     * 那时加载期的组都已到齐,一次查全不会漏。查不过就抛出,而且不记作已查:下一次还会再查、再抛,不会带着
     * 断掉的引用接着用。在这之后才登记的组(测试夹具这类)在它自己登记那一刻查,它能指向的组那时都已经在了。
     */
    private static synchronized void inUse() {
        if (!inUse) {
            checkSeeAlso(GROUPS.values(), GROUPS);
            inUse = true;
        }
    }

    /** {@code groups} 里每条相关命令都要在 {@code known} 里找到它指的动作;找不到的一次列全,抛出。 */
    static void checkSeeAlso(Collection<CommandGroup> groups, Map<String, CommandGroup> known) {
        List<String> broken = new ArrayList<>();
        for (CommandGroup group : groups) {
            for (Action action : group.actions()) {
                for (String path : action.seeAlso()) {
                    if (resolve(path, known) == null) {
                        broken.add(action.path() + " -> " + path);
                    }
                }
            }
        }
        if (!broken.isEmpty()) {
            throw new IllegalStateException("相关命令指向不存在的动作: " + String.join("; ", broken));
        }
    }

    /** 一条整路径指的动作:{@code numen <组> <动作>};没有是 null。 */
    private static Action resolve(String path, Map<String, CommandGroup> groups) {
        String[] words = path.split(" ");
        if (words.length != 3 || !ROOT.equals(words[0])) {
            return null;
        }
        CommandGroup group = groups.get(words[1]);
        return group == null ? null : group.action(words[2]);
    }

    /**
     * 这一行归不归主人客户端答:沿着解析到的字面节点看最后一格——是帮助({@code numen help}、各层的 {@code --help}),
     * 或者是一个客户端动作,就在客户端;停在根上、组上、服务端动作上,或者根本不以 {@code numen} 开头,都送服务端。
     */
    private static boolean answeredOnClient(ParseResults<ClientSource> parse) {
        List<String> path = literalPath(parse);
        if (path.isEmpty()) {
            return false;
        }
        String last = path.get(path.size() - 1);
        if (last.equals(HELP_FLAG) || (path.size() == 2 && last.equals(HELP))) {
            return true;
        }
        if (path.size() < 3) {
            return false;
        }
        Action action = GROUPS.get(path.get(1)).action(path.get(2));
        return action != null && !action.runsOnServer();
    }

    /** 这一行是不是 Numen 自己的命令:解析到的第一格是 {@code numen} 这个根。 */
    static boolean owns(ParseResults<?> parse) {
        List<String> path = literalPath(parse);
        return !path.isEmpty() && path.get(0).equals(ROOT);
    }

    /**
     * 一行 Numen 命令写不写得通:写不通是 Brigadier 的报错(原话与出错位置)、出错那一层的帮助,再接上"你是不是要写"
     * ({@link Completions#didYouMean},和原版与模组的指令同一个函数);写得通是 null。两侧同一种说法——主人客户端的
     * 小表与 MC 指令树都从同一份声明长出来,同一行在两边的报错一字不差。
     */
    static <S> String problem(ParseResults<S> parse, String line) {
        try {
            validate(parse, line);
            return null;
        } catch (CommandSyntaxException e) {
            return e.getMessage() + "\n" + helpAt(parse) + Completions.didYouMean(parse);
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
     * 出错那一层的帮助:沿着已解析的字面节点走——根、组、动作,走到哪层算哪层。参数节点不算一层,
     * 所以卡在某个参数上时给的是那个动作的帮助。
     */
    private static String helpAt(ParseResults<?> parse) {
        List<String> path = literalPath(parse);
        CommandGroup group = path.size() > 1 ? GROUPS.get(path.get(1)) : null;
        if (group == null) {
            return rootListing().first();
        }
        Action action = path.size() > 2 ? group.action(path.get(2)) : null;
        return action == null ? CommandHelp.group(group).first() : CommandHelp.action(action);
    }

    /** 解析走过的字面节点的名字,从根往下。 */
    static List<String> literalPath(ParseResults<?> parse) {
        return parse.getContext().getNodes().stream()
                .map(n -> (CommandNode<?>) n.getNode())
                .filter(n -> n instanceof LiteralCommandNode)
                .map(CommandNode::getName)
                .toList();
    }

    private static Listing rootListing() {
        return CommandHelp.root(GROUPS.values());
    }
}
