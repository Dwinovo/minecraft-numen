package com.dwinovo.numen.core.task;
import com.dwinovo.numen.task.TaskResult;

/**
 * One running task on a companion {@link com.dwinovo.numen.entity.NumenPlayer}
 * body, driven by {@code CompanionTickDispatcher}. The player-body replacement
 * for the Mob's {@code LlmTaskGoal} (which was a vanilla {@code Goal} run by a
 * GoalSelector) — here the dispatcher owns the lifecycle directly:
 * {@link #start()} once, {@link #tick()} each server tick until it returns a
 * terminal {@link TaskState}, then {@link #buildResult} for the reply.
 */
public interface CompanionTask {

    /** First-tick setup. May return a terminal state immediately via the record. */
    void start();

    /** Advance one tick. Returns {@link TaskState#RUNNING} or a terminal state. */
    TaskState tick();

    /**
     * Whether the dispatcher should treat prolonged lack of position change as a stall.
     * Tasks that intentionally work in place (wait, locate, crafting scans, etc.) keep
     * the default false; movement tasks opt in explicitly.
     */
    default boolean monitorsMovementProgress() {
        return false;
    }

    /** Called when a paused task resumes so task-local game-time deadlines can be shifted. */
    default void onResume(long pausedTicks) {
    }

    /**
     * Called by the dispatcher after a no-progress stall. Return true if the task
     * reset internal state and should be given another chance, false to fail as stuck.
     */
    default boolean recoverFromStuck() {
        return false;
    }

    /** The result envelope handed back to the LLM. */
    TaskResult buildResult(TaskState finalState);
}
