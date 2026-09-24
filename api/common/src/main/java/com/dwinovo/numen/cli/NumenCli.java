package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.Internal;
import com.dwinovo.numen.task.TaskResult;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Numen 自己的命令调度器:一棵 Brigadier 树,根是 {@code numen},下面一级是命令组,再下面是动作。
 * 它不挂在服务器的 {@code /} 指令上,玩家碰不到;只有模型经 {@code numen} 工具和快捷工具进来。
 *
 * <h2>两侧同一棵树</h2>
 * 树是进程级的静态表,由各模组的公共初始化代码登记——客户端进程与服务端进程各自跑同一份登记,长出同一棵树。
 * 单人游戏里两侧在同一个进程,共用这一棵。所以客户端能当场给帮助、当场报解析错误;执行侧见 {@link Action}。
 *
 * <h2>一条命令怎么跑</h2>
 * 解析 → 找到那一格 → 交给它唯一的处理函数({@link Action#execute})。解析不过时,回执是 Brigadier 的报错
 * 加上出错那一层的帮助,模型写错一次就能照着改对。{@code help} 与各层的 {@code --help} 是树上的普通节点,
 * 同一次解析认出来,不另有一套识别。
 */
public final class NumenCli {

    /** 根命令,也是 {@code numen} 工具的名字:一行命令以它开头。 */
    public static final String ROOT = "numen";
    static final String HELP_FLAG = "--help";
    private static final String HELP = "help";

    /** 按名字排序:根帮助与系统提示索引的顺序不随插件的加载先后变,字节稳定。 */
    private static final Map<String, CommandGroup> GROUPS = new TreeMap<>();
    private static final CommandDispatcher<CommandSource> DISPATCHER = new CommandDispatcher<>();
    private static final LiteralCommandNode<CommandSource> ROOT_NODE = DISPATCHER.register(
            LiteralArgumentBuilder.<CommandSource>literal(ROOT)
                    .then(helpNode(HELP, NumenCli::rootListing))
                    .then(helpNode(HELP_FLAG, NumenCli::rootListing)));

    private NumenCli() {}

    /**
     * 登记一个命令组。{@code NumenApi.registerCommands} 背后就是它;插件经那扇门来,不直接调。
     *
     * <p>组名谁先登记归谁,撞了当场抛出——插件只在自己的组里加动作,碰不到别人的(见 {@link CommandGroup})。
     * 登记块跑完后:组挂上树,提升过的动作按登记顺序进工具表(工具名撞了由 {@link ToolRegistry} 当场抛出)。
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
        GROUPS.put(name, group);
        ROOT_NODE.addChild(group.node().build());
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
        if (GROUPS.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<commands>\nCommand groups of the ")
                .append(ROOT).append(" tool (").append(ROOT).append(" <group> ").append(HELP_FLAG)
                .append(" lists a group's actions):");
        for (CommandGroup g : GROUPS.values()) {
            sb.append('\n').append(CommandHelp.groupLine(g));
        }
        return sb.append("\n</commands>").toString();
    }

    /** 跑一行命令:解析、执行,或回一条附着用法的失败。结果经 {@code source} 恰好回一次。 */
    static void run(String line, CommandSource source) {
        ParseResults<CommandSource> parse = DISPATCHER.parse(line.strip(), source);
        try {
            if (parse.getReader().canRead() && parse.getExceptions().isEmpty()) {
                // 余下的词在这一层连一个候选都对不上(组名、动作名写错,或多写了东西):Brigadier 此时报的是
                // "参数不对",对只有字面子节点的那一层说"没有这个命令"才是实话。
                throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand()
                        .createWithContext(parse.getReader());
            }
            DISPATCHER.execute(parse);
        } catch (CommandSyntaxException e) {
            source.reply(TaskResult.fail(e.getMessage() + "\n" + helpAt(parse)).toJson());
        }
    }

    /**
     * 出错那一层的帮助:沿着已解析的字面节点走——根、组、动作,走到哪层算哪层。参数节点不算一层,
     * 所以卡在某个参数上时给的是那个动作的帮助。
     */
    private static String helpAt(ParseResults<CommandSource> parse) {
        List<String> path = parse.getContext().getNodes().stream()
                .map(n -> n.getNode())
                .filter(n -> n instanceof LiteralCommandNode)
                .map(CommandNode::getName)
                .toList();
        CommandGroup group = path.size() > 1 ? GROUPS.get(path.get(1)) : null;
        if (group == null) {
            return rootListing().first();
        }
        if (group.direct() != null) {
            return CommandHelp.action(group.direct());
        }
        Action action = path.size() > 2 ? group.action(path.get(2)) : null;
        return action == null ? CommandHelp.group(group).first() : CommandHelp.action(action);
    }

    private static Listing rootListing() {
        return CommandHelp.root(GROUPS.values());
    }

    /** 一个显示列表的帮助节点:不带标志是第一页,{@code --page N} 翻页。在哪一侧解析就在哪一侧回。 */
    static LiteralArgumentBuilder<CommandSource> helpNode(String literal, Supplier<Listing> listing) {
        return pagedHelp(literal, (source, args) -> source.reply(TaskResult.ok(listing.get().page(args)).toJson()));
    }

    /**
     * 列表只有服务端算得出的帮助节点(按这具身体此刻的样子列):客户端解析到它时把这次调用原样送去服务端,
     * 服务端算出列表再翻页。
     */
    static LiteralArgumentBuilder<CommandSource> serverHelpNode(String literal,
                                                               Function<ServerSource, Listing> listing) {
        return pagedHelp(literal, (source, args) -> {
            switch (source) {
                case ClientSource client -> client.forwardToServer();
                case ServerSource server -> server.reply(TaskResult.ok(listing.apply(server).page(args)).toJson());
            }
        });
    }

    /** 回 {@code --page} 要的那一页帮助;页码不存在时抛出,和别的解析错误一样附着用法回去。 */
    @FunctionalInterface
    private interface PageShown {
        void show(CommandSource source, CommandArgs args) throws CommandSyntaxException;
    }

    private static LiteralArgumentBuilder<CommandSource> pagedHelp(String literal, PageShown help) {
        Command<CommandSource> show = ctx -> {
            help.show(ctx.getSource(), CommandArgs.fromCommand(List.of(), ctx, FlagsArgument.valuesIn(ctx)));
            return Command.SINGLE_SUCCESS;
        };
        return LiteralArgumentBuilder.<CommandSource>literal(literal)
                .executes(show)
                .then(RequiredArgumentBuilder.<CommandSource, Map<String, Object>>argument(
                        FlagsArgument.NODE, new FlagsArgument(List.of(Listing.PAGE))).executes(show));
    }
}
