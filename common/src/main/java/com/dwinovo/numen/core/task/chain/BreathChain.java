package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.task.BodyLog;
import com.dwinovo.numen.task.reflex.Reflex;
import com.dwinovo.numen.entity.InputDriver;

import com.dwinovo.numen.core.task.SurvivalConfig;
import com.dwinovo.numen.task.TaskChain;
import com.dwinovo.numen.core.task.survival.SurvivalDecisions;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

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
 * <p>Straight-up handles the open-water cases. Under a sealed ceiling (frozen
 * ocean, flooded cave — the terrain that actually drowned a body while it
 * pressed uselessly against pack ice) it BFS-walks the connected water for the
 * nearest column with breathable space above and swims toward that opening,
 * still stroking upward. Only when no opening exists within the search budget
 * does it fall back to best-effort straight-up and diaries the entrapment so
 * the cognition layer hears about it while there is still air to act on.
 *
 * <p>GATED OFF by default via {@link SurvivalConfig}, like every survival chain.
 */
public final class BreathChain implements TaskChain, com.dwinovo.numen.task.reflex.Reflex {

    /** How high the straight-up column is probed before calling the ceiling sealed;
     *  deeper unbroken water than this means "open ocean, just keep rising". */
    private static final int CEILING_PROBE = 16;
    /** BFS budget over connected water cells when hunting a breathable opening. */
    private static final int AIR_SEARCH_BUDGET = 400;
    /** Horizontal cap of that hunt (per axis, blocks from the start column). */
    private static final int AIR_SEARCH_RADIUS = 16;
    /** Ticks between re-validating/re-picking the opening being swum toward. */
    private static final int RETARGET_TICKS = 20;

    /** BodyLog for completed episodes — dual-rail routed (may be null in unit tests). */
    private final com.dwinovo.numen.task.BodyLog bodyLog;
    /** Lowest air seen during the current episode (drives the one diary line). */
    private int worstAir = Integer.MAX_VALUE;
    private boolean episodeActive;
    /** Water cell with breathable space above it — the opening being swum toward
     *  while a ceiling seals the straight-up column (null = rising straight). */
    private BlockPos airColumn;
    private int retargetCooldown;
    /** One trapped-diary line per episode, written the moment the search comes up
     *  empty — while there is still air left for the cognition layer to act on. */
    private boolean trappedNoted;

    public BreathChain() {
        this(null);
    }

    public BreathChain(com.dwinovo.numen.task.BodyLog bodyLog) {
        this.bodyLog = bodyLog;
    }

    @Override
    public float getPriority(NumenPlayer companion) {
        if (!SurvivalConfig.enabled()) return Float.NEGATIVE_INFINITY;
        if (!com.dwinovo.numen.task.reflex.ReflexRegistry.enabled(id())) {
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
        InputDriver.halt(companion);
        companion.setShiftKeyDown(false);
        // Straight up is the cheap common rescue (open water). Only a sealed column
        // engages the lateral hunt: swim through connected water toward the nearest
        // opening with air above it (an ice hole, the cave mouth), still stroking up.
        if (!ceilingSealed(companion)) {
            airColumn = null;
        } else {
            if (airColumn == null || --retargetCooldown <= 0
                    || !breathableAbove(companion.level(), airColumn)) {
                airColumn = findAirColumn(companion);
                retargetCooldown = RETARGET_TICKS;
                if (airColumn == null) {
                    noteTrapped(companion);
                }
            }
            if (airColumn != null) {
                InputDriver.stepToward(companion, Vec3.atCenterOf(airColumn), false);
            }
        }
        InputDriver.jump(companion);   // in water this is the per-tick swim-up stroke
    }

    /**
     * Is the column straight above the head sealed before it reaches breathable
     * space? Unbroken water deeper than {@link #CEILING_PROBE} counts as open —
     * that is the deep-ocean case where rising is exactly right.
     */
    private static boolean ceilingSealed(NumenPlayer companion) {
        Level level = companion.level();
        BlockPos p = BlockPos.containing(companion.getEyePosition());
        for (int i = 0; i < CEILING_PROBE; i++) {
            p = p.above();
            BlockState s = level.getBlockState(p);
            if (s.getFluidState().is(FluidTags.WATER)) continue;
            return !breathable(level, p, s);
        }
        return false;
    }

    /** A cell the head could breathe in: no fluid, nothing to collide with. */
    private static boolean breathable(Level level, BlockPos pos, BlockState state) {
        return state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty();
    }

    /** Is {@code waterCell} still a valid opening: water with breathable space above? */
    private static boolean breathableAbove(Level level, BlockPos waterCell) {
        if (!level.getFluidState(waterCell).is(FluidTags.WATER)) return false;
        BlockPos above = waterCell.above();
        return breathable(level, above, level.getBlockState(above));
    }

    /**
     * BFS through connected water from the head for the nearest cell with
     * breathable space directly above — nearest-by-swim-distance, so the body
     * heads for the closest real opening, not a straight-line mirage behind a
     * wall. Bounded by {@link #AIR_SEARCH_BUDGET}/{@link #AIR_SEARCH_RADIUS}:
     * ~400 block reads once per {@link #RETARGET_TICKS} during an episode.
     */
    private static BlockPos findAirColumn(NumenPlayer companion) {
        Level level = companion.level();
        BlockPos start = BlockPos.containing(companion.getEyePosition());
        if (!level.getFluidState(start).is(FluidTags.WATER)) {
            start = companion.blockPosition();
        }
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        queue.add(start);
        seen.add(start.asLong());
        int budget = AIR_SEARCH_BUDGET;
        while (!queue.isEmpty() && budget-- > 0) {
            BlockPos cell = queue.poll();
            if (breathableAbove(level, cell)) {
                return cell;
            }
            for (Direction d : Direction.values()) {
                BlockPos n = cell.relative(d);
                if (Math.abs(n.getX() - start.getX()) > AIR_SEARCH_RADIUS
                        || Math.abs(n.getZ() - start.getZ()) > AIR_SEARCH_RADIUS) continue;
                if (!level.getFluidState(n).is(FluidTags.WATER)) continue;
                if (seen.add(n.asLong())) {
                    queue.add(n);
                }
            }
        }
        return null;
    }

    /** Diary the entrapment the moment it is diagnosed — not post-mortem. */
    private void noteTrapped(NumenPlayer companion) {
        if (trappedNoted || bodyLog == null) return;
        trappedNoted = true;
        bodyLog.report("drowning under a sealed ceiling with " + Math.max(0, companion.getAirSupply() / 20)
                + "s of air — no opening within " + AIR_SEARCH_RADIUS
                + " blocks of connected water; I need an air hole dug or a way out");
    }

    /** One diary line per near-drowning, stamped with how close it got (in seconds of air left). */
    private void noteEpisode(NumenPlayer companion) {
        episodeActive = false;
        int worst = worstAir;
        worstAir = Integer.MAX_VALUE;
        airColumn = null;
        retargetCooldown = 0;
        trappedNoted = false;
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
        return "在水里快憋不住气时会自己浮上来换气,头顶被冰面/岩层封住时会游向最近的透气口";
    }
}
