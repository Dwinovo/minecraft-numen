package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.net.TaskResultPayload;
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

    /** Active (base priority) iff there's a running task or a pending one; else dormant. */
    @Override
    public float getPriority(NumenPlayer companion) {
        return (record != null || queue.hasPending()) ? LLM_BASE_PRIORITY : Float.NEGATIVE_INFINITY;
    }

    @Override
    public void tick(NumenPlayer player) {
        // 1) idle → pick up the queue head and start it (may go terminal in start()).
        if (record == null) {
            TaskRecord rec = queue.pollHead();
            if (rec != null) {
                rec.setState(TaskState.RUNNING);
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
     * The running task was preempted this tick: push its deadline one tick later so
     * the ticks it spends paused don't count against its budget (no false TIMEOUT
     * while a survival chain holds the body). Uses the existing freeze-aware
     * {@code extendDeadlineTo}, which only ever moves the deadline later.
     */
    void freezeTick(NumenPlayer companion) {
        if (record != null && record.getState() == TaskState.RUNNING) {
            record.extendDeadlineTo(record.getDeadlineGameTime() + 1);
        }
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
            String json = result == null
                    ? "{\"success\":false,\"message\":\"no result produced\"}"
                    : result.toJson();
            Services.NETWORK.sendToPlayer(owner,
                    new TaskResultPayload(player.getUUID(), rec.getToolCallId(), json));
        }
    }

    // ---- lifecycle finalizers (called by CompanionTickDispatcher via CompanionLifecycle) ----

    /** Death: drop the running task WITHOUT a result — the client's death payload already resolved the call. */
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
