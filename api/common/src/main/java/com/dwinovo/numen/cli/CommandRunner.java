package com.dwinovo.numen.cli;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Gate;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.permission.Verdict;
import com.dwinovo.numen.task.TaskResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 服务端唯一的执行入口。她要执行的每一行指令({@code command} 工具、{@code /numen drive}、重启后的重放)和每一次
 * 快捷工具调用都从这里过;{@code /numen} 自己的、原版的、模组的指令是同一条路。
 *
 * <h2>一行指令</h2>
 * <ol>
 *   <li><b>先解析</b>:以她的 {@code CommandSourceStack} 在服务器的指令树上解析。写不通(没有这条、服务器不让她用、
 *       参数写错)当场失败并附上用法,不打扰主人,不进任务槽。能用哪些是服务器按她的权限等级定的,这里不放宽也不收紧。</li>
 *   <li><b>过权限层</b>:执行一行指令是动作 {@code command(根名)},放行、问主人、拒绝由权限层裁决。</li>
 *   <li><b>执行</b>:{@link Commands#performPrefixedCommand},和玩家在聊天栏里敲的是同一条路,加载器的指令事件
 *       (别的模组在那里拦或记指令)照常。来源是她自己的,只把回话去处换成 {@link Echo}——它同时带着这次调用。</li>
 *   <li><b>回执</b>:{@code /numen} 的处理函数从来源里取出这次调用,自己回执或把长活交给任务槽;原版与模组的指令说的话
 *       由 {@link Echo} 收成回执。</li>
 * </ol>
 *
 * <h2>快捷工具</h2>
 * 快捷工具是一条 {@code /numen} 命令的 alias:参数已按同一组参数类型读好,权限层裁决的是它作为 alias 的那一行,
 * 放行后交同一个处理函数,不拼行再解析。
 *
 * <p>要问主人时这次调用悬着({@link PendingCommands}),主人答复后接着走。
 */
public final class CommandRunner {

    /** 写不通时附的那一句:原版的 {@code help} 按她的来源过滤,列的就是她此刻能执行的。 */
    private static final String HELP_HINT = "help lists the commands you can run.";

    private CommandRunner() {}

    /**
     * 以她的身份执行一行(前导 {@code /} 可有可无):{@code /numen drive} 从这里进,和 {@code command} 工具是同一个入口。
     *
     * @param callId 这次调用的 id;长活的受理与收尾都对着它
     */
    public static void run(NumenPlayer her, String callId, String typed, Consumer<String> reply) {
        String line = NumenCli.bare(typed);
        line(new ServerSource(her, CommandTool.NAME, callId, CommandTool.args(line), reply), line);
    }

    /** {@code command} 工具在服务端的入口:解析、过权限层、执行。 */
    static void line(ServerSource call, String line) {
        if (line.isEmpty()) {
            call.reply(TaskResult.fail("there is no command to run.").toJson());
            return;
        }
        NumenPlayer her = call.companion();
        String problem = problem(her.getServer().getCommands().getDispatcher(), line, her.createCommandSourceStack());
        if (problem != null) {
            call.reply(TaskResult.fail(problem).toJson());
            return;
        }
        authorize(call, line, allowed -> perform(allowed, line));
    }

    /**
     * 快捷工具在服务端的入口:读好的参数交给同一个处理函数,之前同样过权限层。
     *
     * @param line 这次调用写成命令的那一行,权限层与征询卡片认的就是它
     */
    static void action(ServerSource call, Action action, CommandArgs args, String line) {
        authorize(call, line, allowed -> action.execute(allowed, args));
    }

    /**
     * 这一行她此刻写不写得通;写得通返回 null。{@code numen} 开头的是 Numen 的命令:报错是 Brigadier 的原话加上出错
     * 那一层的帮助,和主人客户端那一侧同一种说法。别的指令:和服务器执行前做的是同一道检查,写不通时说为什么——根不存在、
     * 服务器不让她用这条、还是参数写错(附上这条的用法)。
     */
    static String problem(CommandDispatcher<CommandSourceStack> dispatcher, String line, CommandSourceStack her) {
        ParseResults<CommandSourceStack> parse = dispatcher.parse(line, her);
        if (NumenCli.owns(parse)) {
            return NumenCli.problem(parse, line);
        }
        try {
            Commands.validateParseResults(parse);
            if (ContextChain.tryFlatten(parse.getContext().build(line)).isEmpty()) {
                throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand()
                        .createWithContext(parse.getReader());
            }
            return null;
        } catch (CommandSyntaxException e) {
            String root = line.split(" ", 2)[0];
            CommandNode<CommandSourceStack> node = dispatcher.getRoot().getChild(root);
            if (node == null) {
                return "there is no /" + root + " command on this server. " + HELP_HINT;
            }
            if (!node.canUse(her)) {
                return "the server does not let you use /" + root + ". " + HELP_HINT;
            }
            return e.getMessage() + "\nUsage: /" + dispatcher.getSmartUsage(dispatcher.getRoot(), her).get(node);
        }
    }

    /**
     * 执行这一行就是动作 {@code command(根名)}:根名在服务器此刻的指令树上认,别名同认。放行就接着走;不许就带着理由
     * 收场;要问就挂起这次调用,主人答复后再走。
     */
    private static void authorize(ServerSource call, String line, Consumer<ServerSource> go) {
        NumenPlayer her = call.companion();
        com.dwinovo.numen.permission.Action command = com.dwinovo.numen.permission.Action.command(
                line, her.getServer().getCommands().getDispatcher().getRoot());
        Gate gate = Permission.gateFor(her);
        Verdict verdict = gate.judgeLive(command, her.serverLevel());
        switch (verdict.kind()) {
            case ALLOW -> go.accept(call);
            case DENY -> call.reply(refused(line, verdict.reason()));
            case ASK -> PendingCommands.of(her).await(call, line,
                    List.of(gate.consentItemLive(command, verdict, her.serverLevel())), go);
        }
    }

    /** 没执行这一行的回执:理由是规则、模式或主人的原话。 */
    static String refused(String line, String why) {
        return TaskResult.fail("did not run /" + line + ": " + why, Map.of("command", "/" + line)).toJson();
    }

    /** 以她的身份执行,回话去处换成带着这次调用的 {@link Echo};跑完了没人答的,回显就是回执。 */
    private static void perform(ServerSource call, String line) {
        NumenPlayer her = call.companion();
        Echo echo = new Echo(call);
        her.getServer().getCommands().performPrefixedCommand(
                her.createCommandSourceStack().withSource(echo).withCallback(echo), line);
        echo.settle(line);
    }
}
