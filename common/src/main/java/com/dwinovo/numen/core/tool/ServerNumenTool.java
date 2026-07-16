package com.dwinovo.numen.core.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.core.task.CompanionTickDispatcher;
import com.dwinovo.numen.core.task.TaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.function.Consumer;

/**
 * Convenience base for a numen-core tool that runs on the companion body
 * (server-side). This is <em>optional sugar that lives in core</em>, not part of
 * the engine — the engine's only contract is the raw {@link NumenTool}.
 *
 * <p>It wires the one bit of boilerplate every body-bound tool shares:
 * {@link #invoke} ships the call to the server through core's own transport
 * ({@link CoreServerTools#ship}) and parks it; core's {@code ExecuteToolPayload}
 * handler then calls {@link #runOnServer} on the live body. There the tool does
 * whatever it wants — reply immediately (a query) or enqueue a task and let the
 * task lifecycle reply later (a world action).
 *
 * <p>A tool that wants something this doesn't fit just implements
 * {@link NumenTool} directly and sends its own packets.
 */
public abstract class ServerNumenTool implements NumenTool {

    @Override
    public final void invoke(ToolCall call) {
        CoreServerTools.ship(call);   // client side: ship to the body, park the call until the result returns
    }

    /** Runs on the server with the live companion body. Reply now, or enqueue a task and reply later. */
    public abstract void runOnServer(String toolCallId, JsonObject args,
                                     NumenPlayer companion, Consumer<String> reply);

    /** Helper for world-action tools: a ToolContext carrying the call id + the body's current game time. */
    protected static ToolContext ctx(String toolCallId, NumenPlayer companion) {
        return new ToolContext(toolCallId, companion.level().getGameTime());
    }

    /**
     * Helper for SYNC world-action tools: hand a built task record to the companion's
     * queue. 身体被异步任务占着时直接拒绝——同步任务排在几分钟的长活后面,等于把
     * 当前回合(和串行的工具派发器)整个卡死;拒绝话术把选择权丢回给 LLM。
     */
    protected static void enqueue(NumenPlayer companion, TaskRecord record, Consumer<String> reply) {
        TaskRecord busy = CompanionTickDispatcher.asyncTaskFor(companion.getUUID());
        if (busy != null) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail(busyMessage(busy)).toJson());
            return;
        }
        CompanionTickDispatcher.queueFor(companion.getUUID()).enqueue(record);
    }

    /**
     * Helper for ASYNC (long-running) tools: 受理即回执 task_id,身体后台执行,
     * 收尾经 task_finished 事件送达(事件登记处定档)。一次只受理一件——车道上
     * 有任何工作(同步在跑/异步在跑或排队)都拒绝。
     */
    protected static void dispatchAsync(NumenPlayer companion, TaskRecord record, Consumer<String> reply) {
        if (CompanionTickDispatcher.llmLaneBusy(companion.getUUID())) {
            TaskRecord busy = CompanionTickDispatcher.asyncTaskFor(companion.getUUID());
            reply.accept(com.dwinovo.numen.task.TaskResult.fail(busy != null
                    ? busyMessage(busy)
                    : "身体正在收尾上一个任务,稍候再派。").toJson());
            return;
        }
        record.markAsync();
        CompanionTickDispatcher.queueFor(companion.getUUID()).enqueue(record);
        reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                "已受理,后台执行中。完成会自动收到 task_finished 事件,不要轮询;"
                        + "task_status 查进度,task_stop 叫停。",
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

