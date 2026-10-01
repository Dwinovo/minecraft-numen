package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.script.ScriptLimits;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Map;

/**
 * 组合命令的那个工具:把几条命令写成一段程序,一次调用跑完。每个命令是一个函数({@code work.dig("ores/g3")}),背后就是那一行
 * 命令——同一份登记、同一次解析、同样过权限、照常记账、受理即能跑;一行命令仍然走 {@code command} 工具。工具名与程序怎么写
 * 随脚本语言({@link ScriptEngine}),其余都与语言无关。
 *
 * <p>为什么单开一个工具而不是让 {@code command} 也收程序:两种写法是两种语言,一个工具名说清它收哪一种,模型不必猜这一段该
 * 按命令行还是按程序读。
 *
 * <p>程序由大脑的派发器跑({@code SerialCalls} 认出这个工具,经 {@code ScriptCall} 逐条派命令、等身体收尾、在命令之间停下),
 * 不经 {@link #invoke}。外接大脑直接调工具,没有那个派发器,收到的是一条说明。
 */
public final class ScriptTool implements NumenTool {

    private static final Param<String> CODE = Param.required("code", ArgType.text(), "The program.");

    @Override
    public String name() {
        return ScriptEngine.IN_USE.toolName();
    }

    @Override
    public String description() {
        // 照 Claude Code 的工具描述写:动词起头,只说它做什么、环境是什么样
        ScriptEngine engine = ScriptEngine.IN_USE;
        return "Runs a " + engine.language() + " program that combines your commands, and returns one receipt when it "
                + "ends.\n"
                + "- " + engine.howToCall() + "\n"
                + "- A command function returns when the command is done; for work that occupies your body, when that "
                + "task has finished.\n"
                + "- Write one when each next step follows from what a command returned: going through the parts of "
                + "an area, repeating until nothing is left, stopping on the first failure. A single command is just "
                + "the command tool.\n"
                + "- The receipt says how the program ended and gives one line per line of it that ran commands. Each "
                + "task's account of what it changed still arrives as its own task_finished event.\n"
                + "- A run stops at " + ScriptLimits.COMMANDS + " commands or " + ScriptLimits.WALL_MILLIS / 60_000
                + " minutes, and when it runs " + ScriptLimits.INSTRUCTIONS_PER_SLICE + " instructions without "
                + "calling a command. Your owner speaking, an urgent event or the stop button stops it between "
                + "commands.\n"
                + "- Scripts kept by name: `script list`, `script show <name>`, `script run <name> [args...]` "
                + "(`script.run` in a program), `script save <name> <code>` to keep one you wrote.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Param.schemaOf(List.of(CODE));
    }

    /** 外接大脑直接调到这里:它没有在命令之间等身体收尾、被打断时停下的派发器,跑不了程序。 */
    @Override
    public void invoke(ToolCall call) {
        call.complete(TaskResult.fail("Programs run in the companion's own brain, which waits for each command "
                + "between lines; from here, run one command per call with the command tool.").toJson());
    }

    /**
     * 这次调用里写的程序。
     *
     * @param arguments 模型写的参数 JSON
     * @throws IllegalArgumentException 参数不是 JSON、没写程序或多写了别的
     */
    public static String code(String arguments) {
        JsonObject args;
        try {
            args = JsonParser.parseString(arguments).getAsJsonObject();
        } catch (RuntimeException notJson) {
            throw new IllegalArgumentException("invalid arguments JSON: " + notJson.getMessage());
        }
        return CommandArgs.fromJson(List.of(CODE), args).get(CODE);
    }

    /** 一段程序写成这个工具的一次调用的参数。 */
    public static JsonObject args(String code) {
        JsonObject args = new JsonObject();
        args.addProperty(CODE.name(), code);
        return args;
    }
}
