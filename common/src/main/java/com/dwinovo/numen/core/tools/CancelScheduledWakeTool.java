package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.wake.ScheduledWakeRegistry;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Revoke one pending wake lease owned by this companion. */
public final class CancelScheduledWakeTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private record Args(String wake_id) {}

    @Override
    public String name() {
        return "cancel_scheduled_wake";
    }

    @Override
    public String description() {
        return "Cancel one of your pending scheduled wakes by wake_id. Use "
                + "list_scheduled_wakes first when the id is unknown. Does not occupy the body.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("wake_id", "UUID returned by schedule_wake or list_scheduled_wakes.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args,
                             NumenPlayer companion, Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        UUID id;
        try {
            id = UUID.fromString(parsed.wake_id());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("wake_id must be a valid UUID").toJson());
            return;
        }
        MinecraftServer server = companion.level().getServer();
        if (server == null) {
            reply.accept(TaskResult.fail("server unavailable").toJson());
            return;
        }
        boolean cancelled = ScheduledWakeRegistry.get(server).cancel(companion.getUUID(), id);
        reply.accept((cancelled
                ? TaskResult.ok("cancelled scheduled wake", Map.of("wake_id", id.toString()))
                : TaskResult.fail("scheduled wake not found for this companion",
                        Map.of("wake_id", id.toString()))).toJson());
    }
}
