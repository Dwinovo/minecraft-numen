package com.dwinovo.numen.task;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 查询工具(当场返回):我派出去的东西现在都怎么样了。
 *
 * <p>两条道各报一段——身体在做的那件活({@link TaskDispatch#setTask}),以及挂着的表
 * ({@link TimerRegistry})。两者都答在这一处:「我有什么在跑」只该有一个问法,
 * 否则模型还得记住哪一种去哪问。
 */
public final class TaskStatusTool implements NumenTool {

    @Override
    public String name() {
        return "task_status";
    }

    @Override
    public String description() {
        return "Read what you have in flight: the background task (id, what it is, running/queued, "
                + "elapsed time and remaining budget) and your pending timers (id, seconds left, "
                + "reason). Instant. Normally you don't need it — a task announces its own end as a "
                + "task_finished event and a timer fires on its own; use it when the owner asks how "
                + "things are going, or before deciding what to task_stop.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object().build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        long now = companion.level().getGameTime();
        TaskRecord rec = CompanionTickDispatcher.currentTaskFor(companion.getUUID());
        MinecraftServer server = companion.level().getServer();
        List<TimerRegistry.Timer> timers = server == null
                ? List.of()
                : TimerRegistry.get(server).list(companion.getUUID());

        Map<String, Object> data = new LinkedHashMap<>();
        StringBuilder msg = new StringBuilder();

        if (rec == null) {
            msg.append("身体空闲,没有后台任务。");
        } else {
            long elapsedS = rec.getStartedGameTime() >= 0 ? (now - rec.getStartedGameTime()) / 20 : 0;
            long budgetLeftS = Math.max(0, rec.getDeadlineGameTime() - now) / 20;
            String state = rec.getState() == TaskState.RUNNING ? "running" : "queued";
            msg.append(rec.publicId()).append('(').append(rec.describe()).append(") ").append(state)
                    .append(",已进行 ").append(elapsedS).append("s,时间预算剩 ")
                    .append(budgetLeftS).append("s。");
            data.put("task_id", rec.publicId());
            data.put("task", rec.getToolName());
            data.put("state", state);
            data.put("elapsed_s", elapsedS);
            data.put("budget_left_s", budgetLeftS);
        }

        if (timers.isEmpty()) {
            msg.append("没有挂着的表。");
        } else {
            msg.append("挂着 ").append(timers.size()).append(" 个表:")
                    .append(SetTimerTool.summarize(timers, now)).append('。');
            data.put("timers", SetTimerTool.describe(timers, now));
        }

        reply.accept(TaskResult.ok(msg.toString(), data).toJson());
    }
}
