package com.dwinovo.numen.cli;

import com.dwinovo.numen.task.TaskResult;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 命令组的声明长成一侧的 Brigadier 树。两侧是同一份声明、同一个形状,只有一处不同:
 *
 * <ul>
 *   <li>根下是 {@code help} 与 {@code --help};组下是 {@code --help} 与每一个动作;每个动作下是 {@code --help}。
 *       帮助只读声明,所以两侧都答得出,每一层在两侧都有。</li>
 *   <li>动作的参数与标志尾巴、可执行的那一格,只长在<b>执行它的那一侧</b>({@link #runs})。另一侧的树上这个动作
 *       只有名字和帮助——主人客户端的小表里没有服务端动作的参数,MC 指令树里没有客户端动作的参数。</li>
 * </ul>
 *
 * <p>两侧的差别还在"源对象怎么变成 Numen 的源"({@link #handle}):主人客户端的树,源对象就是 {@link ClientSource};
 * MC 的树,源对象是她的 {@code CommandSourceStack},这次调用由执行入口放在它的回话去处里({@link Echo})。
 *
 * @param <S> 这一侧 Brigadier 的源对象
 */
abstract class CommandTree<S> {

    /**
     * 查例子用的形状:每个动作都长着参数,不分哪一侧——登记时把一组的例子在上面整行解析一遍({@link CommandGroup#close})。
     * 只解析、不执行。
     */
    static final CommandTree<Object> EXAMPLES = new CommandTree<>() {
        @Override
        void handle(Object source, Body body) {
            throw new IllegalStateException("the example tree only parses");
        }

        @Override
        boolean runs(Action action) {
            return true;
        }
    };

    /** 一格交给 Numen 之后做的事;帮助翻页越界时抛出,和别的解析错误一样附着用法回去。 */
    @FunctionalInterface
    interface Body {
        void run(CommandSource source) throws CommandSyntaxException;
    }

    /** 从源对象取出这次调用的 Numen 源,交给 {@code body}。 */
    abstract void handle(S source, Body body) throws CommandSyntaxException;

    /** 这个动作在这一侧执行吗。 */
    abstract boolean runs(Action action);

    /** 根上的帮助节点:{@code help} 与 {@code --help} 各一格,列出各组。 */
    List<LiteralArgumentBuilder<S>> rootHelp(Supplier<Listing> listing) {
        return List.of(help(NumenCli.HELP, listing), help(NumenCli.HELP_FLAG, listing));
    }

    /** 一组:{@code --help}(可翻页)与各个动作,组本身不可执行。 */
    LiteralArgumentBuilder<S> group(CommandGroup group) {
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
            handle(ctx.getSource(), source -> source.reply(TaskResult.ok(CommandHelp.action(action)).toJson()));
            return Command.SINGLE_SUCCESS;
        }));
        if (!runs(action)) {
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
            CommandArgs args = CommandArgs.fromCommand(action.positionals(), ctx, FlagsArgument.valuesIn(ctx));
            handle(ctx.getSource(), source -> action.execute(source, args));
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
            handle(ctx.getSource(), source -> source.reply(TaskResult.ok(listing.get().page(args)).toJson()));
            return Command.SINGLE_SUCCESS;
        };
        return LiteralArgumentBuilder.<S>literal(literal)
                .executes(show)
                .then(RequiredArgumentBuilder.<S, Map<String, Object>>argument(
                        FlagsArgument.NODE, new FlagsArgument(List.of(Listing.PAGE))).executes(show));
    }
}
