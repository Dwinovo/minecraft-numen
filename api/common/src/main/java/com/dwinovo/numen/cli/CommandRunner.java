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
 * 服务端唯一的执行入口。她要执行的每一行({@code command} 工具、{@code /numen drive}、重启后的重放)都从这里过,
 * 按 {@link Line} 分到两层:
 *
 * <ul>
 *   <li><b>第 1 层</b>:在 Numen 服务端的树上解析、执行({@link NumenCli#serve}),处理函数拿到这次调用的
 *       {@link ServerSource}。身体对世界的动作照常由权限层按动作裁决。</li>
 *   <li><b>第 0 层</b>(行首 {@code /}):MC 的指令树,以她自己的权限执行,和她在聊天栏里敲的一样。
 *     <ol>
 *       <li><b>先解析</b>:以她的 {@code CommandSourceStack} 在服务器的指令树上解析。写不通(没有这条、服务器不让她用、
 *           参数写错)当场失败并附上用法,不打扰主人,不进任务槽。能用哪些是服务器按她的权限等级定的,这里不放宽也不收紧。</li>
 *       <li><b>过权限层</b>:执行一行指令是动作 {@code command(根名)},放行、问主人、拒绝由权限层裁决。</li>
 *       <li><b>执行</b>:{@link Commands#performPrefixedCommand},和玩家在聊天栏里敲的是同一条路,加载器的指令事件
 *           (别的模组在那里拦或记指令)照常。来源是她自己的,只把回话去处换成 {@link Echo}。</li>
 *       <li><b>回执</b>:指令说的话由 {@link Echo} 收成回执;{@code help <指令>} 在原版那一行用法之后接上从 Brigadier
 *           挖出的参数类型、例子与此刻的候选({@link BrigadierHelp})。</li>
 *     </ol>
 *     要问主人时这次调用悬着({@link PendingCommands}),主人答复后接着走。</li>
 * </ul>
 */
public final class CommandRunner {

    /** 原版的 {@code help}:不带参数列她此刻能执行的指令,带一条指令给出它的用法。 */
    private static final String HELP = "help";
    /** 写不通时附的那一句:原版的 {@code help} 按她的来源过滤,列的就是她此刻能执行的。 */
    private static final String HELP_HINT = Line.MC + HELP + " lists the commands you can run.";

    private CommandRunner() {}

    /**
     * 以她的身份执行模型写的一行:{@code /numen drive} 从这里进,和 {@code command} 工具是同一个入口。
     *
     * @param callId 这次调用的 id;长活的受理与收尾都对着它
     */
    public static void run(NumenPlayer her, String callId, String typed, Consumer<String> reply) {
        String line = typed.strip();
        line(new ServerSource(her, CommandTool.NAME, callId, CommandTool.args(line), reply), line);
    }

    /** {@code command} 工具在服务端的入口:按 {@link Line} 分到两层。 */
    static void line(ServerSource call, String typed) {
        Line line = Line.of(typed);
        if (line.mc()) {
            mc(call, line.text());
        } else {
            NumenCli.serve(line.text(), call);
        }
    }

    /** 第 0 层的一行:解析、过权限层、执行。 */
    private static void mc(ServerSource call, String line) {
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
     * 这一行第 0 层指令她此刻写不写得通;写得通返回 null。和服务器执行前做的是同一道检查,写不通时说为什么——根不存在、
     * 服务器不让她用这条、还是参数写错(附上这条的用法)。写错了的,最后接上"你是不是要写"({@link Completions#didYouMean}:
     * 出错位置上她能写的候选里最接近的几个,和第 1 层同一个函数)。
     */
    static String problem(CommandDispatcher<CommandSourceStack> dispatcher, String line, CommandSourceStack her) {
        ParseResults<CommandSourceStack> parse = dispatcher.parse(line, her);
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
                return "there is no /" + root + " command on this server. " + HELP_HINT + Completions.didYouMean(parse);
            }
            if (!node.canUse(her)) {
                return "the server does not let you use /" + root + ". " + HELP_HINT;
            }
            return e.getMessage() + "\nUsage: /" + dispatcher.getSmartUsage(dispatcher.getRoot(), her).get(node)
                    + Completions.didYouMean(parse);
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

    /**
     * 以她的身份执行,回话去处换成 {@link Echo},它收下的就是回执。{@code help <指令>} 跑成了,原版那一行用法之后接上从
     * Brigadier 挖出的几项({@link BrigadierHelp})——用法仍是原版 {@code help} 自己说的那一句,这里只接它没说的。
     */
    private static void perform(ServerSource call, String line) {
        NumenPlayer her = call.companion();
        Echo echo = new Echo(her.shouldInformAdmins());
        CommandDispatcher<CommandSourceStack> dispatcher = her.getServer().getCommands().getDispatcher();
        her.getServer().getCommands().performPrefixedCommand(
                her.createCommandSourceStack().withSource(echo).withCallback(echo), line);
        String[] words = line.split(" ", 2);
        call.reply(echo.receipt(line, () -> words[0].equals(HELP) && words.length == 2
                ? BrigadierHelp.mine(dispatcher, words[1], her.createCommandSourceStack())
                : ""));
    }
}
