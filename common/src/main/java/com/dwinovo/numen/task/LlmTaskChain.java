package com.dwinovo.numen.task;

import com.dwinovo.numen.network.payload.TaskResultPayload;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * The base-priority chain that runs the owner's LLM tool-call tasks — a faithful
 * lift of the old {@code CompanionTickDispatcher.tickOne}/{@code drainResults}
 * into a {@link TaskChain}, so the "exactly one {@link TaskResult} per
 * {@code toolCallId}" contract the client agent loop depends on is unchanged.
 *
 * <p>Runs at {@link TaskChain#LLM_BASE_PRIORITY}; survival chains spike above it to
 * preempt. This chain owns the running {@code task}/{@code record} pair and only
 * finalizes it (build result → complete → ship) when the record reaches terminal
 * in its OWN {@link #tick}. A preemption never finalizes, so a preempted-then-
 * resumed task still emits exactly one result; {@link #freezeTick} bumps the
 * deadline for the tick it was paused so it can't spuriously TIME OUT while a
 * survival chain holds the body.
 */
public final class LlmTaskChain implements TaskChain {

    private final TaskQueue queue;
    private CompanionTask task;
    private TaskRecord record;

    LlmTaskChain(TaskQueue queue) {
        this.queue = queue;
    }

    @Override
    public String name() {
        return "llm";
    }

    /**
     * 本链的出价依据(也是异步受理闸门的占用判定):有运行中或排队中的记录
     * 即有工作。
     */
    boolean hasWork() {
        return record != null || queue.hasPending();
    }

    /** Active (base priority) iff there's a running task or a pending one; else dormant. */
    @Override
    public float getPriority(NumenPlayer companion) {
        return hasWork() ? LLM_BASE_PRIORITY : Float.NEGATIVE_INFINITY;
    }

    @Override
    public void tick(NumenPlayer player) {
        // 1) idle → pick up the queue head and start it (may go terminal in start()).
        if (record == null) {
            TaskRecord rec = queue.pollHead();
            if (rec != null) {
                rec.setState(TaskState.RUNNING);
                rec.markStarted(player.level().getGameTime());
                task = CompanionTaskFactory.create(player, rec);
                record = rec;
                task.start();
            }
        } else if (record.getState() == TaskState.RUNNING) {
            // 2) running → deadline check, else advance one tick.
            if (player.level().getGameTime() >= record.getDeadlineGameTime()) {
                record.setState(TaskState.TIMEOUT);
            } else {
                record.setState(task.tick());
            }
        }

        // 3) terminal (from start(), tick(), deadline, or an external cancel) → finalize.
        finalizeTerminal();
    }

    /**
     * Finalize the running record if it has reached a terminal state — build its
     * result, move it to the outbox, release the slot. Split out of {@link #tick}
     * because {@link CompanionBrain} must call it EVERY tick, even while a survival
     * chain holds the body: an owner Stop marks the record CANCELLED out-of-band,
     * and the client's strictly-serial ToolDispatcher is wedged until that one
     * result ships — finalization must not wait for this chain to win again.
     */
    void finalizeTerminal() {
        if (record != null && record.getState().isTerminal()) {
            record.setResult(task.buildResult(record.getState()));
            queue.complete(record);
            task = null;
            record = null;
        }
    }

    /** Lost control to a higher-priority chain: pause the running task without tearing it down. */
    @Override
    public void onInterrupt(NumenPlayer companion) {
        if (task instanceof Suspendable s) {
            s.suspend();
        }
    }

    /**
     * The LLM lane was preempted this tick: push the running task's deadline one
     * tick later so the ticks it spends paused don't count against its budget (no
     * false TIMEOUT while a survival chain holds the body), and do the same for
     * every PENDING record still queued — their deadlines were stamped at enqueue
     * time by the tool layer, and a long survival hold must not burn an unstarted
     * task's whole budget before it even runs. Uses the existing freeze-aware
     * {@code extendDeadlineTo}, which only ever moves the deadline later.
     */
    void freezeTick(NumenPlayer companion) {
        if (record != null && record.getState() == TaskState.RUNNING) {
            record.extendDeadlineTo(record.getDeadlineGameTime() + 1);
        }
        queue.freezePendingDeadlines();
    }

    /**
     * Ship every completed record back to the owner as a {@link TaskResultPayload}.
     * Called each tick by {@link CompanionBrain} (a lift of the old
     * {@code drainResults}). Owner offline → drop (the loop re-asks).
     */
    void drainResults(NumenPlayer player) {
        List<TaskRecord> completed = queue.drainCompleted();
        if (completed.isEmpty()) return;
        ServerPlayer owner = player.resolveOwnerPlayer();
        if (owner == null) return;
        for (TaskRecord rec : completed) {
            TaskResult result = rec.getResult();
            // 异步记录:tool_call 在受理时就回执过了,收尾改走 task_finished 事件
            // (done/failed/timeout 唤醒,stopped 搭车——档位在事件登记处定)。
            if (rec.isAsync()) {
                // 外部(MCP)派的异步任务:不投 task_finished 事件——那条会唤醒并没有派它的内置大脑。
                // 外部驱动靠 task_status 轮询 + 感知确认闭环(见 TaskDispatch/NumenActuator)。
                if (rec.isExternalCall()) {
                    continue;
                }
                String status = switch (rec.getState()) {
                    case SUCCESS -> "done";
                    case TIMEOUT -> "timeout";
                    case CANCELLED -> "stopped";
                    default -> "failed";
                };
                String msg = result == null ? "no result produced" : result.message();
                com.dwinovo.numen.event.GameEvents.taskFinished(
                        player, rec.publicId(), rec.getToolName(), status, msg);
                continue;
            }
            String json = result == null
                    ? "{\"success\":false,\"message\":\"no result produced\"}"
                    : result.toJson();
            Services.NETWORK.sendToPlayer(owner,
                    new TaskResultPayload(player.getUUID(), rec.getToolCallId(), json));
        }
    }

    /** 系统里的异步记录(受理策略保证至多一个):运行中的,或还在排队的。null = 没有。 */
    TaskRecord asyncRecord() {
        if (record != null && record.isAsync()) return record;
        return queue.peekAsync();
    }

    // ---- lifecycle finalizers (called by CompanionTickDispatcher via CompanionLifecycle) ----

    /**
     * Death: drop the running task WITHOUT a result — the client's death payload
     * already resolved the call.
     */
    void dropActiveNoResult() {
        task = null;
        record = null;
    }

    /** Owner Stop: mark the running record CANCELLED; the next {@link #tick} finalizes + ships it. */
    void cancelActive() {
        if (record != null && record.getState() == TaskState.RUNNING) {
            record.setState(TaskState.CANCELLED);
        }
    }

    /**
     * Body leaving the world: finalize the running task inline (it won't be ticked
     * again) so its {@code buildResult} side-effects run and the result ships. Lift
     * of the old {@code onCompanionRemoved} body.
     */
    void finalizeActive() {
        if (record == null) return;
        TaskState st = record.getState();
        if (!st.isTerminal()) {
            st = TaskState.CANCELLED;
            record.setState(st);
        }
        record.setResult(task.buildResult(st));
        queue.complete(record);
        task = null;
        record = null;
    }
}
