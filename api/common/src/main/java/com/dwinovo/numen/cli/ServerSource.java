package com.dwinovo.numen.cli;

import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.function.Consumer;

/**
 * 服务端的命令源:活体、这次调用本身、回信口——和身体工具 {@code onServerCall} 拿到的是同样几样。
 *
 * <p>{@link #toolName()} 与 {@link #args()} 是<b>这次调用本身</b>:从快捷工具进来是那个工具名和它的 JSON,
 * 从 {@code numen} 进来是 {@code numen} 和 {@code {"command": "…"}}。长活交给 {@code TaskDispatch.setTask}
 * 时把它们原样交过去(任务记录用 {@link #toolName()} 起名),重启后的重放就走同一个入口再来一遍——
 * 不需要为命令另记一种配方。
 */
public final class ServerSource implements CommandSource {

    private final NumenPlayer companion;
    private final String toolName;
    private final String toolCallId;
    private final JsonObject args;
    private final Consumer<String> reply;

    ServerSource(NumenPlayer companion, String toolName, String toolCallId, JsonObject args,
                 Consumer<String> reply) {
        this.companion = companion;
        this.toolName = toolName;
        this.toolCallId = toolCallId;
        this.args = args;
        this.reply = reply;
    }

    /** 这具身体。 */
    public NumenPlayer companion() {
        return companion;
    }

    /** 调用进来时用的工具名:快捷工具名,或 {@code numen}。 */
    public String toolName() {
        return toolName;
    }

    /** 模型那次 {@code tool_call} 的 id,要跟着结果回去。 */
    public String toolCallId() {
        return toolCallId;
    }

    /** 调用进来时的 JSON 参数,原样。 */
    public JsonObject args() {
        return args;
    }

    @Override
    public void reply(String resultJson) {
        reply.accept(resultJson);
    }
}
