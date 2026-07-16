package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 工具的全部契约,一个接口没有第二个基类。绝大多数工具是<b>身体工具</b>
 * (动同伴的身体或读它的世界),那就是默认形态:什么都不覆写,调用自动
 * 发往服务端活体,你只实现 {@link #onServerCall}——当场回结果(查询),
 * 或经 {@code TaskDispatch.enqueue}/{@code dispatchAsync} 交给任务队列。
 *
 * <p>少数工具不走身体(纯客户端逻辑如 todowrite,或自带协议调外部服务),
 * 覆写 {@link #invoke(ToolCall)} 自便——引擎只认 invoke,对工具怎么干活
 * 保持全盲。
 */
public interface NumenTool {

    /** Tool name as the LLM sees it. {@code snake_case}. */
    String name();

    /**
     * Description shown to the LLM — the single biggest lever on whether the model
     * picks this tool correctly. Cover what it does, WHEN to use it (and when not),
     * what each non-obvious parameter means, and any caveat.
     */
    String description();

    /** JSON Schema (OpenAI tool-parameter dialect) for the tool's arguments. */
    Map<String, Object> parameterSchema();

    /**
     * Run this tool for one call — the engine's ONLY entry point. 默认实现是
     * 身体工具的运输:把调用发往服务端并停靠,等 {@link #onServerCall} 的
     * 结果回家。不走身体的工具覆写它,想怎么干怎么干(当场完成、去异步、
     * 发自己的包),最后经 {@link ToolCall} 报结果。
     */
    default void invoke(ToolCall call) {
        ServerToolTransport.ship(call);
    }

    /**
     * 身体工具在服务端的入口,拿到活体与回信口。当场回(查询),或建
     * {@code TaskRecord} 交 {@code TaskDispatch.enqueue}(同步短活)/
     * {@code dispatchAsync}(异步长跑)。只有覆写了 {@link #invoke} 的
     * 非身体工具可以不管它——默认实现兜底出一条清晰的失败。
     */
    default void onServerCall(String toolCallId, JsonObject args,
                             NumenPlayer companion, Consumer<String> reply) {
        reply.accept(TaskResult.fail(
                "tool '" + name() + "' has no server-side body implementation").toJson());
    }
}
