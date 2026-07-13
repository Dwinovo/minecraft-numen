package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.task.CompanionTickDispatcher;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Pauses this companion's current and queued body-bound tasks without cancelling tool calls. */
public final class PauseTasksTool extends ServerNumenTool {

    @Override
    public String name() {
        return "pause_tasks";
    }

    @Override
    public String description() {
        return "Pause your current server-side task queue. The active task is frozen and no queued task starts until resume_tasks is called. Use when the owner asks to wait, change plans, or temporarily stop without losing the task state.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        boolean changed = CompanionTickDispatcher.pauseFor(companion);
        reply.accept(TaskResult.ok(changed ? "task queue paused" : "task queue was already paused",
                Map.of("paused", true, "changed", changed)).toJson());
    }
}
