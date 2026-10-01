package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.lua.ScriptLimits;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Map;

/**
 * {@code lua} 工具:把几条命令组合成一段 Lua 程序,一次调用跑完。每个命令是一个函数({@code work.dig("ores/g3")}),背后就是
 * 那一行命令——同一份登记、同一次解析、同样过权限、照常记账、受理即能跑;一行命令仍然走 {@code command} 工具。
 *
 * <p>为什么单开一个工具而不是让 {@code command} 也收 Lua:两种写法是两种语言,一个工具名说清它收哪一种,模型不必猜这一段该
 * 按命令行还是按 Lua 读。
 *
 * <p>脚本由大脑的派发器跑({@code SerialCalls} 认出这个工具,经 {@code ScriptCall} 逐条派命令、等身体收尾、在命令之间停下),
 * 不经 {@link #invoke}。外接大脑直接调工具,没有那个派发器,收到的是一条说明。
 */
public final class LuaTool implements NumenTool {

    /** 工具名。 */
    public static final String NAME = "lua";

    private static final Param<String> CODE = Param.required("code", ArgType.text(),
            "The Lua program.");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        // 照 Claude Code 的工具描述写:动词起头,只说它做什么、环境是什么样
        return "Runs a Lua 5.2 program that combines your commands, and returns one receipt when it ends.\n"
                + "- Every command `<group> <action> <objects...> [--option value]` is the function "
                + "`group.action(objects..., {option = value})`: `work.dig(\"ores/g3\")`, "
                + "`move.goto(\"ores/g3\", {arrive = \"dig\"})`. An object written as several words on the command "
                + "line is a list here: `{120, 64, -35}`.\n"
                + "- A command function returns when the command is done; for work that occupies your body, when that "
                + "task has finished. It returns `{ok = true or false, text = what the command said, data = {...}}`; "
                + "a query whose help says so returns its value directly, so `for _, p in ipairs(area.parts(\"ores\"))` "
                + "loops over it, and raises an error when it fails.\n"
                + "- `print(...)` writes into the receipt. `...` and `arg` hold the arguments of a script run by name.\n"
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
                + "(`script.run` here), `script save <name> <code>` to keep one you wrote.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Param.schemaOf(List.of(CODE));
    }

    /** 外接大脑直接调到这里:它没有在命令之间等身体收尾、被打断时停下的派发器,跑不了脚本。 */
    @Override
    public void invoke(ToolCall call) {
        call.complete(TaskResult.fail("Lua programs run in the companion's own brain, which waits for each command "
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
}
