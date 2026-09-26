package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.Internal;
import com.dwinovo.numen.task.TaskResult;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.ImmutableStringReader;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 第 1 层:Numen 给她的命令层。登记处、两侧各一棵 Numen 自己的调度器,以及两侧共用的帮助与报错。设计稿见
 * {@code docs/cli.md}。
 *
 * <h2>一份声明,两棵树</h2>
 * 命令组的声明是进程级的静态表,由各模组的公共初始化代码登记——客户端进程与服务端进程各跑一遍同一份登记。
 * 由它长出两棵树({@link CommandTree}),都不注册进 MC 的指令树:玩家在 MC 里看不到她的任何命令,也就不需要按
 * "是不是她"过滤可见性。
 * <ul>
 *   <li><b>主人客户端</b>:客户端动作可执行,帮助各层都在。</li>
 *   <li><b>服务端</b>:服务端动作可执行,帮助各层都在。</li>
 * </ul>
 *
 * <h2>一行第 1 层命令怎么走</h2>
 * 先在主人客户端这棵树上解析({@link #run}):解析到服务端动作,原样送服务端,由那边唯一的执行入口
 * ({@link CommandRunner})在服务端这棵树上再解析、执行({@link #serve});其余(客户端动作、帮助、写错了)当场答。
 * 行首是 {@code /} 的不归这里,见 {@link Line}。
 */
public final class NumenCli {

    static final String HELP_FLAG = "--help";
    static final String HELP = "help";

    /** 按名字排序:根帮助与系统提示索引的顺序不随插件的加载先后变,字节稳定。 */
    private static final Map<String, CommandGroup> GROUPS = new TreeMap<>();

    /** 主人客户端的那一棵:执行客户端动作。 */
    private static final CommandTree<ClientSource> CLIENT =
            new CommandTree<ClientSource>(action -> !action.runsOnServer()).withRootHelp(NumenCli::rootListing);
    /** 服务端的那一棵:执行服务端动作。 */
    private static final CommandTree<ServerSource> SERVER =
            new CommandTree<ServerSource>(Action::runsOnServer).withRootHelp(NumenCli::rootListing);
    /**
     * 只读不执行的那一棵:两侧的动作都长着参数,{@link #read} 用它把一行读成动作与参数。和两侧的树同一个生成器,
     * 所以一行在这里读得通,在执行它的那一侧也读得通。
     */
    private static final CommandTree<CommandSource> READ =
            new CommandTree<CommandSource>(action -> true).withRootHelp(NumenCli::rootListing);
    /** 各组到齐、相关命令查过了没有;查过之后登记的组在登记那一刻就查(见 {@link #inUse()})。 */
    private static boolean inUse;

    private NumenCli() {}

    /**
     * 登记一个命令组。{@code NumenApi.registerCommands} 背后就是它;插件经那扇门来,不直接调。
     *
     * <p>组名谁先登记归谁,撞了当场抛出——插件只在自己的组里加动作,碰不到别人的(见 {@link CommandGroup})。
     * 登记块跑完后:查过每个动作的例子(见 {@link CommandGroup#close}),组挂上两侧的树,提升过的动作按登记顺序进工具表
     * (工具名撞了由 {@link ToolRegistry} 当场抛出)。各组已经到齐、查过相关命令之后才来的组,它的相关命令也在这时查
     * (见 {@link #inUse()})。
     */
    @Internal
    public static synchronized void register(String name, String summary, Consumer<CommandGroup> actions) {
        if (name == null || !Action.NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("命令组名不合规(小写字母开头,只含 [a-z0-9_]): '" + name + "'");
        }
        if (HELP.equals(name) || GROUPS.containsKey(name)) {
            throw new IllegalArgumentException("命令组 " + name
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
        CLIENT.add(group);
        SERVER.add(group);
        READ.add(group);
        for (Action a : group.actions()) {
            if (a.toolName() != null) {
                ToolRegistry.register(new PromotedTool(a));
            }
        }
    }

    /**
     * 系统提示里的一行索引:已登记的各组一句。只在组增减时变,按名字排好,字节稳定,不打碎 prompt 缓存。
     * 一个组都没有时是空串。
     */
    public static String index() {
        inUse();
        if (GROUPS.isEmpty()) {
            return "";
        }
        // 和系统提示里的技能清单同一个形状("The following … are available for use with …",一行一个"- 名字: 描述")
        StringBuilder sb = new StringBuilder("<commands>\nThe following command groups are available for use with the ")
                .append(CommandTool.NAME).append(" tool:");
        for (CommandGroup g : GROUPS.values()) {
            sb.append("\n- ").append(g.name()).append(": ").append(g.summary());
        }
        return sb.append("\n</commands>").toString();
    }

    /**
     * 主人客户端这一侧跑模型写的一行:行首是 {@code /} 的原样送服务端(第 0 层);第 1 层的一行在这一侧的树上解析,
     * 解析到服务端动作就原样送服务端,其余(客户端动作、帮助、写错了)当场答,写错了附用法。结果经 {@code source}
     * 恰好回一次。
     */
    static void run(String typed, ClientSource source) {
        Line line = Line.of(typed);
        if (line.mc()) {
            source.forwardToServer();
            return;
        }
        inUse();
        ParseResults<ClientSource> parse = CLIENT.parse(line.text(), source);
        if (reachesServerAction(parse)) {
            source.forwardToServer();
            return;
        }
        answer(CLIENT, parse, line.text(), source);
    }

    /**
     * 一行第 1 层命令读成什么,不执行。
     *
     * @param path     走过的字面节点,空格隔开:{@code build layer}、{@code build --help}、{@code help}、{@code build}
     * @param runnable 走到了可执行的一格(一个动作或一个帮助);否则这一行只点到一组或一个动作的名字,是提到它
     * @param args     走到一个动作时读好的参数,和执行时处理函数拿到的是同一份;帮助与只提到名字的是 null
     */
    public record Reading(String path, boolean runnable, CommandArgs args) {}

    /**
     * 把一行第 1 层命令按命令树读一遍,不执行:写成它的样子的文字(设计文件里的一步、技能与提示里写的命令)和执行时
     * 同一个解析器、同一个判据。读得通有两种:整行是一条能执行的命令,或整行只是一串名字({@code use gui}、{@code build},
     * 在文字里提到一个动作或一组)。停在参数中间、多写了东西、写错了都读不通。
     *
     * @throws IllegalArgumentException 读不通;消息和执行时写错一样(Brigadier 的原话、出错那一层的帮助、你是不是要写)
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
            CommandContext<CommandSource> ctx = parse.getContext().build(line);
            args = CommandArgs.fromCommand(action.positionals(), ctx, FlagsArgument.valuesIn(ctx));
        }
        return new Reading(String.join(" ", path), true, args);
    }

    /** 登记了的各组,按名字排序。 */
    static Collection<CommandGroup> groups() {
        inUse();
        return GROUPS.values();
    }

    /** 这个词是不是第 1 层的一级命令:一个命令组的名字,或根下的 {@code help}、{@code --help}。 */
    public static boolean isTopLevel(String word) {
        return HELP.equals(word) || HELP_FLAG.equals(word) || GROUPS.containsKey(word);
    }

    /** 服务端这一侧跑一行第 1 层命令:在服务端的树上解析、执行;写错了附用法。结果经 {@code call} 恰好回一次。 */
    static void serve(String line, ServerSource call) {
        inUse();
        answer(SERVER, SERVER.parse(line, call), line, call);
    }

    private static <S extends CommandSource> void answer(CommandTree<S> tree, ParseResults<S> parse, String line,
                                                         S source) {
        String problem = problem(parse, line);
        if (problem != null) {
            source.reply(TaskResult.fail(problem).toJson());
            return;
        }
        try {
            tree.execute(parse);
        } catch (CommandSyntaxException e) {
            source.reply(TaskResult.fail(e.getMessage() + "\n" + helpAt(parse)).toJson());
        }
    }

    /**
     * 各组到齐的那一刻把相关命令查一遍:任一侧的树第一次被读时(执行一行、系统提示要索引)。
     *
     * <p>为什么是这个时机:相关命令可以指向别的组,而组谁先登记由加载器排模组的顺序决定——在引用方登记那一刻查,
     * 被指的组可能还没来,结论就随加载顺序变。各模组都在加载期登记,树却要等世界起来才第一次被用,那时加载期的组
     * 都已到齐,一次查全不会漏。查不过就抛出,而且不记作已查:下一次还会再查、再抛,不会带着断掉的引用接着用。
     * 在这之后才登记的组(测试夹具这类)在它自己登记那一刻查,它能指向的组那时都已经在了。
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
     * 这一行是不是解析到了一个服务端动作:沿着解析到的字面节点看,走到了某组的一个服务端动作,而且没有停在它的
     * {@code --help} 上。主人客户端的树上服务端动作只有名字与帮助,参数读不读得通、怎么执行都归服务端。
     */
    private static boolean reachesServerAction(ParseResults<ClientSource> parse) {
        List<String> path = literalPath(parse);
        if (path.size() < 2 || path.get(path.size() - 1).equals(HELP_FLAG)) {
            return false;
        }
        Action action = GROUPS.get(path.get(0)).action(path.get(1));
        return action != null && action.runsOnServer();
    }

    /**
     * 一行 Numen 命令写不写得通:写不通是 Brigadier 的报错(原话与出错位置)、出错那一层的帮助,再接上"你是不是要写"
     * ({@link Completions#didYouMean},和原版与模组的指令同一个函数);写得通是 null。两侧同一种说法——两侧的树从同一份
     * 声明长出来,同一行在两边的报错一字不差。
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
        CommandGroup group = path.isEmpty() ? null : GROUPS.get(path.get(0));
        if (group == null) {
            return rootListing().first();
        }
        Action action = path.size() > 1 ? group.action(path.get(1)) : null;
        return action == null ? CommandHelp.group(group).first() : CommandHelp.action(action);
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
        return CommandHelp.root(GROUPS.values());
    }
}
