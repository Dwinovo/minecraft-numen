package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.wake.ScheduledWakeRegistry;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.function.Consumer;

/** Inspect this companion's durable wake leases. */
public final class ListScheduledWakesTool implements NumenTool {

    @Override
    public String name() {
        return "list_scheduled_wakes";
    }

    @Override
    public String description() {
        return "List your pending one-shot scheduled wakes, including wake_id, remaining game-time "
                + "seconds, and reason. This is a query and does not occupy the body.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args,
                             NumenPlayer companion, Consumer<String> reply) {
        MinecraftServer server = companion.level().getServer();
        if (server == null) {
            reply.accept("{\"success\":false,\"message\":\"server unavailable\"}");
            return;
        }
        long now = server.overworld().getGameTime();
        var entries = ScheduledWakeRegistry.get(server).list(companion.getUUID());
        JsonArray wakes = new JsonArray();
        for (ScheduledWakeRegistry.Entry entry : entries) {
            JsonObject wake = new JsonObject();
            wake.addProperty("wake_id", entry.id().toString());
            wake.addProperty("remaining_seconds", ScheduledWakeRegistry.remainingSeconds(entry, now));
            wake.addProperty("due_game_time", entry.dueGameTime());
            wake.addProperty("reason", entry.reason());
            wakes.add(wake);
        }
        JsonObject data = new JsonObject();
        data.addProperty("count", entries.size());
        data.add("wakes", wakes);
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("message", entries.isEmpty()
                ? "no scheduled wakes" : "pending scheduled wakes");
        result.add("data", data);
        reply.accept(result.toString());
    }
}
