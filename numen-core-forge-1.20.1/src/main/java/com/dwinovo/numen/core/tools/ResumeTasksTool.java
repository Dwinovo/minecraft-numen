package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.task.CompanionTickDispatcher;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Resumes a task queue paused by {@link PauseTasksTool}. */
public final class ResumeTasksTool extends ServerNumenTool {

    @Override
    public String name() {
        return "resume_tasks";
    }

    @Override
    public String description() {
        return "Resume a task queue previously paused with pause_tasks. If a movement task was active, it refreshes its navigation state before continuing.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        boolean changed = CompanionTickDispatcher.resumeFor(companion);
        reply.accept(TaskResult.ok(changed ? "task queue resumed" : "task queue was not paused",
                Map.of("paused", false, "changed", changed)).toJson());
    }
}
