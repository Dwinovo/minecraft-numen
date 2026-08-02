package com.dwinovo.numen.task;

import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Control tool (instant): abort the background task so the body frees up. */
public final class TaskStopTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private record Args(String task_id) {}

    @Override
    public String name() {
        return "task_stop";
    }

    @Override
    public String description() {
        return "Abort the background task (the one <current_task> / task_status shows) so the body "
                + "frees up for something else. The wind-down summary arrives as a task_finished "
                + "event with status=stopped. Fails when the body is already idle.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("task_id", "Optional safety check: the id you mean to stop (e.g. t42). "
                        + "If it doesn't match the running task, nothing is stopped.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        TaskRecord active = CompanionTickDispatcher.asyncTaskFor(companion.getUUID());
        if (active == null) {
            reply.accept(TaskResult.fail("没有进行中的后台任务,不需要叫停。").toJson());
            return;
        }
        if (a != null && a.task_id() != null && !a.task_id().isBlank()
                && !a.task_id().equals(active.publicId())) {
            reply.accept(TaskResult.fail("id 不符:当前在跑的是 " + active.publicId()
                    + "(" + active.describe() + "),没有 " + a.task_id() + "。").toJson());
            return;
        }
        CompanionTickDispatcher.stopActive(companion, "stopped by task_stop");
        reply.accept(TaskResult.ok("已叫停 " + active.publicId() + "(" + active.describe()
                + ")。收尾结果会以 task_finished(status=stopped) 事件送达。",
                Map.of("task_id", active.publicId())).toJson());
    }
}
