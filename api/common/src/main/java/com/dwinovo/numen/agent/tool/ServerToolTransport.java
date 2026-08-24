package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.network.payload.CancelTasksPayload;
import com.dwinovo.numen.network.payload.ExecuteToolPayload;
import com.dwinovo.numen.platform.Services;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * numen-core's client-side tool transport — how a body-bound tool actually reaches
 * the server and comes back, entirely core's own packets. The engine scheduler
 * hands a {@link ToolCall} to a tool's {@code invoke}; a body-bound tool calls
 * {@link #ship} (sends core's {@link ExecuteToolPayload} and parks the call by
 * id); when core's {@code TaskResultPayload} returns, {@link #deliver} completes
 * that call. The engine knows none of this — to it the tool simply completes
 * later.
 */
public final class ServerToolTransport {

    private static final Map<String, ToolCall> IN_FLIGHT = new ConcurrentHashMap<>();

    private ServerToolTransport() {}

    /** Ship a body-bound tool to the server and park its call until the result returns. */
    public static void ship(ToolCall call) {
        UUID entity = call.ctx().entityUuid();
        IN_FLIGHT.put(call.id(), call);
        Services.NETWORK.sendToServer(
                new ExecuteToolPayload(entity, call.id(), call.toolName(), call.rawArgs()));
    }

    /** A server result came back (core's TaskResultPayload) — complete the parked call. */
    public static void deliver(String toolCallId, String resultJson) {
        ToolCall call = IN_FLIGHT.remove(toolCallId);
        if (call != null) call.complete(resultJson);
    }

    /** 主人按停止:忘掉停在这里的调用,并告诉身体住手。 */
    public static void abort(UUID companionUuid) {
        forget(companionUuid);
        Services.NETWORK.sendToServer(new CancelTasksPayload(companionUuid));
    }

    /**
     * 只忘掉停在这里的调用，<b>不动身体</b> —— 断线登出走这条。
     *
     * <p>那些调用属于一个已经结束的会话，结果再也回不来了；不忘就是一直长的账本。
     * 但身体不能叫停：她还在服务器里 tick，任务照样该跑完，收尾进离线出箱等主人回来。
     */
    public static void forget(UUID companionUuid) {
        IN_FLIGHT.values().removeIf(c -> companionUuid.equals(c.ctx().entityUuid()));
    }
}
