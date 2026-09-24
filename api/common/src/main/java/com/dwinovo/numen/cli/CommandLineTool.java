package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code numen} 工具:参数是一行命令,交给 {@link NumenCli}。没有自己的快捷工具的一切都从这里进。
 *
 * <p>先在主人客户端解析:帮助与解析错误当场回,客户端动作当场执行;解析到服务端动作时,这次调用原样经
 * {@code ServerToolTransport} 送去服务端(和身体工具同一条运输),服务端再解析一遍同一棵树、执行,
 * 结果走原来的回执。外脑经 {@code NumenActuator} 调它也是这一条路。
 */
public final class CommandLineTool implements NumenTool {

    private static final Param<String> COMMAND = Param.required("command", ArgType.text(),
            "The whole command line, starting with " + NumenCli.ROOT + ", e.g. \"" + NumenCli.ROOT + " help\".");

    @Override
    public String name() {
        return NumenCli.ROOT;
    }

    @Override
    public String description() {
        return "Run one Numen command. Everything without a tool of its own lives here, in command groups "
                + "(the installed groups are listed under <commands>). Write the whole line starting with "
                + NumenCli.ROOT + ": `" + NumenCli.ROOT + " help` lists the groups, `" + NumenCli.ROOT
                + " <group> --help` lists a group's actions, `" + NumenCli.ROOT
                + " <group> <action> --help` explains one. Required arguments follow the action in order; "
                + "optional ones are flags written `--name value`. A line with a mistake comes back with the "
                + "usage of the level it failed at.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Param.schemaOf(List.of(COMMAND));
    }

    @Override
    public void invoke(ToolCall call) {
        NumenCli.run(line(call.args()), ClientSource.of(call));
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        NumenCli.run(line(args), new ServerSource(companion, name(), toolCallId, args, reply));
    }

    private static String line(JsonObject args) {
        return CommandArgs.fromJson(List.of(COMMAND), args).get(COMMAND);
    }
}
