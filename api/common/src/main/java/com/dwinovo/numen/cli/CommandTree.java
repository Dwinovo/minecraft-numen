package com.dwinovo.numen.cli;

import com.dwinovo.numen.task.TaskResult;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 第 1 层的一棵树:一个 Numen 自己的 Brigadier 调度器,由命令组的声明长出来。主人客户端与服务端各有一棵
 * ({@link NumenCli}),登记例子时再现长一棵只有那一组的({@link CommandGroup#close});三棵是同一个生成器、同一个形状,
 * 只有一处不同:
 *
 * <ul>
 *   <li>根下是 {@code help} 与 {@code --help};组下是 {@code --help} 与每一个动作;每个动作下是 {@code --help}。
 *       帮助只读声明,所以哪一侧都答得出。</li>
 *   <li>动作的参数与标志尾巴、可执行的那一格,只长在<b>执行它的那一侧</b>({@code runs}):主人客户端的树里没有服务端动作的
 *       参数,服务端的树里没有客户端动作的参数。</li>
 * </ul>
 *
 * <p>树上的源对象就是 Numen 自己的来源({@link ClientSource} 或 {@link ServerSource}),处理函数直接拿到它。
 *
 * @param <S> 这一侧的来源
 */
final class CommandTree<S extends CommandSource> {

    private final CommandDispatcher<S> dispatcher = new CommandDispatcher<>();
    private final Predicate<Action> runs;

    /** @param runs 这个动作在这一侧执行吗 */
    CommandTree(Predicate<Action> runs) {
        this.runs = runs;
    }

    /** 根上挂 {@code help} 与 {@code --help},列出各组。查例子的那棵树不挂:例子只该落在动作上。 */
    CommandTree<S> withRootHelp(Supplier<Listing> listing) {
        dispatcher.register(help(NumenCli.HELP, listing));
        dispatcher.register(help(NumenCli.HELP_FLAG, listing));
        return this;
    }

    /** 挂上一组:组名就是一级命令。 */
    void add(CommandGroup group) {
        dispatcher.register(group(group));
    }

    ParseResults<S> parse(String line, S source) {
        return dispatcher.parse(line, source);
    }

    void execute(ParseResults<S> parse) throws CommandSyntaxException {
        dispatcher.execute(parse);
    }

    /** 一组:{@code --help}(可翻页)与各个动作,组本身不可执行。 */
    private LiteralArgumentBuilder<S> group(CommandGroup group) {
        LiteralArgumentBuilder<S> node = LiteralArgumentBuilder.literal(group.name());
        node.then(help(NumenCli.HELP_FLAG, () -> CommandHelp.group(group)));
        for (Action a : group.actions()) {
            node.then(action(a));
        }
        return node;
    }

    /**
     * 一个动作:名字下挂 {@code --help};在这一侧执行的,再把必填参数一格一格往下接,最后一格可执行;有可选参数的话,
     * 可执行的那一格下面再挂一格标志尾巴,同样可执行。
     */
    private LiteralArgumentBuilder<S> action(Action action) {
        LiteralArgumentBuilder<S> node = LiteralArgumentBuilder.literal(action.name());
        node.then(LiteralArgumentBuilder.<S>literal(NumenCli.HELP_FLAG).executes(ctx -> {
            ctx.getSource().reply(TaskResult.ok(CommandHelp.action(action)).toJson());
            return Command.SINGLE_SUCCESS;
        }));
        if (!runs.test(action)) {
            return node;
        }
        List<Param<?>> required = action.positionals();
        if (required.isEmpty()) {
            executable(node, action);
            return node;
        }
        ArgumentBuilder<S, ?> tip = executable(argument(required.get(required.size() - 1)), action);
        for (int i = required.size() - 2; i >= 0; i--) {
            tip = argument(required.get(i)).then(tip);
        }
        node.then(tip);
        return node;
    }

    private <B extends ArgumentBuilder<S, B>> B executable(B builder, Action action) {
        Command<S> run = ctx -> {
            action.execute(ctx.getSource(),
                    CommandArgs.fromCommand(action.positionals(), ctx, FlagsArgument.valuesIn(ctx)));
            return Command.SINGLE_SUCCESS;
        };
        builder.executes(run);
        List<Param<?>> optional = action.params().stream().filter(p -> !p.required()).toList();
        if (!optional.isEmpty()) {
            builder.then(RequiredArgumentBuilder.<S, Map<String, Object>>argument(
                    FlagsArgument.NODE, new FlagsArgument(optional)).executes(run));
        }
        return builder;
    }

    private <T> RequiredArgumentBuilder<S, T> argument(Param<T> param) {
        return RequiredArgumentBuilder.argument(param.name(), param.type().brigadier());
    }

    /** 一个显示列表的帮助节点:不带标志是第一页,{@code --page N} 翻页;页码不存在时抛出,附着用法回去。 */
    private LiteralArgumentBuilder<S> help(String literal, Supplier<Listing> listing) {
        Command<S> show = ctx -> {
            CommandArgs args = CommandArgs.fromCommand(List.of(), ctx, FlagsArgument.valuesIn(ctx));
            ctx.getSource().reply(TaskResult.ok(listing.get().page(args)).toJson());
            return Command.SINGLE_SUCCESS;
        };
        return LiteralArgumentBuilder.<S>literal(literal)
                .executes(show)
                .then(RequiredArgumentBuilder.<S, Map<String, Object>>argument(
                        FlagsArgument.NODE, new FlagsArgument(List.of(Listing.PAGE))).executes(show));
    }
}
