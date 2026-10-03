package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.script.ScriptLimits;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Map;

/**
 * 她唯一的工具:一段程序(语言见 {@link ScriptEngine},眼下是 Lua),一次调用跑完,回一张回执。程序里的每个 API 函数
 * ({@code numen.work.dig(b)})就是登记处的一个动作,读法与执行见 {@link NumenCli};模块里的函数({@code numen.move.to})用同一种语言写成,
 * 程序按名字直接用。工具名与程序怎么写随脚本语言,其余都与语言无关。
 *
 * <p>程序由大脑的派发器跑({@code SerialCalls} 认出这个工具,经 {@code ScriptCall} 逐个派调用、等身体收尾、在调用之间停下),
 * 不经 {@link #invoke}。外接大脑经 {@code NumenActuator} 跑它,交给的是同伴自己的那一个派发器(内脑闲着时借用)。
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
        return "Runs a " + engine.language() + " program that acts through your API, and returns one receipt when it "
                + "ends. This is how you do anything in the world: every function is listed in <api>.\n"
                + "- " + engine.howToCall() + "\n"
                + "- One call is a one-line program: `numen.status.self()`. When each next step follows from what a "
                + "call returned — going through what a scan found, repeating until nothing is left, stopping on the "
                + "first failure — write the steps as one program.\n"
                + "- The receipt says how the program ended (on an error: the line and the call's error, usage and "
                + "hint), then one line per API call (where, which function, ok or failed, the first line of its "
                + "reply), what it returned and what it printed. Each task's account of what it changed still "
                + "arrives as its own task_finished event.\n"
                + "- A run stops at " + ScriptLimits.COMMANDS + " API calls or " + ScriptLimits.WALL_MILLIS / 60_000
                + " minutes, and when it runs " + ScriptLimits.INSTRUCTIONS_PER_SLICE + " instructions without "
                + "calling one. Your owner speaking, an urgent event or the stop button stops it between calls.\n"
                + "- Modules (listed in <api>) are functions written in " + engine.language() + " that a program uses "
                + "by name, with no require: `numen.work.collect()`. `numen.module.show(\"numen.work\")` prints one; "
                + "numen.module.save keeps a module you wrote (its text: functions put in a table, and the table "
                + "returned) under a name for later programs.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Param.schemaOf(List.of(CODE));
    }

    /** 程序只由派发器跑(见类注释),调到这里是接线错了。 */
    @Override
    public void invoke(ToolCall call) {
        throw new IllegalStateException(name() + " runs through a dispatcher (SerialCalls), not through invoke");
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
