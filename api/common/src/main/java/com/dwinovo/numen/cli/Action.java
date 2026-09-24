package com.dwinovo.numen.cli;

import com.dwinovo.numen.task.TaskResult;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

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

    private final CommandGroup group;
    private final String name;
    private final String summary;
    private final List<Param<?>> params;
    private final OnServer onServer;
    private final OnClient onClient;
    private String toolName;
    private String toolDescription;

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
                    onServer.run(server, args);
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

    /**
     * 这一格在 Brigadier 树上的样子:{@code --help};必填参数依次一格一格往下接,最后一格可执行;有可选参数的话,
     * 可执行的那一格下面再挂一格标志尾巴,同样可执行。
     */
    LiteralArgumentBuilder<CommandSource> node() {
        LiteralArgumentBuilder<CommandSource> node = LiteralArgumentBuilder.literal(name);
        node.then(LiteralArgumentBuilder.<CommandSource>literal(NumenCli.HELP_FLAG).executes(ctx -> {
            ctx.getSource().reply(TaskResult.ok(CommandHelp.action(this)).toJson());
            return Command.SINGLE_SUCCESS;
        }));
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

    /** {@code numen <组> <动作>}。 */
    String path() {
        return NumenCli.ROOT + " " + group.name() + " " + name;
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
}
