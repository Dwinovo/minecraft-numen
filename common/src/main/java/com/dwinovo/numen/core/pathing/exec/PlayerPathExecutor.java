package com.dwinovo.numen.core.pathing.exec;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavContext;
import com.dwinovo.numen.core.pathing.calc.Path;
import com.dwinovo.numen.core.pathing.movement.Movement;
import com.dwinovo.numen.core.pathing.movement.Moves;
import com.dwinovo.numen.core.pathing.util.ActionCosts;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.pathing.util.PathSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Walks a computed {@link Path} on a companion {@link NumenPlayer} body. The
 * cross-movement spine: re-localization (match real feet against nearby
 * movements' {@code validPositions} and resync the index instead of replanning
 * on every push/overshoot), a per-movement stall budget, and advancement.
 * Per-movement EXECUTION is input-driving ({@link InputDriver}) + the player's
 * own survival break/place — the body is steered by pressed inputs, not by
 * direct velocity writes.
 *
 * <p>Each movement runs as: clear its {@code toBreak} obstructions (the native
 * {@link BlockDigger} — {@code handleBlockBreakAction} START/STOP, survival
 * timed, drops fall and the player picks them up natively), place its
 * {@code toPlace} scaffold, then step toward
 * {@code dest} (face it, push forward; hop for ascend/pillar/parkour) until the
 * feet reach it. This is a first cut tuned for compilation + wiring; movement
 * feel gets refined against in-game runs.
 */
public final class PlayerPathExecutor {

    public enum Status { RUNNING, ARRIVED, NEEDS_REPLAN, FAILED }

    /** Off-path bands (soft 2 / hard 3 blocks, from {@link PathSettings}), squared. */
    private static final double SOFT_DIST_SQR =
            PathSettings.MAX_DIST_FROM_PATH * PathSettings.MAX_DIST_FROM_PATH;
    private static final double HARD_DIST_SQR =
            PathSettings.MAX_MAX_DIST_FROM_PATH * PathSettings.MAX_MAX_DIST_FROM_PATH;

    private final NumenPlayer player;
    private final Path path;
    private final double speed;
    /** Builds a fresh world snapshot for per-tick cost re-verification. */
    private final java.util.function.Supplier<NavContext> ctxSupplier;

    private int index = 0;
    private int ticksOnCurrent = 0;
    private int ticksAway = 0;
    private boolean placedThisMove = false;
    /** Set when we sprint-skipped a traverse straight into an ascend (see
     *  {@code sprintableAscend}) — the ascend then sprints up instead of jumping from a standstill. */
    private boolean sprintAscend = false;
    /** Set when we sprint-carried into this descend (see {@code canSprintFromDescendInto}) —
     *  the descend drives at sprint speed instead of a controlled walk-down. */
    private boolean sprintDescend = false;
    /** When a fall can overshoot forward over the same-direction traverses below it
     *  (see {@code overrideFall}), this is the extended aim point: driveFall sprints at it
     *  instead of braking straight down. Recomputed each tick; null = no override. */
    private Vec3 fallOverrideAim = null;
    /** How many ticks we've actually DRIVEN this descend
     *  (incremented only inside the drive branch, NOT on break/idle ticks) — gates the 20-tick
     *  fakeDest momentum window so a descend that breaks first doesn't burn it before moving. */
    private int descendDriveTicks = 0;
    /** The live edge-sneak scaffold placement for the current move (null when idle). */
    private PlaceManeuver placeManeuver;
    /** Progressive break of path obstructions (shared model with auto-mine). */
    private final BlockDigger digger;
    /** The index whose lookahead we already cost-verified (one verification per new movement). */
    private int costCheckIndex = -1;

    // --- stuck diagnostics (independent of ticksOnCurrent, so index oscillation can't hide a stall) ---
    /** Furthest movement index reached; net forward progress is measured against this. */
    private int maxIndexReached = 0;
    /** Ticks since {@link #maxIndexReached} last grew. Survives advance/snap/skip resets. */
    private int ticksSinceProgress = 0;
    /** One STUCK log per stall episode. */
    private boolean stuckWarned = false;
    /** Warn after this many ticks with no NET forward progress (≈3s) — catches a stall that the
     *  per-movement timeout misses because the index keeps bouncing. */
    private static final int STUCK_WARN_TICKS = 60;
    /** Force a replan after this many ticks of no NET progress (≈6s) — the backstop for an index
     *  thrash that keeps resetting the per-movement timeout so it never fires. */
    private static final int STUCK_REPLAN_TICKS = 120;
    /** Ticks submerged before treating it as off-plan. A short dunk (a fall clipping water) floats
     *  back up via the universal liquid buoyancy within this window — only sustained submersion
     *  replans, so we don't spam replans that each start underwater again. */
    private static final int UNDERWATER_GRACE_TICKS = 20;
    private int ticksUnderwater = 0;

    public PlayerPathExecutor(NumenPlayer player, Path path, double speed,
                              java.util.function.Supplier<NavContext> ctxSupplier) {
        this.player = player;
        this.path = path;
        this.speed = speed;
        this.ctxSupplier = ctxSupplier;
        this.digger = new BlockDigger(player);
    }

    public Status tick() {
        if (path.isEmpty() || index >= path.movements.size()) {
            return path.partial ? Status.NEEDS_REPLAN : Status.ARRIVED;
        }

        Status reloc = relocalize();
        if (reloc != null) return reloc;
        if (index >= path.movements.size()) {
            return path.partial ? Status.NEEDS_REPLAN : Status.ARRIVED;
        }

        // Sprint-to-next: flow straight through a traverse→sprintable-ascend
        // junction (sprint up the stairs) instead of arriving at the traverse first. May
        // advance the index BEFORE we resolve the move to drive.
        trySprintSkip();
        // Fall override: a fall that lines up with the corridor below it overshoots
        // forward (sprint off the ledge) and splices those traverses. May advance the index.
        tryFallOverride();
        // A splice/skip can land us at the path end (the overshoot ran to the last move) —
        // re-check arrival before resolving a move, or movements.get(index) would overrun.
        if (index >= path.movements.size()) {
            return path.partial ? Status.NEEDS_REPLAN : Status.ARRIVED;
        }

        Movement mv = path.movements.get(index);
        Status progress = trackProgress(mv);
        if (progress != null) return progress;

        // Submerged = off-plan, EXCEPT when the current move is a swim-up: the surface
        // plane is where traverse/ascend live, so being underwater on one of those means
        // we were knocked under → replan from a valid surface cell. But a water-column
        // swim-up (PILLAR out of a water cell) is *meant* to run submerged — exempt it,
        // or the replan would pre-empt the only move that climbs back to the surface.
        boolean swimmingUp = mv.kind == Movement.Kind.PILLAR
                && BlockHelper.isWater(player.level(), mv.src);
        if (player.isUnderWater() && !swimmingUp) {
            // Don't replan the instant we touch water — let the per-kind drive + buoyancy try to
            // surface first; only a SUSTAINED dunk is genuinely off-plan. Otherwise a fall that
            // clips water replans every tick, each new path again starting underwater.
            if (++ticksUnderwater > UNDERWATER_GRACE_TICKS) {
                return replan("underwater off-plan " + ticksUnderwater + " ticks");
            }
        } else {
            ticksUnderwater = 0;
        }

        // Cost re-verification: the world may have changed under a planned
        // path (someone broke/placed a block). Re-cost the current + the next few
        // movements against a fresh snapshot; bail if any became impossible, or if
        // the current one got materially more expensive than planned.
        Status reverify = verifyCosts();
        if (reverify != null) return reverify;

        ticksOnCurrent++;
        // Cancel a movement that overshoots its cost estimate by MOVEMENT_TIMEOUT_TICKS.
        if (ticksOnCurrent > mv.cost + PathSettings.MOVEMENT_TIMEOUT_TICKS) {
            return replan("movement timeout");
        }

        // 1) Clear obstructions for this move (one at a time), breaking each
        //    progressively over its real hardness time — the dig is held across
        //    ticks, the block doesn't pop instantly.
        BlockPos obstruction = nextObstruction(mv);
        if (obstruction != null) {
            digger.dig(obstruction);   // faces + breaks the block (halts the body)
            // Walk-while-breaking (TRAVERSE only): keep approaching + sprint while
            // the front block breaks — but only if neither break cell (dest feet + head) is
            // something to avoid walking into, and we aren't already pressed against the
            // block (Chebyshev dist >= 0.83).
            if (mv.kind == Movement.Kind.TRAVERSE
                    && !BlockHelper.avoidWalkingInto(player.level(), mv.dest)
                    && !BlockHelper.avoidWalkingInto(player.level(), mv.dest.above())
                    && chebyshevDistTo(mv.dest) >= 0.83) {
                player.zza = 1.0f;
                player.setSprinting(speed >= 1.0);
            }
            return Status.RUNNING;
        }

        // 2) Place the scaffold floor under dest, if this move needs one — the live
        //    "edge sneak" maneuver: hold sneak, edge to the rim, look at the support
        //    face, place. Drives the body forward toward the placement itself, so the
        //    bridge is laid as it leans out, not teleport-popped.
        //    (A body standing in the very cell it's trying to place into can wedge; a
        //    known remedy is an in-move nudge — press backward after ~10 ticks without
        //    placement. We instead let the movement-timeout replan rescue a stalled
        //    placement — a bounded fallback. Revisit only if ascend-place wedging shows up.)
        if (mv.toPlace != null && !placedThisMove) {
            if (placeManeuver == null) {
                placeManeuver = new PlaceManeuver(player, mv.toPlace, this::scaffoldSlot,
                        () -> BlockHelper.canWalkOn(player.level(), mv.toPlace));
            }
            switch (placeManeuver.tick()) {
                case DONE -> {
                    placedThisMove = true;
                    placeManeuver = null;
                }
                case FAILED -> {
                    placeManeuver = null;
                    return replan("scaffold place failed");   // out of blocks / no angle → replan
                }
                case RUNNING -> {
                    return Status.RUNNING;
                }
            }
        }

        // 3) Drive toward dest by movement kind — per-kind input forcing with
        //    tuned per-tick timing.
        if (arrived(mv)) {
            advance();
            return Status.RUNNING;
        }
        drive(mv);
        // Universal liquid float (runs after EVERY movement's per-kind drive):
        // if our feet cell is liquid and we're below dest.y+0.6, press jump
        // (→ jumpInLiquid buoyancy). This is the single framework-level mechanism that
        // keeps the body riding the water surface across ALL kinds — without it the body
        // sinks between per-move depth-corrections and porpoises.
        if (!player.level().getBlockState(feet()).getFluidState().isEmpty()
                && player.getY() < mv.dest.getY() + 0.6) {
            InputDriver.jump(player);
        }
        return Status.RUNNING;
    }

    /** Horizontal speed² this tick — used to gate jump timing on measured motion. */
    private double horizontalSpeedSqr() {
        var v = player.getDeltaMovement();
        return v.x * v.x + v.z * v.z;
    }

    /** Horizontal distance from the body to a cell's centre. */
    private double horizontalDistTo(BlockPos cell) {
        double dx = (cell.getX() + 0.5) - player.getX();
        double dz = (cell.getZ() + 0.5) - player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Chebyshev (max-axis) horizontal distance to a cell centre — the
     *  walk-while-breaking "pressed against the block" gate uses this, not Euclidean. */
    private double chebyshevDistTo(BlockPos cell) {
        return Math.max(Math.abs((cell.getX() + 0.5) - player.getX()),
                        Math.abs((cell.getZ() + 0.5) - player.getZ()));
    }

    /** The feet cell, nudged up 0.1251 (so soul-sand /
     *  farmland sink doesn't read us a block low), and — when that cell is a SLAB — taken
     *  as the cell ABOVE it. That slab adjustment is what lets standing on a bottom slab
     *  read as the move's dest (moves onto a slab target the cell above the slab). */
    private BlockPos feet() {
        return BlockHelper.playerFeet(player.level(), player.getX(), player.getY(), player.getZ());
    }

    /**
     * Per-movement execution. Each kind forces the player inputs it needs:
     * walk/diagonal aim + forward (+sprint); ascend
     * waits for forward motion before jumping; parkour holds the jump until clear
     * of the takeoff block; pillar sneaks, jumps and places underfoot at the apex;
     * descend/fall let gravity do the work; dig-down lets phase 1 break the floor.
     */
    private void drive(Movement mv) {
        player.setShiftKeyDown(false);   // default; pillar re-enables per tick
        openDoorsForMove(mv);            // a shut wooden door/gate ahead → open it, don't break it
        Vec3 dest = Vec3.atBottomCenterOf(mv.dest);
        // Never sprint in water, nor when about to run into a hazard just
        // past the destination (momentum could carry us into lava/cactus/etc).
        boolean sprintBase = speed >= 1.0 && !player.isInWater() && !hazardJustPast(mv);
        switch (mv.kind) {
            case TRAVERSE -> {
                // Correct DEPTH before advancing. If our feet
                // aren't on the destination's Y level (bobbing while swimming across water,
                // or sunk into a dip), do NOT move forward this tick — only rise (JUMP) if
                // we're below it; if we've popped ABOVE it, do nothing and let it settle.
                // Moving forward only happens once we're actually on the lane, which is
                // what keeps a water-surface crossing stable instead of porpoising.
                int feetY = feet().getY();
                if (feetY != mv.dest.getY()) {
                    InputDriver.halt(player);
                    // Below the lane → rise. On LAND that's a step-up hop; in LIQUID the
                    // universal liquid-float jump (in tick()) already does the rising, so
                    // don't add a second impulse here (our jump() isn't an idempotent flag).
                    if (feetY < mv.dest.getY()
                            && player.level().getBlockState(feet()).getFluidState().isEmpty()) {
                        InputDriver.jump(player);
                    }
                } else {
                    // On the lane: advance. Don't sprint across a floor we just placed
                    // (sprint momentum on a fresh bridge risks carrying past its edge).
                    InputDriver.stepToward(player, dest, sprintBase && mv.toPlace == null);
                }
            }
            // Only sprint a diagonal when both cut corners are clear.
            case DIAGONAL -> InputDriver.stepToward(player, dest, sprintBase && diagonalCornersClear(mv));
            case ASCEND -> driveAscend(mv);
            case PARKOUR -> {
                int gap = Math.max(Math.abs(mv.dest.getX() - mv.src.getX()),
                        Math.abs(mv.dest.getZ() - mv.src.getZ()));
                InputDriver.stepToward(player, dest, gap >= 4);   // a 4-gap needs sprint physics
                // Hold the jump until clear of the takeoff block,
                // so the arc starts from the edge, not the centre.
                if (player.onGround() && horizontalDistTo(mv.src) > 0.7) {
                    InputDriver.jump(player);
                }
            }
            case PILLAR -> drivePillar(mv);
            case DESCEND -> driveDescend(mv);
            case FALL -> driveFall(mv);
            case DIG_DOWN -> {
                // Phase 1 breaks the floor; here we just keep centred over the column so
                // gravity drops us straight onto the dug cell (recentre over the break
                // cell rather than free-drifting).
                if (horizontalDistTo(mv.dest) > 0.2) {
                    InputDriver.stepToward(player, Vec3.atBottomCenterOf(mv.dest), false);
                } else {
                    InputDriver.halt(player);
                }
            }
        }
    }

    /**
     * Step down one block: aim one block past
     * dest (fakeDest) for the first ~20 ticks WHILE still near the start
     * (fromStart&lt;1.25), to carry momentum off the ledge; then aim at dest. Stop
     * pushing once horizontally on dest so gravity settles us cleanly.
     */
    private void driveDescend(Movement mv) {
        if (descendSafeMode(mv)) {
            // Safe mode: a slowed, straight-in approach to a 0.17/0.83 weighted
            // point with NO fakeDest momentum — avoids overshooting into a wall / hazard
            // and the skip-to-ascend glitch.
            double dx = (mv.src.getX() + 0.5) * 0.17 + (mv.dest.getX() + 0.5) * 0.83;
            double dz = (mv.src.getZ() + 0.5) * 0.17 + (mv.dest.getZ() + 0.5) * 0.83;
            InputDriver.stepToward(player, new Vec3(dx, mv.dest.getY(), dz), false);
            return;
        }
        double ab = horizontalDistTo(mv.dest);
        if (!feet().equals(mv.dest) || ab > 0.25) {
            // descendDriveTicks++ < 20: post-increment inside the drive branch (counts only
            // ticks we actually drive), gating the fakeDest momentum carry.
            boolean earlyWindow = descendDriveTicks++ < 20 && horizontalDistTo(mv.src) < 1.25;
            // Hold the forward (fakeDest) aim through the airborne phase to avoid the jarring ~180°
            // yaw snap (we hard-set yaw rather than rotating smoothly) — but ONLY on a controlled,
            // non-sprint descend, where overshoot is ≤1 cell (dest/fakeDest, both count as arrived).
            // A sprint-carried descend (a stair chain, 2+ in a row) keeps the exact-aim rule —
            // switch to dest once past the early window — so its momentum is steered back onto the
            // planned line instead of drifting off it.
            boolean commitForward = (!player.onGround() && !sprintDescend) || earlyWindow;
            Vec3 aim = commitForward
                    ? Vec3.atBottomCenterOf(new BlockPos(
                            2 * mv.dest.getX() - mv.src.getX(),
                            mv.dest.getY(),
                            2 * mv.dest.getZ() - mv.src.getZ()))
                    : Vec3.atBottomCenterOf(mv.dest);
            InputDriver.stepToward(player, aim, sprintDescend);
        } else {
            InputDriver.halt(player);
        }
    }

    /** Descend safe mode: a hazard just past dest (sprint-overshoot risk)
     *  OR the skip-to-ascend overshoot-glitch geometry (a wall at foot level with air above). */
    private boolean descendSafeMode(Movement mv) {
        if (hazardJustPast(mv)) return true;
        int dx = mv.dest.getX() - mv.src.getX();
        int dz = mv.dest.getZ() - mv.src.getZ();
        if (dx == 0 && dz == 0) return false;
        BlockPos into = mv.dest.offset(dx, 0, dz);
        return !BlockHelper.canWalkThrough(player.level(), into)
                && BlockHelper.canWalkThrough(player.level(), into.above())
                && BlockHelper.canWalkThrough(player.level(), into.above(2));
    }

    /** Something to avoid in the cell(s) one step PAST the destination — the block we'd
     *  carry into if we over-committed. The descend safe mode + sprint suppression both
     *  gate on {@code avoidWalkingInto} (any fluid + the hazard block set) for exactly this. */
    private boolean hazardJustPast(Movement mv) {
        int dx = Integer.signum(mv.dest.getX() - mv.src.getX());
        int dz = Integer.signum(mv.dest.getZ() - mv.src.getZ());
        if (dx == 0 && dz == 0) return false;
        BlockPos into = mv.dest.offset(dx, 0, dz);
        for (int y = 0; y <= 2; y++) {
            if (BlockHelper.avoidWalkingInto(player.level(), into.above(y))) return true;
        }
        return false;
    }

    /** Both diagonal cut-corners clear — the gate for sprinting a diagonal. */
    private boolean diagonalCornersClear(Movement mv) {
        BlockPos cornerA = new BlockPos(mv.src.getX(), mv.src.getY(), mv.dest.getZ());
        BlockPos cornerB = new BlockPos(mv.dest.getX(), mv.src.getY(), mv.src.getZ());
        return BlockHelper.canWalkThrough(player.level(), cornerA)
                && BlockHelper.canWalkThrough(player.level(), cornerA.above())
                && BlockHelper.canWalkThrough(player.level(), cornerB)
                && BlockHelper.canWalkThrough(player.level(), cornerB.above());
    }

    /**
     * Multi-block fall (no water-bucket clutch — that would need a bucket
     * in hand): unlike a descend it does NOT carry momentum
     * — it RECENTRES on the landing cell so it doesn't clip a wall, sneaking while
     * fast-falling ({@code |Δy|>0.4}) to kill horizontal drift and land cleanly.
     */
    private void driveFall(Movement mv) {
        if (fallOverrideAim != null) {
            // Overshoot: sprint forward at the extended aim so we clear the ledge and ride the
            // fall over the corridor below, instead of braking straight down.
            player.setShiftKeyDown(false);
            InputDriver.lookAt(player, fallOverrideAim);
            player.zza = 1.0f;
            player.xxa = 0.0f;
            player.setSprinting(true);
            return;
        }
        var v = player.getDeltaMovement();
        double cx = mv.dest.getX() + 0.5;
        double cz = mv.dest.getZ() + 0.5;
        // Look ahead by one tick of velocity to anticipate the drift.
        boolean offCentre = Math.abs(player.getX() + v.x - cx) > 0.1
                || Math.abs(player.getZ() + v.z - cz) > 0.1;
        if (offCentre) {
            // Sneak while fast-falling to brake the horizontal drift toward centre.
            player.setShiftKeyDown(!player.onGround() && Math.abs(v.y) > 0.4);
            InputDriver.lookAt(player, fallAim(mv));   // landing cell, biased off an adjacent ladder
            player.zza = 1.0f;
            player.xxa = 0.0f;
            player.setSprinting(false);
        } else {
            player.setShiftKeyDown(false);
            InputDriver.halt(player);
        }
    }

    /** Aim at the landing cell, nudged 0.125 away from an adjacent ladder/vine so the
     *  fall doesn't grab it mid-drop. */
    private Vec3 fallAim(Movement mv) {
        Vec3 c = Vec3.atCenterOf(mv.dest);
        for (Direction d : new Direction[]{
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            var s = player.level().getBlockState(mv.dest.relative(d));
            if (s.is(net.minecraft.world.level.block.Blocks.LADDER)
                    || s.is(net.minecraft.world.level.block.Blocks.VINE)) {
                return c.add(-d.getStepX() * 0.125, 0.0, -d.getStepZ() * 0.125);
            }
        }
        return c;
    }

    /** Carry/aim of an overshooting fall. */
    private record FallOverride(Vec3 aim, BlockPos fallDest, int spliceIndex) {}

    /** Fall-override handling: if the current FALL lines up with the
     *  corridor below it, either splice past it (once we've landed at the extended dest) or
     *  set the forward aim so driveFall sprints off the ledge. Recomputed each tick. */
    private void tryFallOverride() {
        fallOverrideAim = null;
        if (index >= path.movements.size()) return;
        Movement cur = path.movements.get(index);
        if (cur.kind != Movement.Kind.FALL) return;
        FallOverride fo = overrideFall(cur);
        if (fo == null) return;
        if (feet().equals(fo.fallDest)) {
            jumpToIndex(fo.spliceIndex);   // landed at the overshoot dest — continue past the spliced traverses
            return;
        }
        fallOverrideAim = fo.aim;
    }

    /** A fall of ≤3 that isn't breaking and is followed by
     *  up to two same-flat-direction traverses, each with a clear column up to the fall's head
     *  and a solid floor, can overshoot forward — the body rides the fall out over the corridor
     *  and lands at the last such traverse's dest. Returns null when no extension is valid. */
    private FallOverride overrideFall(Movement mv) {
        BlockPos dir = dirOf(mv);
        if (dir.getY() < -3) return null;          // too deep to overshoot safely
        if (!mv.toBreak.isEmpty()) return null;    // it's breaking
        BlockPos flatDir = new BlockPos(dir.getX(), 0, dir.getZ());
        int i;
        // Bound in node terms: a path of N movements has N+1 nodes; "< nodes-1" == movements.size().
        for (i = index + 1; i < path.movements.size() && i < index + 3; i++) {
            Movement next = path.movements.get(i);
            if (next.kind != Movement.Kind.TRAVERSE) break;
            if (!flatDir.equals(dirOf(next))) break;
            boolean columnBlocked = false;
            for (int y = next.dest.getY(); y <= mv.src.getY() + 1; y++) {
                BlockPos chk = new BlockPos(next.dest.getX(), y, next.dest.getZ());
                if (!BlockHelper.canWalkThrough(player.level(), chk)) { columnBlocked = true; break; }
            }
            if (columnBlocked) break;
            if (!BlockHelper.canWalkOn(player.level(), next.dest.below())) break;
        }
        i--;
        if (i == index) return null;   // no valid extension exists
        double len = i - index - 0.4;
        int steps = i - index;
        Vec3 aim = new Vec3(flatDir.getX() * len + mv.dest.getX() + 0.5,
                mv.dest.getY(),
                flatDir.getZ() * len + mv.dest.getZ() + 0.5);
        BlockPos fallDest = mv.dest.offset(flatDir.getX() * steps, 0, flatDir.getZ() * steps);
        return new FallOverride(aim, fallDest, i + 1);
    }

    /**
     * Step up one block. Drive forward toward
     * dest, then JUMP when the body is ALIGNED to the move axis (small lateral
     * drift) and either the head is clear or we're close enough — critically NOT
     * gated on forward speed, so hitting the step (which zeroes forward speed) still
     * triggers the jump instead of stalling against the wall.
     */
    private void driveAscend(Movement mv) {
        // Sprint up only when we sprint-skipped into this ascend (sprintableAscend);
        // a normal ascend jumps from a standstill.
        InputDriver.stepToward(player, Vec3.atBottomCenterOf(mv.dest), sprintAscend);
        // Stepping onto a bottom slab from a non-slab is a 0.5 walk-up, NOT a jump
        // — vanilla auto-step handles the half block, so don't press jump here.
        if (BlockHelper.isBottomSlab(player.level(), mv.dest.below())
                && !BlockHelper.isBottomSlab(player.level(), mv.src.below())) {
            return;
        }
        if (feet().equals(mv.src.above())) {
            return;   // already airborne off the step
        }
        int xAxis = Math.abs(mv.src.getX() - mv.dest.getX()); // 0 or 1
        int zAxis = Math.abs(mv.src.getZ() - mv.dest.getZ()); // 0 or 1
        double px = player.getX();
        double pz = player.getZ();
        double flatDistToNext = xAxis * Math.abs((mv.dest.getX() + 0.5) - px)
                + zAxis * Math.abs((mv.dest.getZ() + 0.5) - pz);
        double sideDist = zAxis * Math.abs((mv.dest.getX() + 0.5) - px)
                + xAxis * Math.abs((mv.dest.getZ() + 0.5) - pz);
        var dm = player.getDeltaMovement();
        double lateralMotion = xAxis * dm.z + zAxis * dm.x;   // drift perpendicular to the move axis
        if (Math.abs(lateralMotion) > 0.1) {
            return;   // still drifting sideways — wait until aligned
        }
        if (headBonkClear(mv.src)) {
            InputDriver.jump(player);   // head's clear above — jump now (InputDriver gates onGround)
            return;
        }
        if (flatDistToNext > 1.2 || sideDist > 0.2) {
            return;   // too far / off-axis — would bonk an adjacent block
        }
        InputDriver.jump(player);
    }

    /** Head-bonk check: the four horizontals above src+2 are passable, so a
     *  jump won't smack the body's head into a ceiling block. */
    private boolean headBonkClear(BlockPos src) {
        BlockPos up2 = src.above(2);
        for (Direction d : new Direction[]{
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (!BlockHelper.canWalkThrough(player.level(), up2.relative(d))) {
                return false;
            }
        }
        return true;
    }

    /** Sprint-skip: if the current
     *  traverse leads straight into a sprintable ascend and we're centred enough to commit
     *  (skipNow), skip the traverse's arrival and sprint up the step. Advances the index. */
    private boolean trySprintSkip() {
        // Descend-sprint is re-decided every tick (sprint is re-asserted per tick, never
        // latched), so clear it up front; the descend branch below re-asserts it when still applicable.
        sprintDescend = false;
        if (index >= path.movements.size() - 1) return false;   // need at least cur + next
        Movement cur = path.movements.get(index);
        Movement next = path.movements.get(index + 1);
        // This runs BEFORE the per-move phases (break/place/drive), so don't skip past a
        // move whose own obstruction is still pending — skipping would cancel that dig.
        if (nextObstruction(cur) != null) return false;
        // Traverse → sprintable ascend: skip the traverse's arrival and sprint up the step.
        if (cur.kind == Movement.Kind.TRAVERSE && next.kind == Movement.Kind.ASCEND
                && index < path.movements.size() - 2
                && sprintableAscend(cur, next, path.movements.get(index + 2)) && skipNow(cur)) {
            advance();              // skip straight into the ascend
            sprintAscend = true;    // (advance() reset it; set after)
            return true;
        }
        // Descend → sprintable continuation (canSprintFromDescendInto): carry sprint
        // down the descend into a same-direction descend chain or a traverse/diagonal, and
        // hand off the moment we reach the descend's landing cell.
        //   safeMode gate: we gate on plain `!descendSafeMode` — intentionally NOT sprinting
        //   into the skip-to-ascend clip-glitch (a laxer gate could still sprint to clip
        //   through it); consistent with the stricter-safety stance taken elsewhere in
        //   this executor.
        if (cur.kind == Movement.Kind.DESCEND && !descendSafeMode(cur)
                && canSprintFromDescendInto(cur, next)) {
            // Next-next veto: if this descend feeds a descend chain whose THIRD link
            // can't itself be sprinted into, don't build momentum we can't safely shed.
            if (next.kind == Movement.Kind.DESCEND && index + 2 < path.movements.size()) {
                Movement nextNext = path.movements.get(index + 2);
                if (nextNext.kind == Movement.Kind.DESCEND
                        && !canSprintFromDescendInto(next, nextNext)) {
                    return false;
                }
            }
            if (feet().equals(cur.dest)) {
                advance();
                return trySprintSkip();   // re-decide sprint for the NEW current (recurse on handoff)
            }
            sprintDescend = true;   // sprint this descend this tick
            return true;
        }
        return false;
    }

    /** A descend keeps sprint into the next
     *  move only when it's a same-direction descend chain, or — with a solid floor one block
     *  below-and-forward of the landing — a diagonal. Deliberately NOT a traverse: a
     *  descend's move vector (dx,−1,dz) can never equal a traverse's (dx,0,dz), so a
     *  descend never sprint-flows into a traverse — the body settles at the landing, then
     *  the traverse drives itself. */
    private boolean canSprintFromDescendInto(Movement cur, Movement next) {
        if (next.kind == Movement.Kind.DESCEND && dirOf(cur).equals(dirOf(next))) {
            return true;
        }
        // dirOf(cur) is (dx,-1,dz), so dest.offset(dir) is one block below-AND-forward.
        if (!BlockHelper.canWalkOn(player.level(), cur.dest.offset(dirOf(cur)))) return false;
        return next.kind == Movement.Kind.DIAGONAL;
    }

    /** Sprintable ascend: the traverse and
     *  the following ascend share a horizontal direction (as does the move after), both have
     *  a solid floor, the ascend breaks nothing, the 2x3 column over the source is clear, and
     *  neither the block above the head nor above the ascend's head is something to avoid. */
    private boolean sprintableAscend(Movement cur, Movement next, Movement nextnext) {
        net.minecraft.world.level.Level level = player.level();
        BlockPos curDir = dirOf(cur);     // (dx,0,dz)
        BlockPos nextDir = dirOf(next);   // (dx,1,dz)
        if (curDir.getX() != nextDir.getX() || curDir.getZ() != nextDir.getZ()) return false;
        BlockPos nnDir = dirOf(nextnext);
        if (nnDir.getX() != nextDir.getX() || nnDir.getZ() != nextDir.getZ()) return false;
        if (!BlockHelper.canWalkOn(level, cur.dest.below())) return false;
        if (!BlockHelper.canWalkOn(level, next.dest.below())) return false;
        if (!next.toBreak.isEmpty()) return false;   // it's breaking
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 3; y++) {
                BlockPos chk = cur.src.above(y);
                if (x == 1) chk = chk.offset(curDir.getX(), 0, curDir.getZ());
                if (!BlockHelper.canWalkThrough(level, chk)) return false;
            }
        }
        if (BlockHelper.avoidWalkingInto(level, cur.src.above(3))) return false;
        return !BlockHelper.avoidWalkingInto(level, next.dest.above(2));
    }

    /** Commit gate for the sprint-skip: centred on the move axis (off-axis < 0.1) AND either
     *  the cell we'd head-bonk on is clear, or we've travelled far enough (flat dist > 0.8). */
    private boolean skipNow(Movement cur) {
        BlockPos dir = dirOf(cur);
        double offTarget = Math.abs(dir.getX() * (cur.src.getZ() + 0.5 - player.getZ()))
                + Math.abs(dir.getZ() * (cur.src.getX() + 0.5 - player.getX()));
        if (offTarget > 0.1) return false;
        BlockPos headBonk = cur.src.offset(-dir.getX(), 0, -dir.getZ()).above(2);
        if (BlockHelper.canWalkThrough(player.level(), headBonk)) return true;
        double flatDist = Math.abs(dir.getX() * (headBonk.getX() + 0.5 - player.getX()))
                + Math.abs(dir.getZ() * (headBonk.getZ() + 0.5 - player.getZ()));
        return flatDist > 0.8;
    }

    /** The net move vector (dest − src). */
    private static BlockPos dirOf(Movement mv) {
        return mv.dest.subtract(mv.src);
    }

    /**
     * Block-tower pillar: sneak so we never step off the
     * column, stay centred, jump from the ground, and while airborne keep attempting
     * to place a block in the cell we just left so we land one higher.
     */
    private void drivePillar(Movement mv) {
        // Swim straight up a water column:
        // aim at the destination cell's centre and press forward ONLY when off-centre by
        // >0.2 (just to recentre) — buoyancy does the actual rising. No sneak, no place.
        if (BlockHelper.isWater(player.level(), mv.src)) {
            player.setShiftKeyDown(false);
            InputDriver.lookAt(player, Vec3.atCenterOf(mv.dest));
            double cx = mv.dest.getX() + 0.5;
            double cz = mv.dest.getZ() + 0.5;
            player.zza = (Math.abs(player.getX() - cx) > 0.2 || Math.abs(player.getZ() - cz) > 0.2)
                    ? 1.0f : 0.0f;
            player.xxa = 0.0f;
            player.setSprinting(false);
            // The rise itself comes from the universal liquid-float jump in tick() —
            // this branch deliberately presses no jump of its own.
            return;
        }
        player.setShiftKeyDown(true);   // sneak: never step off the column
        // Recentre on the column rather than walking off it.
        if (horizontalDistTo(mv.src) > 0.17) {
            InputDriver.stepToward(player, Vec3.atBottomCenterOf(mv.src), false);
        } else {
            InputDriver.halt(player);
            // Jump only when nearly still AND still BELOW the
            // destination — stop jumping once we've reached the top (`y < dest.y`).
            if (player.onGround() && horizontalSpeedSqr() < 0.0025
                    && player.getY() < mv.dest.getY()) {
                InputDriver.jump(player);
            }
        }
        // While airborne, attempt the underfoot place EVERY tick and let vanilla's own
        // placement rules judge legality: the ticks where our collision box still occupies
        // the cell are rejected by the placement's obstruction check, and the first tick
        // the hop lifts the box clear (feet above cell top) succeeds. No apex timing, no
        // Y-window guess — the judge is the same code that must accept the block anyway.
        if (!player.onGround()) {
            placeUnderfoot(mv.src);
        }
    }

    /** Attempt a scaffold place at {@code cell} against the solid block directly below it,
     *  performed natively (look down + useItemOn on the up-face). Called every airborne
     *  pillar tick; vanilla accepts on whichever tick the hop has cleared the cell. */
    private void placeUnderfoot(BlockPos cell) {
        int slot = scaffoldSlot();
        if (slot < 0) {
            underfootBlocked("no scaffold block in inventory", cell);
            return;
        }
        if (!Placement.canPlaceAgainst(player.level(), cell.below())) {
            underfootBlocked("support below is gone", cell);
            return;
        }
        InputDriver.lookAt(player, Vec3.atBottomCenterOf(cell));   // look straight down at the support's top
        BlockHitResult hit = Placement.resolve(player, cell, true);  // honest raycast only — no fabricated hit
        if (hit == null) {
            underfootBlocked("no clear line to own feet cell", cell);
            return;
        }
        player.holdInHand(slot);   // real hotbar-select / swap-to-hand, not an aliasing overwrite
        Interaction use = Interaction.useBlock(player, hit, InteractionHand.MAIN_HAND);
        use.tick();
        // The press is NOT trusted as the outcome — the world is. Refusals early in the hop
        // (box still occupies the cell) are expected and retried next tick; only a persistent
        // refusal streak is a real blockage, and then the diagnostic names vanilla's verdict
        // and where the body actually was, so a dead pillar is never silent again.
        if (BlockHelper.canWalkOn(player.level(), cell)) {
            underfootBlockedTicks = 0;
        } else {
            underfootBlocked(String.format("vanilla refused: %s (y=%.2f, clears cell at >%d.0)",
                    use.lastUseOutcome(), player.getY(), cell.getY() + 1), cell);
        }
    }

    /** Persistent pillar-place blockage diagnostics — names the failing gate once a second. */
    private int underfootBlockedTicks;

    private void underfootBlocked(String gate, BlockPos cell) {
        if (++underfootBlockedTicks % 20 == 0) {
            Constants.LOG.info("[numen-path] pillar place blocked {} ticks ({}) at {}",
                    underfootBlockedTicks, gate, cell.toShortString());
        }
    }

    /**
     * Toggle any wooden door / fence gate that blocks THIS movement (the dest cell + the head
     * cell above it, each judged from the cell we approach it from) —
     * the alternative to breaking it. The planner already treats openable
     * doors as passable (so they never enter {@code toBreak}); here we right-click via the shared
     * {@link Interaction#useBlock} primitive, which does a REAL eye-raycast (no fabricated hit) and
     * only fires when the door is actually in line of sight — exactly like a player, and the same
     * interaction path mining uses. Not sneaking (drive() cleared shift), so the door's own use()
     * fires (open/close) rather than placing a held block against it.
     */
    private void openDoorsForMove(Movement mv) {
        toggleDoorwayIfBlocking(mv.dest, mv.src);
        toggleDoorwayIfBlocking(mv.dest.above(), mv.src.above());
    }

    private void toggleDoorwayIfBlocking(BlockPos cell, BlockPos from) {
        if (!BlockHelper.isOpenableDoor(player.level().getBlockState(cell))) {
            return;   // not a wooden door / fence gate (iron doors stay a solid obstruction)
        }
        if (BlockHelper.isDoorwayPassable(player.level(), cell, from)) {
            return;   // already passable for this approach — nothing to toggle
        }
        // Real line-of-sight right-click (raycast-verified); a blocked sightline just retries
        // next tick as the body lines up — no reachable rotation means no click.
        Interaction.useBlock(player, cell, InteractionHand.MAIN_HAND).tick();
    }

    /**
     * Cost re-verification. The current movement is re-costed
     * every tick (catches a block change directly in front of us); the lookahead
     * window is re-verified only when we just advanced to a new movement (the
     * {@code costCheckIndex} guard). Cancels — triggering a replan — if any
     * movement became impossible ({@code COST_INF}) or the current one's live cost
     * rose by more than {@code MAX_COST_INCREASE} over its planned estimate.
     */
    private Status verifyCosts() {
        NavContext fresh = ctxSupplier.get();
        Movement cur = path.movements.get(index);
        // EVERY cost-based cancel is gated on safeToCancel():
        // a movement mid-commit (airborne, mid-jump, mid-place, bridging over air) must
        // not be abandoned to a replan, or the body is dropped into a bad state. While
        // not cancellable, let the move finish; the movement timeout still bounds a stall.
        if (!safeToCancel(cur)) {
            return null;
        }
        // Movements whose OWN execution mutates the world — pillar places a block
        // underfoot, dig-down breaks the floor — would self-trigger COST_INF the
        // instant they act (regeneration no longer emits them). Skip live re-costing
        // for those; the movement timeout still guards a genuine stall. Movements
        // that only consume the world (break an obstruction) get cheaper, never
        // INF, so they're safe to verify.
        if (!selfMutating(cur.kind)) {
            double liveCur = recost(fresh, cur);
            if (liveCur >= ActionCosts.COST_INF) {
                return replan("current move now impossible (" + obstruct(fresh, cur) + ")");
            }
            if (liveCur - cur.cost > PathSettings.MAX_COST_INCREASE) {
                return replan("current move got too expensive");
            }
        }
        if (costCheckIndex != index) {
            costCheckIndex = index;
            int last = path.movements.size() - 1;
            // Window: COST_VERIFICATION_LOOKAHEAD(5) → 4 movements ahead
            // (index+1 .. index+4), bounded by the last movement.
            for (int i = index + 1; i < index + PathSettings.COST_VERIFICATION_LOOKAHEAD && i <= last; i++) {
                Movement ahead = path.movements.get(i);
                if (!selfMutating(ahead.kind)
                        && recost(fresh, ahead) >= ActionCosts.COST_INF) {
                    return replan("lookahead +" + (i - index) + " impossible: " + ahead.kind + " "
                            + ahead.src.toShortString() + "->" + ahead.dest.toShortString()
                            + " (" + obstruct(fresh, ahead) + ")");
                }
            }
        }
        return null;
    }

    /**
     * Cancel-safety gate, judged per movement kind + live body state. A movement
     * that is mid-commit must not
     * be cancelled (replanned) — doing so drops the body off a fall, fails a jump, or
     * strands it mid-bridge. Per kind:
     * <ul>
     *   <li>FALL — safe only before stepping off the edge (still at {@code src});</li>
     *   <li>PARKOUR — only on the takeoff (0th) tick, no momentum knowledge after;</li>
     *   <li>ASCEND — not while a step block was just placed;</li>
     *   <li>TRAVERSE — a sneak-bridge over air can't be abandoned until its floor exists;</li>
     *   <li>others (DIAGONAL/DESCEND/PILLAR/DIG_DOWN) — base {@code true}.</li>
     * </ul>
     */
    private boolean safeToCancel(Movement mv) {
        switch (mv.kind) {
            case FALL:
                return feet().equals(mv.src);
            case PARKOUR:
                return ticksOnCurrent == 0;
            case ASCEND:
                // Unsafe once we've STARTED placing the step block —
                // i.e. once the place maneuver began or finished.
                return mv.toPlace == null || (placeManeuver == null && !placedThisMove);
            case TRAVERSE:
                return mv.toPlace == null
                        || BlockHelper.canWalkOn(player.level(), mv.dest.below());
            case DIAGONAL:
                return diagonalSafeToCancel(mv);
            default:
                return true;
        }
    }

    /** Diagonal cancel-safety: safe at the start cell, or when both cut
     *  corners have a floor; if we're cornering through an unwalkable corner cell, only
     *  safe when a block actually supports us (one of the four 0.25 offsets below). */
    private boolean diagonalSafeToCancel(Movement mv) {
        net.minecraft.world.level.Level level = player.level();
        BlockPos feet = feet();
        if (feet.equals(mv.src)) return true;
        BlockPos floorA = new BlockPos(mv.src.getX(), mv.src.getY() - 1, mv.dest.getZ());
        BlockPos floorB = new BlockPos(mv.dest.getX(), mv.src.getY() - 1, mv.src.getZ());
        if (BlockHelper.canWalkOn(level, floorA) && BlockHelper.canWalkOn(level, floorB)) {
            return true;
        }
        BlockPos cornerA = new BlockPos(mv.src.getX(), mv.src.getY(), mv.dest.getZ());
        BlockPos cornerB = new BlockPos(mv.dest.getX(), mv.src.getY(), mv.src.getZ());
        if (feet.equals(cornerA) || feet.equals(cornerB)) {
            double off = 0.25;
            double x = player.getX(), y = player.getY() - 1, z = player.getZ();
            return BlockHelper.canWalkOn(level, BlockPos.containing(x + off, y, z + off))
                    || BlockHelper.canWalkOn(level, BlockPos.containing(x + off, y, z - off))
                    || BlockHelper.canWalkOn(level, BlockPos.containing(x - off, y, z + off))
                    || BlockHelper.canWalkOn(level, BlockPos.containing(x - off, y, z - off));
        }
        return true;
    }

    /** Kinds whose execution changes the world such that regenerating them mid-move
     *  would spuriously report them gone (place-under / break-floor). */
    private static boolean selfMutating(Movement.Kind kind) {
        return kind == Movement.Kind.PILLAR
                || kind == Movement.Kind.DIG_DOWN;
    }

    /**
     * Recompute a movement's live cost from a fresh world snapshot: regenerate the
     * moves out of its source cell and match the same kind + destination.
     * {@code COST_INF} if it's no longer producible (the world changed so the move
     * is gone).
     */
    private double recost(NavContext fresh, Movement mv) {
        for (Movement gen : Moves.generate(fresh, mv.src)) {
            if (gen.kind == mv.kind && gen.dest.equals(mv.dest)) {
                return gen.cost;
            }
        }
        return ActionCosts.COST_INF;
    }

    /**
     * Has the move completed? Feet at dest, or — for a walk — overshot 1–2 cells past
     * it at the same height (a sprint can carry the
     * body a cell or two beyond the target, which still counts as arrived).
     */
    private boolean arrived(Movement mv) {
        BlockPos feet = feet();
        if (mv.kind == Movement.Kind.PILLAR) {
            // Water swim-up succeeds on feet==dest with NO
            // block check (you're swimming, nothing is placed).
            if (BlockHelper.isWater(player.level(), mv.src)) {
                return feet.equals(mv.dest);
            }
            // Dry tower: success = feet at dest AND the placed block
            // exists (canWalkOn(src)). WITHOUT the block check, the jump APEX — feet
            // momentarily at dest.y mid-air, before placeUnderfoot has placed anything —
            // false-arrives, advances the index, then the body falls back with nothing
            // placed and churns forever. The block check holds arrival until we've placed.
            return feet.equals(mv.dest) && BlockHelper.canWalkOn(player.level(), mv.src);
        }
        if (mv.kind == Movement.Kind.DESCEND) {
            // Descend success: feet at dest OR the overshoot fakeDest, AND
            // settled within 0.5 of dest.y (or dest is liquid) — don't advance while still
            // falling through the destination cell.
            BlockPos fakeDest = new BlockPos(
                    2 * mv.dest.getX() - mv.src.getX(), mv.dest.getY(), 2 * mv.dest.getZ() - mv.src.getZ());
            if (!feet.equals(mv.dest) && !feet.equals(fakeDest)) return false;
            return !player.level().getBlockState(mv.dest).getFluidState().isEmpty()
                    || player.getY() - mv.dest.getY() < 0.5;
        }
        if (mv.kind == Movement.Kind.FALL) {
            // Fall success: feet at dest AND settled within 0.094 of dest.y
            // (lilypad tolerance); or landed in water and no longer sinking (no water-bucket
            // clutch here).
            if (!feet.equals(mv.dest)) return false;
            if (!player.level().getBlockState(mv.dest).getFluidState().isEmpty()) {
                return player.getDeltaMovement().y >= 0.0;
            }
            return player.getY() - mv.dest.getY() < 0.094;
        }
        if (mv.kind == Movement.Kind.TRAVERSE) {
            // Traverse SUCCESS only once the floor under dest exists
            // (canWalkOn(dest.below)) — don't declare a bridge traverse done before the
            // placed floor is there. Then feet==dest, or a 1-2 cell sprint overshoot in the
            // move direction.
            if (!BlockHelper.canWalkOn(player.level(), mv.dest.below())) return false;
            if (feet.equals(mv.dest)) return true;
            int dx = Integer.signum(mv.dest.getX() - mv.src.getX());
            int dz = Integer.signum(mv.dest.getZ() - mv.src.getZ());
            if (dx != 0 || dz != 0) {
                BlockPos one = mv.dest.offset(dx, 0, dz);
                if (feet.equals(one) || feet.equals(one.offset(dx, 0, dz))) return true;
            }
            return false;
        }
        if (mv.kind == Movement.Kind.ASCEND) {
            // Ascend success: feet==dest OR one cell further horizontally
            // (the jump can carry us a cell past dest).
            if (feet.equals(mv.dest)) return true;
            int dx = mv.dest.getX() - mv.src.getX();
            int dz = mv.dest.getZ() - mv.src.getZ();
            return feet.equals(mv.dest.offset(dx, 0, dz));
        }
        // DIAGONAL (feet==dest only, no overshoot), DIG_DOWN, PARKOUR.
        return feet.equals(mv.dest);
    }

    /** The next still-solid block this move must break, or null when its path is clear. */
    private BlockPos nextObstruction(Movement mv) {
        for (BlockPos pos : mv.toBreak) {
            if (!player.level().getBlockState(pos).isAir()) {
                return pos;
            }
        }
        return null;
    }

    /** The inventory slot of a scaffold block (cobble/dirt/…), or -1 if none. */
    private int scaffoldSlot() {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (NavContext.isScaffold(inv.getItem(i))) return i;
        }
        return -1;
    }

    // ---- re-localization ----

    private Status relocalize() {
        BlockPos feet = feet();
        Movement cur = path.movements.get(index);
        if (cur.validPositions().contains(feet)) {
            ticksAway = 0;
            return null;
        }
        // Backward: lagged / pushed back — scan earlier
        // movements FROM THE START and take the FIRST (earliest) whose valid cells hold us.
        for (int i = 0; i < index; i++) {
            if (path.movements.get(i).validPositions().contains(feet)) {
                jumpToIndex(i);
                return null;
            }
        }
        // Forward skip +3: a movement signals its own completion (e.g. sneak-place),
        // so +1/+2 are deliberately skipped.
        int last = path.movements.size() - 1;
        for (int i = index + 3; i <= last; i++) {
            if (path.movements.get(i).validPositions().contains(feet)) {
                jumpToIndex(i - 1);
                return null;
            }
        }
        // Off-path watchdog: distance to the
        // closest valid cell of the WHOLE path — not just the current movement — so the body
        // being near any part of the route doesn't read as off-path. Two bands: >2 blocks
        // counts toward MAX_TICKS_AWAY before giving up, >3 cancels immediately.
        double bestSq = Double.MAX_VALUE;
        for (Movement m : path.movements) {
            for (BlockPos vp : m.validPositions()) {
                double d = player.distanceToSqr(Vec3.atCenterOf(vp));
                if (d < bestSq) bestSq = d;
            }
        }
        // Soft band first, then the immediate hard band.
        if (possiblyOffPath(cur, bestSq, SOFT_DIST_SQR)) {
            if (++ticksAway > PathSettings.MAX_TICKS_AWAY) {
                return replan("off-path soft band " + PathSettings.MAX_TICKS_AWAY + " ticks");
            }
        } else {
            ticksAway = 0;
        }
        if (possiblyOffPath(cur, bestSq, HARD_DIST_SQR)) {
            return replan("off-path hard band (>3 blocks)");
        }
        return null;
    }

    /** Are we further than {@code leniencySq} from the
     *  path? With a mid-FALL carve-out — falling you're far in Y from both ends but not off
     *  path, so judge a fall by the FLAT (XZ) distance to its landing cell instead. */
    private boolean possiblyOffPath(Movement cur, double bestSq, double leniencySq) {
        if (bestSq <= leniencySq) return false;
        if (cur.kind == Movement.Kind.FALL) {
            double dx = (cur.dest.getX() + 0.5) - player.getX();
            double dz = (cur.dest.getZ() + 0.5) - player.getZ();
            return (dx * dx + dz * dz) >= leniencySq;
        }
        return true;
    }

    /** Why a move re-costs to COST_INF, in words — the break-veto on its line, or a note that the
     *  block obstruction is clean (so it failed for support/fall/geometry, e.g. the floor is gone). */
    private String obstruct(NavContext fresh, Movement mv) {
        String why = fresh.diagnoseObstruction(mv.src, mv.dest);
        return why != null ? why : "no break-veto (support/fall/geometry)";
    }

    /** Log a replan trigger with full context (rare event), then return the status. */
    private Status replan(String why) {
        Movement mv = index < path.movements.size() ? path.movements.get(index) : null;
        Constants.LOG.info("[numen-path] REPLAN {} | {}", why, mv != null ? desc(mv) : "feet=" + feet().toShortString());
        return Status.NEEDS_REPLAN;
    }

    /** One-line snapshot of the current move + body state for diagnostics. */
    private String desc(Movement mv) {
        return String.format(
                "%s %s->%s feet=%s y=%.2f grnd=%b sprintDesc=%b t=%d/%.0f away=%d d2path=%.2f idx=%d/%d",
                mv.kind, mv.src.toShortString(), mv.dest.toShortString(), feet().toShortString(),
                player.getY(), player.onGround(), sprintDescend,
                ticksOnCurrent, mv.cost + PathSettings.MOVEMENT_TIMEOUT_TICKS,
                ticksAway, Math.sqrt(distToPathSq()), index, path.movements.size());
    }

    /** Squared distance from the body to the nearest valid cell of the WHOLE path (diagnostics). */
    private double distToPathSq() {
        double best = Double.MAX_VALUE;
        for (Movement m : path.movements) {
            for (BlockPos vp : m.validPositions()) {
                double d = player.distanceToSqr(Vec3.atCenterOf(vp));
                if (d < best) best = d;
            }
        }
        return best;
    }

    /** Track NET forward progress (vs the furthest index reached) so an index that bounces between
     *  snap-back and skip-forward can't mask a stall: warn once at ~3s, and force a replan at ~6s
     *  IF the per-movement timeout is being defeated (low {@code ticksOnCurrent} = the move keeps
     *  restarting). A legit long move has a high {@code ticksOnCurrent} climbing to its own timeout,
     *  so this backstop leaves it alone. Returns a replan status, or null to continue. */
    private Status trackProgress(Movement mv) {
        if (index > maxIndexReached) {
            maxIndexReached = index;
            ticksSinceProgress = 0;
            stuckWarned = false;
            return null;
        }
        // An active dig IS progress even though the index hasn't advanced: a bare-hand
        // stone break runs ~150+ ticks — far past the stall thresholds — and a latched
        // dig always either completes or is cancelled (NO_SHOT never latches). Without
        // this, the stall backstop kills every slow legal dig at 60 ticks and the body
        // grinds the same block forever in replan loops.
        if (digger.current() != null) {
            ticksSinceProgress = 0;
            stuckWarned = false;
            return null;
        }
        ticksSinceProgress++;
        if (ticksSinceProgress >= STUCK_WARN_TICKS && !stuckWarned) {
            stuckWarned = true;
            Constants.LOG.warn("[numen-path] STUCK no forward progress {} ticks | {}",
                    ticksSinceProgress, desc(mv));
        }
        if (ticksSinceProgress >= STUCK_REPLAN_TICKS && ticksOnCurrent < STUCK_WARN_TICKS) {
            return replan("no net progress " + ticksSinceProgress + " ticks (index thrash)");
        }
        return null;
    }

    private void jumpToIndex(int i) {
        index = i;
        resetMoveState();
    }

    private void advance() {
        index++;
        resetMoveState();
    }

    private void resetMoveState() {
        ticksAway = 0;
        ticksOnCurrent = 0;
        sprintAscend = false;
        sprintDescend = false;
        descendDriveTicks = 0;
        digger.cancel();          // a partial dig belongs to the move we just left
        placedThisMove = false;
        if (placeManeuver != null) {
            placeManeuver.stop();
            placeManeuver = null;
        }
    }

    public boolean isPartial() {
        return path.partial;
    }

    public BlockPos pathEnd() {
        return path.end;
    }

    public int remainingMovements() {
        return Math.max(0, path.movements.size() - index);
    }

    /** Estimated ticks left in this segment — Σ cost of the unplayed movements. */
    public double remainingCost() {
        double c = 0.0;
        for (int i = Math.max(index, 0); i < path.movements.size(); i++) {
            c += path.movements.get(i).cost;
        }
        return c;
    }

    public void stop() {
        digger.cancel();
        if (placeManeuver != null) {
            placeManeuver.stop();
            placeManeuver = null;
        }
        InputDriver.halt(player);
        // Release sneak too — pillar/place hold it every tick; without this it lingers
        // through a mid-path replan's planning ticks (the next path's drive() clears it,
        // but only once it starts). Mirrors PlayerNav.stop().
        player.setShiftKeyDown(false);
    }
}
