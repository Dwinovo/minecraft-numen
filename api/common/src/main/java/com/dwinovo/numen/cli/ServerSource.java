package com.dwinovo.numen.cli;

import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.function.Consumer;

/**
 * 服务端的一次调用:活体、这次调用本身、回信口——和身体工具 {@code onServerCall} 拿到的是同样几样。服务端动作的处理函数
 * 拿到的就是它。
 *
 * <p>{@link #toolName()} 与 {@link #args()} 是<b>这次调用本身</b>:从快捷工具进来是那个工具名和它的 JSON,
 * 从 {@code command} 工具进来是 {@code command} 和 {@code {"command": "…"}}。长活交给
 * {@code TaskDispatch.setTask(source, record)} 时,重启后的重放记的就是它们,走同一个入口再来一遍——不需要为命令另记
 * 一种配方。
 *
 * <p>它就是服务端那棵第 1 层树上的来源:解析到动作,处理函数直接拿到它——调用 id、任务名、回信口一路跟着这次调用走。
 *
 * <p>给模型看的任务名是另一回事,见 {@link #taskName()}:它要说出是哪个动作,而 {@code command} 这个工具名说不出。
 * 所以解析到动作、交给处理函数之前,源对象先绑上那个动作({@link #running})。
 */
public final class ServerSource implements CommandSource {

    private final NumenPlayer companion;
    private final String toolName;
    private final String toolCallId;
    private final JsonObject args;
    private final Consumer<String> reply;
    /** 解析到的动作;交给处理函数之前由 {@link #running} 绑上。 */
    private final Action action;
    /** 主人为这次调用点了头时,回执末尾交代的那一句;没问过主人为 null。 */
    private final String allowance;

    ServerSource(NumenPlayer companion, String toolName, String toolCallId, JsonObject args,
                 Consumer<String> reply) {
        this(companion, toolName, toolCallId, args, reply, null, null);
    }

    private ServerSource(NumenPlayer companion, String toolName, String toolCallId, JsonObject args,
                         Consumer<String> reply, Action action, String allowance) {
        this.companion = companion;
        this.toolName = toolName;
        this.toolCallId = toolCallId;
        this.args = args;
        this.reply = reply;
        this.action = action;
        this.allowance = allowance;
    }

    /** 同一次调用,绑上解析到的动作。 */
    ServerSource running(Action action) {
        return new ServerSource(companion, toolName, toolCallId, args, reply, action, allowance);
    }

    /** 同一次调用,主人点了头:回执末尾交代 {@code allowance} 这一句。 */
    ServerSource allowed(String allowance) {
        return new ServerSource(companion, toolName, toolCallId, args, reply, action, allowance);
    }

    /** 这具身体。 */
    public NumenPlayer companion() {
        return companion;
    }

    /** 调用进来时用的工具名:快捷工具名,或 {@code command}。 */
    public String toolName() {
        return toolName;
    }

    /**
     * 这次调用派下的活叫什么——任务记录、{@code task_finished}、{@code <current_task>} 里写的名字:从快捷工具进来是
     * 快捷工具名,从 {@code command} 进来是"组 动作"(如 {@code kaleidoscope cook})。模型看到的就是它刚才调的那个东西。
     */
    public String taskName() {
        return toolName.equals(action.toolName()) ? toolName : action.label();
    }

    /** 模型那次 {@code tool_call} 的 id,要跟着结果回去。 */
    public String toolCallId() {
        return toolCallId;
    }

    /** 调用进来时的 JSON 参数,原样。 */
    public JsonObject args() {
        return args;
    }

    /** 送回这次调用的结果;主人为它点过头的,消息末尾交代那一句(和任务回执交代主人允许的是同一种写法)。 */
    @Override
    public void reply(String resultJson) {
        if (allowance == null) {
            reply.accept(resultJson);
            return;
        }
        JsonObject result = JsonParser.parseString(resultJson).getAsJsonObject();
        result.addProperty("message", result.get("message").getAsString() + " " + allowance + ".");
        reply.accept(result.toString());
    }
}
