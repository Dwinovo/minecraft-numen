package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.network.payload.CancelTasksPayload;
import com.dwinovo.numen.network.payload.ExecuteToolPayload;
import com.dwinovo.numen.platform.Services;

import java.util.Collection;
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

    /**
     * 主人按停止:告诉身体住手。停在这里的调用不在这里清——服务端会把被取消的活作为结果照常送回,
     * 外接模型经 {@code NumenActuator} 挂着的调用就靠这条结果收尾;内脑放弃的调用由它自己按 id
     * {@link #forget} 掉。
     */
    public static void abort(UUID companionUuid) {
        Services.NETWORK.sendToServer(new CancelTasksPayload(companionUuid));
    }

    /**
     * 忘掉这几个调用,<b>不动身体</b>。调用方放弃了它们(打断、死亡、登出),结果回来也没人要了;
     * 只按 id 清,同一只同伴身上别人挂着的调用(外接模型的)不受影响。
     */
    public static void forget(Collection<String> callIds) {
        for (String id : callIds) {
            IN_FLIGHT.remove(id);
        }
    }
}
