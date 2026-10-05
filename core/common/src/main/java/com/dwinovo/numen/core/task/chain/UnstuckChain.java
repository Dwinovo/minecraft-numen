package com.dwinovo.numen.core.task.chain;


import com.dwinovo.numen.core.nav.Trip;
import com.dwinovo.numen.pathing.body.Controls;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.entity.NumenPlayer;

/**
 * Autonomous positional-recovery survival chain. It wakes when the walk she is on says it is not
 * progressing ({@link Trip#progressing}: for a second the body neither moved nor got any work done, nor was it
 * waiting for a route) — the signature of a body wedged against geometry while the route keeps pushing — spikes
 * above the LLM task and drives a short break-out burst (turn to a new heading, walk forward, hop), then drops
 * back. An idle body is never on a walk, so it will not wake during legitimate idle; whether she is stuck is judged
 * in the pathing module alone, this chain only reacts.
 *
 * <p>The break-out is a bounded, best-effort wander driven straight through her keys
 * ({@link NumenPlayer#controls}; no nav, no dig plan) — rough but safe: it is capped at
 * {@link #WANDER_TICKS} and never travels far. After a burst it stays quiet for {@link #REARM_TICKS} so the walk
 * gets to try again from where she ended up before it is judged once more.
 */
public final class UnstuckChain implements Task, com.dwinovo.numen.task.reflex.Reflex {

    /** Length of one break-out burst. */
    private static final int WANDER_TICKS = 30;
    /** 一阵挣脱之后,这么多刻不再醒:让路线从她挣到的地方接着走,走不动了它会再说。 */
    private static final int REARM_TICKS = 40;

    private int wanderTicksLeft;
    private float wanderYaw;
    private int rearm;

    @Override
    public boolean canRun(NumenPlayer companion) {
        if (wanderTicksLeft > 0) return true;   // finish the burst
        if (rearm > 0) {
            rearm--;
            return false;
        }
        Trip trip = Trip.current(companion);
        return trip != null && !trip.progressing();
    }

    @Override
    public TaskState tick(NumenPlayer companion) {
        if (wanderTicksLeft <= 0) {
            // Begin a fresh break-out: pick a new heading (turn ~137° off current so
            // repeated attempts fan out).
            wanderTicksLeft = WANDER_TICKS;
            wanderYaw = companion.getYRot() + 137.0f;
        }
        driveWander(companion);
        if (--wanderTicksLeft <= 0) {
            companion.controls().stop();
            rearm = REARM_TICKS;
        }
        return TaskState.RUNNING;
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        companion.controls().releaseAll();
        wanderTicksLeft = 0;
    }

    @Override
    public String name() {
        return "unstuck";
    }

    // ---- Reflex roster paperwork (constitution §6) ----

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "被地形卡住时会自己挣脱出来";
    }

    /** Face the chosen heading, hold forward, and hop every fifth tick to clear a lip/step. */
    private void driveWander(NumenPlayer companion) {
        companion.setYRot(wanderYaw);
        companion.setYHeadRot(wanderYaw);
        Controls keys = companion.controls();
        keys.press(Controls.Key.FORWARD);
        keys.release(Controls.Key.SPRINT);
        keys.set(Controls.Key.JUMP, wanderTicksLeft % 5 == 0);
    }
}
