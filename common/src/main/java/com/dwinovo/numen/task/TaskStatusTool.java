package com.dwinovo.numen.task;

import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Query tool (instant): read the state of the background task, if any. */
public final class TaskStatusTool implements NumenTool {

    @Override
    public String name() {
        return "task_status";
    }

    @Override
    public String description() {
        return "Read the background task's live state: id, what it is, running/queued, elapsed time "
                + "and remaining time budget. Instant; says so when the body is idle. Normally you "
                + "don't need this — completion arrives by itself as a task_finished event; use it "
                + "when the owner asks how it's going, or before deciding to task_stop.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object().build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        TaskRecord rec = CompanionTickDispatcher.asyncTaskFor(companion.getUUID());
        if (rec == null) {
            reply.accept(TaskResult.ok("身体空闲,没有后台任务。").toJson());
            return;
        }
        long now = companion.level().getGameTime();
        long elapsedS = rec.getStartedGameTime() >= 0 ? (now - rec.getStartedGameTime()) / 20 : 0;
        long budgetLeftS = Math.max(0, rec.getDeadlineGameTime() - now) / 20;
        String state = rec.getState() == TaskState.RUNNING ? "running" : "queued";
        reply.accept(TaskResult.ok(
                rec.publicId() + "(" + rec.describe() + ") " + state
                        + ",已进行 " + elapsedS + "s,时间预算剩 " + budgetLeftS + "s。",
                Map.of("task_id", rec.publicId(),
                        "task", rec.getToolName(),
                        "state", state,
                        "elapsed_s", elapsedS,
                        "budget_left_s", budgetLeftS)).toJson());
    }
}
