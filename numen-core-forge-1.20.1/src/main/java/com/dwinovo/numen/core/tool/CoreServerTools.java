package com.dwinovo.numen.core.tool;

import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.core.net.CancelTasksPayload;
import com.dwinovo.numen.core.net.ExecuteToolPayload;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.client.agent.AutonomyMemory;
import com.google.gson.Gson;
import net.minecraft.client.Minecraft;

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
public final class CoreServerTools {

    private record CallKey(UUID companionUuid, String toolCallId) {}
    private static final Map<CallKey, ToolCall> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Gson GSON = new Gson();

    private CoreServerTools() {}

    /** Ship a body-bound tool to the server and park its call until the result returns. */
    public static void ship(ToolCall call) {
        UUID entity = call.ctx().entityUuid();
        IN_FLIGHT.put(new CallKey(entity, call.id()), call);
        Services.NETWORK.sendToServer(ExecuteToolPayload.ID,
                new ExecuteToolPayload(entity, call.id(), call.toolName(), call.rawArgs(),
                        reservationsJson(entity), reconnectFlag(call)));
    }

    /** A server result came back (core's TaskResultPayload) — complete the parked call. */
    public static void deliver(UUID companionUuid, String toolCallId, String resultJson) {
        ToolCall call = IN_FLIGHT.remove(new CallKey(companionUuid, toolCallId));
        if (call != null) call.complete(resultJson);
    }

    /** Owner interrupted: forget this companion's parked calls and tell the body to stop. */
    public static void abort(UUID companionUuid) {
        IN_FLIGHT.keySet().removeIf(k -> companionUuid.equals(k.companionUuid()));
        Services.NETWORK.sendToServer(CancelTasksPayload.ID, new CancelTasksPayload(companionUuid));
    }

    /** compileJava may still see the previous API jar; final API jars expose reconnect(). */
    private static boolean reconnectFlag(ToolCall call) {
        try {
            return Boolean.TRUE.equals(call.getClass().getMethod("reconnect").invoke(call));
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static String reservationsJson(UUID entityUuid) {
        try {
            var directory = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config").resolve("numen").resolve("memory");
            return GSON.toJson(AutonomyMemory.forEntity(directory, entityUuid).reservations());
        } catch (RuntimeException error) {
            return "[]";
        }
    }
}
