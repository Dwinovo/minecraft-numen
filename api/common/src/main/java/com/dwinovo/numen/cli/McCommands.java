package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.script.ScriptType;

/**
 * {@code numen.mc}:原版与模组的指令,写法和玩家在聊天栏里敲的一样(前面的 {@code /} 可写可不写),以她自己的权限执行。解析、过权限层
 * ({@code command(根名)})、执行、收回执都在 {@link CommandRunner} 的第 0 层,和人写的 {@code /…} 那一行同一条路。
 */
public final class McCommands {

    static final String GROUP = "mc";

    private static final Param<String> LINE = Param.required("command", ArgType.text(),
            "A Minecraft or mod command exactly as a player types it in chat; the leading / may be left out.");

    private McCommands() {}

    /** 引擎自己登记这一组:原版指令是引擎的一部分,和 {@link CommandRunner} 在一处。 */
    public static void install() {
        NumenCli.register(com.dwinovo.numen.api.NumenPlugins.NUMEN, GROUP, "Minecraft and mod commands, as a player types them in chat, with your own "
                + "permission level.", mc ->
                mc.server("run", "Run one Minecraft or mod command and return what it said.",
                                (src, args) -> CommandRunner.mc(src, Line.of(args.get(LINE)).text()), LINE)
                        .returns(ScriptType.table(
                                ScriptType.field("command", ScriptType.STRING, "The command as it ran, with its /."),
                                ScriptType.field("output", ScriptType.listOf(ScriptType.STRING), "What it said, line by line."),
                                ScriptType.field("result", ScriptType.INTEGER, "The number the command returned.")))
                        .example(call("help give"))
                        .example(call("time query daytime"))
                        .note("The server decides which commands you may use at your permission level; "
                                + "`" + call("help") + "` lists them, and `" + call("help give") + "` shows one's usage "
                                + "with the types, examples and what you could write next.")
                        .note("A command may need your owner's consent first; the call waits for the answer."));
    }

    /** 组的全名。 */
    static final String FULL = com.dwinovo.numen.api.NumenPlugins.NUMEN + "." + GROUP;

    /** 跑这一行原版指令怎么写:{@code numen.mc.run("help give")}。 */
    static String call(String line) {
        return ScriptEngine.IN_USE.function(FULL, "run") + "(\"" + line + "\")";
    }
}
