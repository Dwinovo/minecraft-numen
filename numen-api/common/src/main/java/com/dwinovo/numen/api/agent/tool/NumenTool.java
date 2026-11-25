package com.dwinovo.numen.api.agent.tool;

import com.dwinovo.numen.api.agent.provider.IToolSpec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 模型工具表里的一个工具。LLM 侧的描述面(name/description/parameterSchema)继承自连接层的 {@link IToolSpec},执行面只有
 * {@link #invoke}。她自己的工具是跑脚本的那一个({@code ScriptTool}),身体与世界的一切都经它里面的 API 函数;另外三个只管大脑
 * 自己的事:装技能({@code SkillTool})、记计划({@code TodoTool})与札记({@code MemoryTool})。再有就是主人接进来的外部 MCP 工具,各自带着协议去调外部服务。
 */
public interface NumenTool extends IToolSpec {

    /**
     * Run this tool for one call — Numen API's ONLY entry point. 当场完成、去异步、发自己的包都由工具自便,最后经
     * {@link ToolCall} 报结果。
     */
    void invoke(ToolCall call);

    /** 这个工具的性质,见 {@link ToolHints};没声明的按最保守的算。 */
    default ToolHints hints() {
        return ToolHints.UNKNOWN;
    }

    /**
     * 这一次调用在主人的面板上读作什么:一句短话,没有值得说的是空串。面板上的工具条与"她在干什么"都问它,
     * 一次调用读成什么只有工具自己知道。
     */
    default String summary(JsonObject args) {
        return "";
    }

    /** {@code tool} 对这段参数 JSON 的 {@link #summary};工具没登记(旧日志里的调用)或参数读不出来是空串。 */
    static String summaryOf(NumenTool tool, String arguments) {
        if (tool == null) {
            return "";
        }
        try {
            return tool.summary(arguments == null || arguments.isBlank() ? new JsonObject()
                    : JsonParser.parseString(arguments).getAsJsonObject());
        } catch (RuntimeException unreadable) {
            return "";   // 参数不是一个 JSON 对象:这次调用没有可说的
        }
    }
}
