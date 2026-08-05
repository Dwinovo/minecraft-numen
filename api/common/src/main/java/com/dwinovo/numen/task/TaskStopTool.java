package com.dwinovo.numen.task;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 控制工具(当场返回):撤掉一件我派出去的东西。
 *
 * <p>两条道共用这一个口:不带 id 停身体上那件活;带 id 则身体槽和挂着的表都查。
 * 「停掉它」跟 {@link TaskStatusTool} 的「有什么在跑」对称,各只有一处。
 *
 * <p>找不到 id 时回执<b>列出现在有什么</b>——失败的回执得让模型看得出下一步该怎么改,
 * 而不是只说一句没找到。
 */
public final class TaskStopTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private record Args(String task_id) {}

    @Override
    public String name() {
        return "task_stop";
    }

    @Override
    public String description() {
        return "Cancel something you dispatched. With no id: aborts the background task (the one "
                + "<current_task> / task_status shows) so the body frees up; its wind-down arrives as "
                + "a task_finished event with status=stopped. With an id: cancels that task or that "
                + "timer (tm...). Fails, listing what is actually pending, when nothing matches.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("task_id", "What to cancel: a task id (e.g. t42) or a timer id "
                        + "(e.g. tm3). Omit to stop the background task, whatever it is.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        String wanted = a == null || a.task_id() == null || a.task_id().isBlank() ? null : a.task_id().strip();

        MinecraftServer server = companion.level().getServer();
        long now = companion.level().getGameTime();
        TaskRecord active = CompanionTickDispatcher.currentTaskFor(companion.getUUID());

        // 指名道姓的表:先在表里找,找到就撤。
        if (wanted != null && server != null) {
            TimerRegistry registry = TimerRegistry.get(server);
            if (registry.cancel(companion.getUUID(), wanted)) {
                reply.accept(TaskResult.ok("已撤掉表 " + wanted + "。",
                        Map.of("timer_id", wanted)).toJson());
                return;
            }
        }

        if (active == null || (wanted != null && !wanted.equals(active.publicId()))) {
            reply.accept(TaskResult.fail(nothingMatched(wanted, active, server, companion, now)).toJson());
            return;
        }

        CompanionTickDispatcher.stopActive(companion, "stopped by task_stop");
        reply.accept(TaskResult.ok("已叫停 " + active.publicId() + "(" + active.describe()
                + ")。收尾结果会以 task_finished(status=stopped) 事件送达。",
                Map.of("task_id", active.publicId())).toJson());
    }

    /** 没撤成的时候把现状摊开:身体在干嘛、挂着哪些表。 */
    private static String nothingMatched(String wanted, TaskRecord active, MinecraftServer server,
                                         NumenPlayer companion, long now) {
        List<TimerRegistry.Timer> timers = server == null
                ? List.of()
                : TimerRegistry.get(server).list(companion.getUUID());
        String body = active == null
                ? "身体空闲"
                : "身体在跑 " + active.publicId() + "(" + active.describe() + ")";
        String pending = body + ";表:" + SetTimerTool.summarize(timers, now);
        return wanted == null
                ? "没有进行中的后台任务,不需要叫停。当前:" + pending + "。"
                : "没有 " + wanted + " 这个 id。当前:" + pending + "。";
    }
}
