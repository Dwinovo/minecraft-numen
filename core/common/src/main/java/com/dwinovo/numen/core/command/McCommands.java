package com.dwinovo.numen.core.command;

import static com.dwinovo.numen.task.TaskDispatch.ctx;
import static com.dwinovo.numen.task.TaskDispatch.runSync;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.task.command.McCommandTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.List;

/**
 * {@code numen mc <command...>}:以她的身份执行一条游戏指令,原版的和模组的都一样——交给服务器自己的指令调度器,
 * 来源是她的 {@link CommandSourceStack}。
 *
 * <ul>
 *   <li><b>能用哪些是服务器的事。</b>权限等级就是服务器给她的等级:她是 OP 才有 {@code /give},我们不放宽也不收紧。
 *       服务器不让她用、或者写错了的,当场如实失败,不去打扰主人。</li>
 *   <li><b>执行是一个动作,由权限层裁决。</b>写得通的指令交给任务槽里的一次短任务({@link McCommandTaskRecord}):
 *       动手前把 {@code command} 动作交给权限层,要问就等主人答复,和别的会问主人的动作同一套机制。</li>
 *   <li><b>回显原样返回。</b>指令说的每一句都进回执;它对她身体做的事(背包、位置)照常出现在状态里。</li>
 *   <li><b>帮助由服务器生成。</b>{@code numen mc --help} 列出服务器此刻按她的权限等级让她用的指令用法,分页。</li>
 * </ul>
 */
public final class McCommands {

    /** 一次执行的期限(游戏刻):指令当刻就跑完;等主人答复的那些刻不算(见任务基类)。 */
    private static final long TIMEOUT_TICKS = 5 * 20;

    private static final Param<String> COMMAND = Param.required("command", ArgType.text(),
            "The command as you would type it in chat, with or without the leading /, e.g. msg Steve on my way.");

    private McCommands() {}

    /** 经插件那扇门登记这一组;它没有快捷工具,工具表不变。 */
    public static void install(NumenApi numen) {
        numen.registerCommands("mc", "Run game commands as yourself; which ones you can use depends on the "
                + "permission level the server gives you.", mc ->
                mc.serverDirect("Run one game command as yourself, as a player typing it in chat. What the command "
                                + "says comes back as the result; what it does to your body shows in your status. "
                                + "A command waits for your owner's consent unless their rules allow it.",
                                McCommands::run, COMMAND)
                        .catalog("Commands the server lets you run now:", McCommands::usable));
    }

    /**
     * 先按她的身份在服务器的指令树上解析:写不通(服务器不让她用、没有这条、参数写错)当场失败,理由说清;
     * 写得通的交给任务槽执行。
     */
    private static void run(ServerSource src, CommandArgs args) {
        NumenPlayer her = src.companion();
        String typed = args.get(COMMAND).strip();
        String line = typed.startsWith("/") ? typed.substring(1).strip() : typed;
        if (line.isEmpty()) {
            src.reply(TaskResult.fail("there is no command after the slash.").toJson());
            return;
        }
        CommandDispatcher<CommandSourceStack> dispatcher = her.getServer().getCommands().getDispatcher();
        String problem = problem(dispatcher, line, her.createCommandSourceStack());
        if (problem != null) {
            src.reply(TaskResult.fail(problem).toJson());
            return;
        }
        runSync(her, new McCommandTaskRecord(src.toolName(), src.toolCallId(),
                ctx(src.toolCallId(), her).deadline(TIMEOUT_TICKS), line), src::reply);
    }

    /**
     * 这一行她此刻写不写得通:和服务器执行前做的是同一道检查(解析完、没有剩下的字、走到一个能执行的节点)。
     * 写不通时说为什么——根不存在、服务器不让她用这条、还是参数写错(附上这条的用法)。写得通返回 null。
     */
    private static String problem(CommandDispatcher<CommandSourceStack> dispatcher, String line,
                                  CommandSourceStack source) {
        ParseResults<CommandSourceStack> parse = dispatcher.parse(line, source);
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
                return "there is no /" + root + " command on this server. " + helpHint();
            }
            if (!node.canUse(source)) {
                return "the server does not let you use /" + root + ". " + helpHint();
            }
            return e.getMessage() + "\nUsage: /" + dispatcher.getSmartUsage(dispatcher.getRoot(), source).get(node);
        }
    }

    private static String helpHint() {
        return "numen mc --help lists the commands you can run.";
    }

    /** 帮助的目录:服务器此刻按她的权限等级让她用的每条指令的用法,按字母排。 */
    private static List<String> usable(ServerSource src) {
        CommandDispatcher<CommandSourceStack> dispatcher = src.companion().getServer().getCommands().getDispatcher();
        return dispatcher.getSmartUsage(dispatcher.getRoot(), src.companion().createCommandSourceStack()).values()
                .stream().sorted().map(usage -> "  /" + usage).toList();
    }
}
