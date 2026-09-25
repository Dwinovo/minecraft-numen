package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code command} 工具:执行一行游戏指令,就像玩家在聊天栏里敲的那样。没有自己的快捷工具的一切都从这里进——
 * Numen 自己的命令({@code numen …})、原版的、别的模组的,是同一种写法。
 *
 * <p>先在主人客户端的小表上解析({@link NumenCli#run}):解析到客户端动作或帮助,当场执行;否则这次调用原样经
 * {@code ServerToolTransport} 送去服务端(和身体工具同一条运输),由服务端唯一的执行入口({@link CommandRunner})
 * 以她的身份执行,结果走原来的回执。外脑经 {@code NumenActuator} 调它也是这一条路。
 */
public final class CommandTool implements NumenTool {

    /** 工具名。 */
    public static final String NAME = "command";

    private static final Param<String> LINE = Param.required("command", ArgType.text(),
            "One command line, as a player would type it in chat; the leading / is optional. "
                    + "E.g. \"numen --help\", \"help give\".");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Run one game command as yourself, the way a player types it in chat. Numen's own commands start "
                + "with " + NumenCli.ROOT + " (the installed groups are listed under <commands>): `" + NumenCli.ROOT
                + " --help` lists the groups, `" + NumenCli.ROOT + " <group> --help` a group's actions, `"
                + NumenCli.ROOT + " <group> <action> --help` explains one; required arguments follow the action "
                + "in order, optional ones are flags written `--name value`. Every other command, vanilla or from "
                + "another mod: `help` lists the ones the server lets you run, `help <command>` shows one's usage. "
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

    /** 这次调用写的那一行,去掉前导 {@code /}。 */
    static String line(JsonObject args) {
        return NumenCli.bare(CommandArgs.fromJson(List.of(LINE), args).get(LINE));
    }

    /** 这一行作为一次调用的参数:{@code /numen drive} 与重放记的调用就是它。 */
    static JsonObject args(String line) {
        JsonObject args = new JsonObject();
        args.addProperty(LINE.name(), line);
        return args;
    }
}
