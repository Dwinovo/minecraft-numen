package com.dwinovo.numen.task;

import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.entity.NumenPlayer;

import java.util.function.Consumer;

/**
 * 身体工具在 {@code onServerCall} 里用的三个静态帮手(建议 static import,
 * 调用点保持裸名):{@link #ctx} 造任务上下文,{@link #enqueue} 同步排队,
 * {@link #dispatchAsync} 异步受理。收口在此的还有"一具身体一件活"的占用
 * 闸门与拒绝话术。
 */
public final class TaskDispatch {

    private TaskDispatch() {}

    /** 任务上下文:调用 id + 身体当前游戏刻(deadline 的起点)。 */
    public static ToolContext ctx(String toolCallId, NumenPlayer companion) {
        return new ToolContext(toolCallId, companion.level().getGameTime());
    }

    /**
     * SYNC world-action tools:hand a built task record to the companion's
     * queue. 身体被异步任务占着时直接拒绝——同步任务排在几分钟的长活后面,
     * 等于把当前回合(和串行的工具派发器)整个卡死;拒绝话术把选择权丢回给 LLM。
     */
    public static void enqueue(NumenPlayer companion, TaskRecord record, Consumer<String> reply) {
        TaskRecord busy = CompanionTickDispatcher.asyncTaskFor(companion.getUUID());
        if (busy != null) {
            reply.accept(TaskResult.fail(busyMessage(busy)).toJson());
            return;
        }
        CompanionTickDispatcher.queueFor(companion.getUUID()).enqueue(record);
    }

    /**
     * ASYNC (long-running) tools:受理即回执 task_id,身体后台执行,收尾经
     * task_finished 事件送达。一次只受理一件——车道上有任何工作(同步在跑/
     * 异步在跑或排队)都拒绝。
     */
    public static void dispatchAsync(NumenPlayer companion, TaskRecord record, Consumer<String> reply) {
        if (CompanionTickDispatcher.llmLaneBusy(companion.getUUID())) {
            TaskRecord busy = CompanionTickDispatcher.asyncTaskFor(companion.getUUID());
            reply.accept(TaskResult.fail(busy != null
                    ? busyMessage(busy)
                    : "身体正在收尾上一个任务,稍候再派。").toJson());
            return;
        }
        record.markAsync();
        CompanionTickDispatcher.queueFor(companion.getUUID()).enqueue(record);
        // 内置大脑靠 task_finished 事件收尾(别轮询);外部(MCP)夺舍收不到事件(那条投给内置大脑,
        // 不是它),得自己轮询 task_status 到身体空闲,再感知确认。
        String note = record.isExternalCall()
                ? "已受理,后台执行中。用 task_status 轮询,身体转空闲即为完成,再用感知工具确认结果;task_stop 叫停。"
                : "已受理,后台执行中。完成会自动收到 task_finished 事件,不要轮询;task_status 查进度,task_stop 叫停。";
        reply.accept(TaskResult.ok(
                note,
                java.util.Map.of(
                        "task_id", record.publicId(),
                        "task", record.getToolName(),
                        "async", true)).toJson());
    }

    private static String busyMessage(TaskRecord busy) {
        return "身体正忙: " + busy.publicId() + "(" + busy.describe()
                + ") 后台进行中。先 task_stop 叫停,或等它的 task_finished 事件再派新活。";
    }
}
