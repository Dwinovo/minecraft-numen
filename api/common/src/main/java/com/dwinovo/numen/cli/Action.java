package com.dwinovo.numen.cli;

import com.dwinovo.numen.task.TaskResult;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 一个动作:{@code numen <组> <动作> …} 的那一格。它持有这件事<b>唯一的处理函数</b>、参数表与说明,
 * 命令行、帮助、快捷工具都从这里取。
 *
 * <p>执行侧由登记时给的处理函数决定:{@link CommandGroup#server} 给的是服务端函数,{@link CommandGroup#client}
 * 给的是客户端函数,二者只有一个。两侧都注册这同一格(公共代码在每个进程里各跑一遍),所以客户端能当场给帮助、
 * 当场报解析错误;真正执行时,源对象在哪一侧、函数属于哪一侧,{@link #execute} 一处决定——客户端遇到服务端动作
 * 就把调用送过去,服务端遇到客户端动作如实拒绝。专用服务器上客户端动作的函数照样登记着(帮助要用它的说明),
 * 只是永远不会在那里被调用。
 *
 * <p>一组也可以直接就是一个动作({@link CommandGroup#serverDirect}):它没有动作名,参数紧跟在组名后面
 * ({@code numen mc <command...>})。
 *
 * <h2>帮助正文也登记在这里</h2>
 * 动作的帮助除了用法、说明、参数,还有三块,都接在登记处返回的这个动作上写:
 * <pre>{@code
 * quests.server("submit", "Hand in a quest's items from your own inventory.", QuestSubmit::submit, QUEST_ID)
 *       .example("numen ftbquests submit 15CDF6A098B95FDA")
 *       .note("Takes the items from YOUR inventory; FTB decides what counts.")
 *       .seeAlso("numen ftbquests list", "numen ftbquests show");
 * }</pre>
 * <ul>
 *   <li>{@link #example}:一整行真实可用的命令,可以多个。模型照着例子写,比读语法可靠。</li>
 *   <li>{@link #note}:可选,多条。写会不会问主人、是不是长活、会动她的什么、不会做什么。</li>
 *   <li>{@link #seeAlso}:可选。做完这件事下一步通常用的动作,同组别组都行,写整条路径。引用在命令树第一次被读时
 *       查(那时各模组的组都已登记完),理由见 {@link NumenCli}。</li>
 * </ul>
 */
public final class Action implements Command<CommandSource> {

    /** 动作名与组名同形:小写字母开头,小写字母、数字、下划线。 */
    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,31}");

    /** 服务端动作:拿到活体与回信口,当场回结果,或把长活交给 {@code TaskDispatch}。 */
    @FunctionalInterface
    public interface OnServer {
        void run(ServerSource source, CommandArgs args);
    }

    /** 主人客户端动作:当场执行,经 {@link ClientSource#reply} 回结果。 */
    @FunctionalInterface
    public interface OnClient {
        void run(ClientSource source, CommandArgs args);
    }

    /**
     * 帮助里一张只有服务端答得出的目录:按这一刻、这具身体算出来的条目,一行一条(比如服务器按她的权限等级
     * 让她用的原版指令)。
     */
    @FunctionalInterface
    public interface Catalog {
        List<String> lines(ServerSource source);
    }

    private final CommandGroup group;
    /** 动作名;组直接就是这个动作时为 null。 */
    private final String name;
    private final String summary;
    private final List<Param<?>> params;
    private final OnServer onServer;
    private final OnClient onClient;
    private final List<String> examples = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final List<String> seeAlso = new ArrayList<>();
    private String toolName;
    private String toolDescription;
    private String catalogTitle;
    private Catalog catalog;

    Action(CommandGroup group, String name, String summary, List<Param<?>> params,
           OnServer onServer, OnClient onClient) {
        this.group = group;
        this.name = name;
        this.summary = summary;
        this.params = List.copyOf(params);
        this.onServer = onServer;
        this.onClient = onClient;
    }

    /**
     * 提升为快捷工具:模型的工具表里多一个 {@code toolName},描述是 {@code description},参数 schema 由这个
     * 动作的参数表生成。调用它就是执行这个动作——同一个处理函数,同一份回执。
     */
    public Action promote(String toolName, String description) {
        group.requireOpen();
        if (this.toolName != null) {
            throw new IllegalStateException(path() + " 已经提升为 " + this.toolName + ",一个动作只提升一次");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException(path() + " 提升为 " + toolName + " 却没写工具描述");
        }
        this.toolName = toolName;
        this.toolDescription = description;
        return this;
    }

    /** 一个例子:一整行真实可用的命令,帮助里原样列出。可以调多次,按调用顺序列。 */
    public Action example(String line) {
        examples.add(requireText(line, "例子"));
        return this;
    }

    /** 一条注意:会不会问主人、是不是长活、会动她的什么、不会做什么。可以调多次,按调用顺序列。 */
    public Action note(String text) {
        notes.add(requireText(text, "注意"));
        return this;
    }

    /** 相关命令:下一步通常用的动作,写整条路径,如 {@code numen ftbquests list}。可以调多次。 */
    public Action seeAlso(String... paths) {
        for (String path : paths) {
            seeAlso.add(requireText(path, "相关命令"));
        }
        return this;
    }

    private String requireText(String text, String what) {
        group.requireOpen();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(path() + " 的" + what + "是空的");
        }
        return text;
    }

    /**
     * 帮助末尾再列一张目录,标题是 {@code title}。目录只有服务端答得出,所以这个动作的 {@code --help} 在客户端
     * 解析到时把调用送去服务端,在那边算出来,和组的列表一样分页、认 {@code --page}。
     */
    public Action catalog(String title, Catalog lines) {
        group.requireOpen();
        if (this.catalog != null) {
            throw new IllegalStateException(path() + " 已经有一张目录了");
        }
        if (title == null || title.isBlank() || lines == null) {
            throw new IllegalArgumentException(path() + " 的目录要有标题和条目");
        }
        this.catalogTitle = title;
        this.catalog = lines;
        return this;
    }

    /** 命令行这一入口:Brigadier 解析通过后调到这里。 */
    @Override
    public int run(CommandContext<CommandSource> ctx) {
        execute(ctx.getSource(), CommandArgs.fromCommand(positionals(), ctx, FlagsArgument.valuesIn(ctx)));
        return Command.SINGLE_SUCCESS;
    }

    /** 两个入口的汇合处:读好的参数交给这一侧的处理函数,或送去该执行的那一侧。 */
    void execute(CommandSource source, CommandArgs args) {
        switch (source) {
            case ServerSource server -> {
                if (onServer != null) {
                    onServer.run(server.running(this), args);
                } else {
                    server.reply(TaskResult.fail(path() + " runs on the owner's client, not on the server.").toJson());
                }
            }
            case ClientSource client -> {
                if (onClient != null) {
                    onClient.run(client, args);
                } else {
                    client.forwardToServer();
                }
            }
        }
    }

    /** 具名动作在 Brigadier 树上的那一格:动作名,下面是 {@link #fill} 挂的东西。 */
    LiteralArgumentBuilder<CommandSource> node() {
        return fill(LiteralArgumentBuilder.<CommandSource>literal(name));
    }

    /**
     * 往 {@code node} 下面挂这个动作:{@code --help};必填参数依次一格一格往下接,最后一格可执行;有可选参数的话,
     * 可执行的那一格下面再挂一格标志尾巴,同样可执行。具名动作挂在自己那一格下,组直接就是它时挂在组那一格下。
     */
    <B extends ArgumentBuilder<CommandSource, B>> B fill(B node) {
        node.then(catalog == null
                ? LiteralArgumentBuilder.<CommandSource>literal(NumenCli.HELP_FLAG).executes(ctx -> {
                    ctx.getSource().reply(TaskResult.ok(CommandHelp.action(this)).toJson());
                    return Command.SINGLE_SUCCESS;
                })
                : NumenCli.serverHelpNode(NumenCli.HELP_FLAG, source -> CommandHelp.catalog(this, source)));
        List<Param<?>> required = positionals();
        if (required.isEmpty()) {
            executable(node);
            return node;
        }
        ArgumentBuilder<CommandSource, ?> tip = executable(argument(required.get(required.size() - 1)));
        for (int i = required.size() - 2; i >= 0; i--) {
            tip = argument(required.get(i)).then(tip);
        }
        node.then(tip);
        return node;
    }

    private <B extends ArgumentBuilder<CommandSource, B>> B executable(B builder) {
        builder.executes(this);
        List<Param<?>> optional = params.stream().filter(p -> !p.required()).toList();
        if (!optional.isEmpty()) {
            builder.then(RequiredArgumentBuilder.<CommandSource, Map<String, Object>>argument(
                    FlagsArgument.NODE, new FlagsArgument(optional)).executes(this));
        }
        return builder;
    }

    private static <T> RequiredArgumentBuilder<CommandSource, T> argument(Param<T> param) {
        return RequiredArgumentBuilder.argument(param.name(), param.type().brigadier());
    }

    List<Param<?>> positionals() {
        return params.stream().filter(Param::required).toList();
    }

    List<Param<?>> params() {
        return params;
    }

    String name() {
        return name;
    }

    String summary() {
        return summary;
    }

    List<String> examples() {
        return examples;
    }

    List<String> notes() {
        return notes;
    }

    /** 相关命令的整条路径,按登记顺序。 */
    List<String> seeAlso() {
        return seeAlso;
    }

    /** {@code numen <组> <动作>};组直接就是这个动作时是 {@code numen <组>}。 */
    String path() {
        return NumenCli.ROOT + " " + label();
    }

    /** {@code <组> <动作>},组直接就是这个动作时只是 {@code <组>}:从命令派下的活就叫这个名字。 */
    String label() {
        return group.name() + (name == null ? "" : " " + name);
    }

    /** 整行用法:路径 + 必填参数 + 标志。 */
    String usage() {
        StringBuilder sb = new StringBuilder(path());
        for (Param<?> p : params) {
            if (p.required()) sb.append(' ').append(p.usage());
        }
        for (Param<?> p : params) {
            if (!p.required()) sb.append(' ').append(p.usage());
        }
        return sb.toString();
    }

    /** 服务端执行?(否则在主人客户端执行。) */
    boolean runsOnServer() {
        return onServer != null;
    }

    /** 提升成的工具名;没提升是 {@code null}。 */
    String toolName() {
        return toolName;
    }

    String toolDescription() {
        return toolDescription;
    }

    /** 目录的标题;没有目录是 {@code null}。 */
    String catalogTitle() {
        return catalogTitle;
    }

    /** 这具身体此刻的目录条目。只在服务端调。 */
    List<String> catalogLines(ServerSource source) {
        return catalog.lines(source);
    }
}
