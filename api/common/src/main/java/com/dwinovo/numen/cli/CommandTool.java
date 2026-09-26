package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code command} 工具:执行一行命令。她只有这一个能力,快捷工具是它的 alias。一行分两层({@link Line}):
 * 行首不带 {@code /} 的是 Numen 给她的命令(第 1 层,{@link NumenCli});带 {@code /} 的是原版与模组的原生指令(第 0 层),
 * 以她自己的权限执行,和玩家在聊天栏里敲的一样。
 *
 * <p>先在主人客户端分({@link NumenCli#run}):第 1 层的客户端动作、帮助与写错的当场答;其余这次调用原样经
 * {@code ServerToolTransport} 送去服务端(和身体工具同一条运输),由服务端唯一的执行入口({@link CommandRunner})
 * 执行,结果走原来的回执。外脑经 {@code NumenActuator} 调它也是这一条路。
 */
public final class CommandTool implements NumenTool {

    /** 工具名。 */
    public static final String NAME = "command";

    private static final Param<String> LINE = Param.required("command", ArgType.text(),
            "One command line. Without a leading / it is one of Numen's commands, e.g. \"numen --help\"; "
                    + "with a leading / it is a native command, e.g. \"/help give\".");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Run one command line. Two kinds of line:\n"
                + "- Without a leading /: Numen's commands for you, grouped (the installed groups are listed under "
                + "<commands>). `" + NumenCli.ROOT + " --help` lists the groups, `" + NumenCli.ROOT
                + " <group> --help` a group's actions, `" + NumenCli.ROOT + " <group> <action> --help` explains "
                + "one; required arguments follow the action in order, optional ones are flags written "
                + "`--name value`.\n"
                + "- With a leading /: a native Minecraft or mod command, run as yourself exactly as a player types "
                + "it in chat, with your own permission level. `/help` lists the ones the server lets you run, "
                + "`/help <command>` shows one's usage. Each may need your owner's consent first.\n"
                + "What the command says comes back as the result; a line with a mistake comes back with the "
                + "usage of the level it failed at.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Param.schemaOf(List.of(LINE));
    }

    @Override
    public void invoke(ToolCall call) {
        NumenCli.run(line(call.args()), ClientSource.of(call));
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        CommandRunner.line(new ServerSource(companion, NAME, toolCallId, args, reply), line(args));
    }

    /** 这次调用写的那一行,原样(分到哪一层见 {@link Line})。 */
    static String line(JsonObject args) {
        return CommandArgs.fromJson(List.of(LINE), args).get(LINE);
    }

    /** 这一行作为一次调用的参数:{@code /numen drive} 与重放记的调用就是它。 */
    static JsonObject args(String line) {
        JsonObject args = new JsonObject();
        args.addProperty(LINE.name(), line);
        return args;
    }
}
