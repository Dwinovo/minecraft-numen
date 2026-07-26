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
import java.util.function.Consumer;

/** Register a durable, non-body-blocking, one-shot agent wake. */
public final class ScheduleWakeTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final int MAX_SECONDS = 86_400;
    private static final int MAX_REASON_LENGTH = 400;

    private record Args(int after_seconds, String reason) {}

    @Override
    public String name() {
        return "schedule_wake";
    }

    @Override
    public String description() {
        return "Schedule one future reasoning turn after a delay in active server game time. "
                + "Returns immediately and NEVER occupies the body or waits for a world action. "
                + "Use after starting a time-dependent process (for example smelting) when you "
                + "need to come back and inspect it later. The timer only reminds you: it does "
                + "not prove that the process completed, so re-check the world when the "
                + "scheduled_wake event arrives. One-shot, max 24 hours; use "
                + "list_scheduled_wakes/cancel_scheduled_wake to manage durable timers.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("after_seconds", "Delay in active server seconds (1-86400).", 1, MAX_SECONDS)
                .string("reason", "Specific state to inspect or decision to resume when awakened.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args,
                             NumenPlayer companion, Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        if (parsed.after_seconds() < 1 || parsed.after_seconds() > MAX_SECONDS) {
            reply.accept(TaskResult.fail("after_seconds must be between 1 and " + MAX_SECONDS).toJson());
            return;
        }
        String reason = parsed.reason() == null ? "" : parsed.reason().strip();
        if (reason.isEmpty() || reason.length() > MAX_REASON_LENGTH) {
            reply.accept(TaskResult.fail("reason must be 1-" + MAX_REASON_LENGTH + " characters").toJson());
            return;
        }
        MinecraftServer server = companion.level().getServer();
        if (server == null) {
            reply.accept(TaskResult.fail("server unavailable").toJson());
            return;
        }
        try {
            long now = server.overworld().getGameTime();
            ScheduledWakeRegistry.Entry entry = ScheduledWakeRegistry.get(server)
                    .schedule(companion.getUUID(), now, parsed.after_seconds(), reason);
            reply.accept(TaskResult.ok(
                    "scheduled a one-shot wake; continue other work or end this turn",
                    Map.of(
                            "wake_id", entry.id().toString(),
                            "after_seconds", parsed.after_seconds(),
                            "due_game_time", entry.dueGameTime())).toJson());
        } catch (IllegalStateException ex) {
            reply.accept(TaskResult.fail(ex.getMessage()
                    + "; list or cancel an existing wake first").toJson());
        }
    }
}
