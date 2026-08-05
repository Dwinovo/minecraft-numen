package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.task.reflex.Reflex;
import com.dwinovo.numen.entity.InputDriver;

import com.dwinovo.numen.core.task.SurvivalConfig;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.core.task.survival.SurvivalDecisions;
import com.dwinovo.numen.core.task.survival.UnstuckDetector;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Autonomous positional-recovery survival chain. Each tick it samples the body's
 * horizontal position and whether the body was trying to move (nonzero locomotion
 * input); when a full rolling window shows "kept pushing, never moved" — the
 * signature of a body wedged against geometry — it spikes above the LLM task and
 * drives a short break-out burst (turn to a new heading, walk forward, hop), then
 * drops back. Conservative by construction: an idle body (no locomotion input) is
 * never counted as stuck, so it will not wake during legitimate idle.
 *
 * <p>The break-out is a bounded, best-effort wander driven straight through
 * {@link InputDriver} (no nav, no dig plan) — rough but safe: it is capped at
 * {@link #WANDER_TICKS} and never travels far. The pure detection logic lives in
 * {@link UnstuckDetector} so it is unit-tested headless.
 *
 * <p>GATED OFF by default via {@link SurvivalConfig}: the gate check precedes the
 * position sampling, so with the gate off nothing is recorded and the chain is a
 * strict no-op.
 */
public final class UnstuckChain implements Task, com.dwinovo.numen.task.reflex.Reflex {

    /** Rolling window length (ticks) and the disc radius (blocks) that counts as "not moving". */
    private static final int WINDOW = 40;
    private static final double MOVE_THRESHOLD = 0.75;
    /** Length of one break-out burst. */
    private static final int WANDER_TICKS = 30;

    private final UnstuckDetector detector = new UnstuckDetector(WINDOW, MOVE_THRESHOLD);
    private int wanderTicksLeft;
    private float wanderYaw;

    @Override
    public boolean canRun(NumenPlayer companion) {
        if (!SurvivalConfig.enabled()) return false;
        if (!com.dwinovo.numen.task.reflex.ReflexRegistry.enabled(id())) {
            return false;   // reflex switched off by the owner
        }
        Vec3 pos = companion.position();
        boolean tryingToMove = companion.zza != 0.0f || companion.xxa != 0.0f;
        detector.record(pos.x, pos.z, tryingToMove);

        if (wanderTicksLeft > 0) return true;   // finish the burst
        return detector.isStuck();
    }

    @Override
    public TaskState tick(NumenPlayer companion) {
        if (wanderTicksLeft <= 0) {
            // Begin a fresh break-out: pick a new heading (turn ~137° off current so
            // repeated attempts fan out) and clear the window so we re-evaluate after.
            wanderTicksLeft = WANDER_TICKS;
            wanderYaw = companion.getYRot() + 137.0f;
            detector.reset();
        }
        driveWander(companion);
        if (--wanderTicksLeft <= 0) {
            InputDriver.halt(companion);
        }
        return TaskState.RUNNING;
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        InputDriver.halt(companion);
        companion.setShiftKeyDown(false);
        wanderTicksLeft = 0;
        detector.reset();
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

    /** Face the chosen heading, push forward, and hop periodically to clear a lip/step. */
    private void driveWander(NumenPlayer companion) {
        companion.setYRot(wanderYaw);
        companion.setYHeadRot(wanderYaw);
        companion.zza = 1.0f;
        companion.xxa = 0.0f;
        companion.setSprinting(false);
        if (wanderTicksLeft % 5 == 0) {
            InputDriver.jump(companion);
        }
    }
}
