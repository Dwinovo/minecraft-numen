package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.task.SurvivalConfig;
import com.dwinovo.numen.core.task.TaskChain;
import com.dwinovo.numen.core.task.survival.SurvivalDecisions;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.tags.FluidTags;

/**
 * Autonomous surface-for-air survival chain — the player-body equivalent of the
 * float instinct every vanilla Mob gets for free. A fake player has no client
 * holding the jump key: navigation strokes it afloat only while a move is being
 * executed, so a body left idle in deep water (a task that ended mid-swim, an
 * owner Stop, plain wandering) sinks, runs out of air, and drowns. This chain
 * polls head-submersion + air supply each tick; once air dips past
 * {@link SurvivalDecisions#LOW_AIR_TICKS} it takes the body, swims straight up
 * until the head clears the water, then goes dormant — the wake/refill band
 * gives an idle body in deep water a natural bob cycle instead of a grave.
 *
 * <p>Straight-up is deliberately the whole strategy: it rescues the open-water
 * cases (the ones that actually kill). Under a sealed ceiling it still swims up
 * best-effort and diaries the near-miss; finding an air pocket is a navigation
 * problem the cognition layer can be asked to solve, not a reflex.
 *
 * <p>GATED OFF by default via {@link SurvivalConfig}, like every survival chain.
 */
public final class BreathChain implements TaskChain, com.dwinovo.numen.core.task.reflex.Reflex {

    /** BodyLog for completed episodes — dual-rail routed (may be null in unit tests). */
    private final com.dwinovo.numen.core.task.BodyLog bodyLog;
    /** Lowest air seen during the current episode (drives the one diary line). */
    private int worstAir = Integer.MAX_VALUE;
    private boolean episodeActive;

    public BreathChain() {
        this(null);
    }

    public BreathChain(com.dwinovo.numen.core.task.BodyLog bodyLog) {
        this.bodyLog = bodyLog;
    }

    @Override
    public float getPriority(NumenPlayer companion) {
        if (!SurvivalConfig.enabled()) return Float.NEGATIVE_INFINITY;
        if (!com.dwinovo.numen.core.task.reflex.ReflexRegistry.enabled(id())) {
            return SurvivalDecisions.DORMANT;   // reflex switched off by the owner
        }
        float p = SurvivalDecisions.breathPriority(
                companion.isEyeInFluid(FluidTags.WATER), companion.getAirSupply());
        if (p == SurvivalDecisions.DORMANT && episodeActive) {
            noteEpisode(companion);   // head just cleared the water — close the episode
        }
        return p;
    }

    @Override
    public void tick(NumenPlayer companion) {
        episodeActive = true;
        worstAir = Math.min(worstAir, companion.getAirSupply());
        // Drop everything and stroke straight up — no horizontal drift, no sprint.
        InputDriver.halt(companion);
        companion.setShiftKeyDown(false);
        InputDriver.jump(companion);   // in water this is the per-tick swim-up stroke
    }

    /** One diary line per near-drowning, stamped with how close it got (in seconds of air left). */
    private void noteEpisode(NumenPlayer companion) {
        episodeActive = false;
        int worst = worstAir;
        worstAir = Integer.MAX_VALUE;
        if (bodyLog == null) return;
        bodyLog.report("nearly drowned (" + Math.max(0, worst / 20) + "s of air left) — swam up for a breath");
    }

    @Override
    public void onInterrupt(NumenPlayer companion) {
        // No cross-tick body state to unwind; the episode bookkeeping closes on the
        // next dormant read (or is superseded by a fresh dip).
    }

    @Override
    public String name() {
        return "breath";
    }

    // ---- Reflex roster paperwork (constitution §6) ----

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "在水里快憋不住气时会自己浮上来换气";
    }
}
