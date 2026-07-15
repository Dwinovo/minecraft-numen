package com.dwinovo.numen.core.pathing.exec;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavContext;
import com.dwinovo.numen.core.pathing.calc.Path;
import com.dwinovo.numen.core.pathing.exec.drive.AscendDriver;
import com.dwinovo.numen.core.pathing.exec.drive.DescendDriver;
import com.dwinovo.numen.core.pathing.exec.drive.FallDriver;
import com.dwinovo.numen.core.pathing.exec.drive.MoveDriver;
import com.dwinovo.numen.core.pathing.movement.Movement;
import com.dwinovo.numen.core.pathing.movement.Moves;
import com.dwinovo.numen.core.pathing.util.ActionCosts;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.pathing.util.PathSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * Walks a computed {@link Path} on a companion {@link NumenPlayer} body. This class
 * is the KIND-BLIND spine: re-localization (match real feet against nearby
 * movements' {@code validPositions} and resync the index instead of replanning on
 * every push/overshoot), the watchdog pipeline (off-path bands, net-progress stall,
 * cost re-verification, per-movement timeout, premise checks), the uniform
 * break/place phases, and advancement. Everything kind-SPECIFIC — per-tick input
 * pressing, arrival detection, cancel safety, world premises, per-move state —
 * lives in the current movement's {@link MoveDriver}; the executor never branches
 * on {@link Movement.Kind} except in the cross-move sprint/splice optimizer, which
 * is the one sanctioned puncture (it may downcast the current driver to poke sprint
 * carry flags — an optimization channel, never a correctness dependency).
 *
 * <p>Each movement runs as: clear its {@code toBreak} obstructions (the native
 * {@link BlockDigger} — {@code handleBlockBreakAction} START/STOP, survival
 * timed, drops fall and the player picks them up natively), place its
 * {@code toPlace} scaffold ({@link PlaceManeuver}), then let the driver step the
 * body toward {@code dest} until it reports arrival.
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
    /** May execution modify terrain? The dig and scaffold phases are normally driven by the
     *  plan (whose {@link NavContext} already prices breaks/places {@code COST_INF} when this
     *  is false, so no such move exists), but the scaffold phase can ALSO fire from a live
     *  floor check ({@code MoveDriver.scaffoldCell}) — this flag bars that path too. */
    private final boolean terrainMods;

    private int index = 0;
    private int ticksOnCurrent = 0;
    private int ticksAway = 0;
    private boolean placedThisMove = false;
    /** The current movement's driver — owns all kind-specific behavior and per-move
     *  mutable state (sprint carry, tick counters). Recreated whenever {@link #index}
     *  moves; state dies with it. */
    private MoveDriver driver;
    /** The index {@link #driver} was built for (a driver never outlives its move). */
    private int driverIndex = -1;
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
        this(player, path, speed, ctxSupplier, true);
    }

    public PlayerPathExecutor(NumenPlayer player, Path path, double speed,
                              java.util.function.Supplier<NavContext> ctxSupplier,
                              boolean terrainMods) {
        this.player = player;
        this.path = path;
        this.speed = speed;
        this.ctxSupplier = ctxSupplier;
        this.terrainMods = terrainMods;
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
        MoveDriver drv = currentDriver();
        Status progress = trackProgress(mv);
        if (progress != null) return progress;

        // Submerged = off-plan, EXCEPT when the current driver claims submersion (a
        // water-column swim-up is *meant* to run submerged): the surface plane is where
        // traverse/ascend live, so being underwater on one of those means we were
        // knocked under → replan from a valid surface cell.
        if (player.isUnderWater() && !drv.allowsSubmersion()) {
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
        //    ticks, the block doesn't pop instantly. The driver may add kind-specific
        //    inputs (e.g. a traverse keeps approaching the breaking block).
        BlockPos obstruction = nextObstruction(mv);
        if (obstruction != null) {
            digger.dig(obstruction);   // faces + breaks the block (halts the body)
            drv.duringBreak();
            return Status.RUNNING;
        }

        // 2) Make the cell this move needs solid, if any — the driver derives the need
        //    from the LIVE world each tick (a floor the plan assumed may have been dug
        //    since, the path's own earlier breaks included; the plan's toPlace is a
        //    pricing prediction, not the trigger). The live "edge sneak" maneuver:
        //    hold sneak, edge to the rim, look at the support face, place.
        BlockPos scaffold = placedThisMove ? null : drv.scaffoldCell();
        if (scaffold != null && !terrainMods) {
            // A live floor-restoration place is a world edit — barred under
            // modify_terrain:false. Replan instead: the fresh search (same flag)
            // routes around the hole or fails clean.
            return replan("floor missing at " + scaffold.toShortString()
                    + " but terrain modification is disabled");
        }
        if (scaffold != null) {
            if (placeManeuver == null) {
                BlockPos cell = scaffold.immutable();
                placeManeuver = new PlaceManeuver(player, cell,
                        () -> MoveDriver.scaffoldSlot(player),
                        () -> BlockHelper.canWalkOn(player.level(), cell));
            }
            switch (placeManeuver.tick()) {
                case DONE -> {
                    placedThisMove = true;
                    placeManeuver = null;
                }
                case FAILED -> {
                    String why = placeManeuver.failReason();
                    placeManeuver = null;
                    return replan("scaffold place failed: " + why);   // out of blocks / no angle → replan
                }
                case RUNNING -> {
                    return Status.RUNNING;
                }
            }
        } else if (placeManeuver != null) {
            // The need vanished (floor restored some other way) — release the maneuver.
            placeManeuver.stop();
            placeManeuver = null;
        }

        // 3) Drive toward dest — the driver owns all kind-specific input pressing.
        if (drv.arrived()) {
            advance();
            return Status.RUNNING;
        }
        // Premise check: the world no longer satisfies what this move's plan assumed
        // (e.g. a pillar column whose floor never got placed). Driving can only grind
        // the movement timeout — surrender NOW and replan against the world as it is.
        String broken = drv.premiseBroken();
        if (broken != null) {
            return replan(broken);
        }
        player.setShiftKeyDown(false);   // default; drivers re-enable per tick
        openDoorsForMove(mv);            // a shut wooden door/gate ahead → open it, don't break it
        drv.drive();
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

    /** The feet cell, nudged up 0.1251 (so soul-sand /
     *  farmland sink doesn't read us a block low), and — when that cell is a SLAB — taken
     *  as the cell ABOVE it. That slab adjustment is what lets standing on a bottom slab
     *  read as the move's dest (moves onto a slab target the cell above the slab). */
    private BlockPos feet() {
        return BlockHelper.playerFeet(player.level(), player.getX(), player.getY(), player.getZ());
    }

    /** The driver for the current index, created on demand and discarded whenever the
     *  index moves — per-move state lives and dies with it. */
    private MoveDriver currentDriver() {
        if (driver == null || driverIndex != index) {
            if (driver != null) driver.stop();
            driver = MoveDriver.of(player, path.movements.get(index), speed);
            driverIndex = index;
        }
        return driver;
    }

    /** Carry/aim of an overshooting fall. */
    private record FallOverride(Vec3 aim, BlockPos fallDest, int spliceIndex) {}

    /** Fall-override handling: if the current FALL lines up with the
     *  corridor below it, either splice past it (once we've landed at the extended dest) or
     *  set the forward aim so driveFall sprints off the ledge. Recomputed each tick. */
    private void tryFallOverride() {
        if (index >= path.movements.size()) return;
        Movement cur = path.movements.get(index);
        if (cur.kind != Movement.Kind.FALL) return;
        FallDriver fall = (FallDriver) currentDriver();   // optimizer channel
        fall.setOverrideAim(null);                        // recomputed each tick
        FallOverride fo = overrideFall(cur);
        if (fo == null) return;
        if (feet().equals(fo.fallDest)) {
            jumpToIndex(fo.spliceIndex);   // landed at the overshoot dest — continue past the spliced traverses
            return;
        }
        fall.setOverrideAim(fo.aim);
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

    /** Sprint-skip: if the current
     *  traverse leads straight into a sprintable ascend and we're centred enough to commit
     *  (skipNow), skip the traverse's arrival and sprint up the step. Advances the index. */
    private boolean trySprintSkip() {
        // Descend-sprint is re-decided every tick (sprint is re-asserted per tick, never
        // latched), so clear it up front; the descend branch below re-asserts it when still
        // applicable. (Optimizer channel: the downcast pokes are sanctioned — see class doc.)
        if (driver instanceof DescendDriver d && driverIndex == index) {
            d.setSprint(false);
        }
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
            ((AscendDriver) currentDriver()).setSprint(true);
            return true;
        }
        // Descend → sprintable continuation (canSprintFromDescendInto): carry sprint
        // down the descend into a same-direction descend chain or a traverse/diagonal, and
        // hand off the moment we reach the descend's landing cell.
        //   safeMode gate: we gate on plain `!safeMode` — intentionally NOT sprinting
        //   into the skip-to-ascend clip-glitch (a laxer gate could still sprint to clip
        //   through it); consistent with the stricter-safety stance taken elsewhere in
        //   this executor.
        if (cur.kind == Movement.Kind.DESCEND
                && currentDriver() instanceof DescendDriver descend
                && !descend.safeMode()
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
            descend.setSprint(true);   // sprint this descend this tick
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
        // EVERY cost-based cancel is gated on the driver's safeToCancel():
        // a movement mid-commit (airborne, mid-jump, mid-place, bridging over air) must
        // not be abandoned to a replan, or the body is dropped into a bad state. While
        // not cancellable, let the move finish; the movement timeout still bounds a stall.
        if (!currentDriver().safeToCancel(placeManeuver != null || placedThisMove, ticksOnCurrent)) {
            return null;
        }
        // Movements whose OWN execution mutates the world — pillar places a block
        // underfoot, dig-down breaks the floor — would self-trigger COST_INF the
        // instant they act (regeneration no longer emits them). Skip live re-costing
        // for those; the movement timeout still guards a genuine stall. Movements
        // that only consume the world (break an obstruction) get cheaper, never
        // INF, so they're safe to verify.
        if (!MoveDriver.selfMutating(cur.kind)) {
            double liveCur = recost(fresh, cur);
            if (liveCur >= ActionCosts.COST_INF) {
                return replan("current move now impossible (" + obstruct(fresh, cur) + ")");
            }
            // Cost-drift cancel ONLY for GUESSED prices (the move's chunk wasn't captured in
            // the planning snapshot, so it was priced off optimistic-AIR misses; now that
            // we're close and the terrain is real, a big rise means the plan was built on
            // wrong data → replan). An accurately-priced move that got dearer is the path's
            // own earlier actions interfering with a later step (scaffolds placed, blocks
            // dug) — a replan sees the same world and re-emits the same step at the new
            // price, so cancelling just buys a search and a lost second. Impossibility
            // (COST_INF, above) still always cancels.
            if (!cur.calculatedWhileLoaded()
                    && liveCur - cur.cost > PathSettings.MAX_COST_INCREASE) {
                return replan(String.format(
                        "current move got too expensive (guessed %.1f off-snapshot, live %.1f)",
                        cur.cost, liveCur));
            }
        }
        if (costCheckIndex != index) {
            costCheckIndex = index;
            int last = path.movements.size() - 1;
            // Window: COST_VERIFICATION_LOOKAHEAD(5) → 4 movements ahead
            // (index+1 .. index+4), bounded by the last movement.
            for (int i = index + 1; i < index + PathSettings.COST_VERIFICATION_LOOKAHEAD && i <= last; i++) {
                Movement ahead = path.movements.get(i);
                if (!MoveDriver.selfMutating(ahead.kind)
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

    /** The next still-solid block this move must break, or null when its path is clear. */
    private BlockPos nextObstruction(Movement mv) {
        for (BlockPos pos : mv.toBreak) {
            if (!player.level().getBlockState(pos).isAir()) {
                return pos;
            }
        }
        return null;
    }

    // ---- re-localization ----

    /** Forward coherence window: matches are considered only up to this many moves
     *  ahead — a match from a later portion of a self-crossing path (spiral stair,
     *  switchback) is more likely a lie than a skip. Out-of-window displacement
     *  escalates (off-path bands → replan) instead of teleporting the index. */
    private static final int FORWARD_MATCH_WINDOW = 6;

    private Status relocalize() {
        BlockPos feet = feet();
        Movement cur = path.movements.get(index);
        // Stay-put hysteresis: while the CURRENT move still explains the feet, never
        // re-decide. Adjacent moves' valid sets overlap (diagonal corners, fall
        // columns); re-classifying every tick on overlapping sets is what ping-pongs.
        if (cur.validPositions().contains(feet)) {
            ticksAway = 0;
            return null;
        }
        // Rewind at most ONE step (a shove back over the seam). Anything further back
        // needs no re-index at all: driving seeks the current move's target and works
        // from behind (the terrain we just traversed is passable); a genuine stall is
        // caught by the net-progress watchdog and escalates to a replan.
        if (index > 0 && path.movements.get(index - 1).validPositions().contains(feet)) {
            jumpToIndex(index - 1);
            return null;
        }
        // Forward skip: from +3 (a movement signals its own completion, e.g. a
        // sneak-place — +1/+2 must not be preempted), bounded by the coherence window,
        // and never across an unexecuted world edit — "matching" past a pending dig
        // or place would declare edits done that never happened.
        int last = path.movements.size() - 1;
        int limit = Math.min(last, index + FORWARD_MATCH_WINDOW);
        for (int i = index + 3; i <= limit; i++) {
            if (!noPendingEditsBetween(index, i - 2)) break;
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

    /** No move in {@code [from, to]} still has world edits to perform (a solid block in
     *  its {@code toBreak}, or an unbuilt {@code toPlace} floor) — the gate for skipping
     *  those moves during forward matching. */
    private boolean noPendingEditsBetween(int from, int to) {
        for (int i = from; i <= to && i < path.movements.size(); i++) {
            Movement m = path.movements.get(i);
            if (nextObstruction(m) != null) return false;
            if (m.toPlace != null && !BlockHelper.canWalkOn(player.level(), m.toPlace)) return false;
        }
        return true;
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
        String flags = (driver != null && driverIndex == index) ? driver.debugFlags() : "";
        return String.format(
                "%s %s->%s feet=%s y=%.2f grnd=%b%s t=%d/%.0f away=%d d2path=%.2f idx=%d/%d",
                mv.kind, mv.src.toShortString(), mv.dest.toShortString(), feet().toShortString(),
                player.getY(), player.onGround(), flags.isEmpty() ? "" : " " + flags,
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
        digger.cancel();          // a partial dig belongs to the move we just left
        placedThisMove = false;
        if (placeManeuver != null) {
            placeManeuver.stop();
            placeManeuver = null;
        }
        // Per-move driver state (sprint carry, tick counters) dies with its driver.
        if (driver != null) {
            driver.stop();
            driver = null;
            driverIndex = -1;
        }
    }

    /** Ticks since NET forward progress (index advance past the furthest reached, or an
     *  active dig) — the executor's liveness signal, exposed for progress-lease deadlines. */
    public int ticksSinceProgress() {
        return ticksSinceProgress;
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
        if (driver != null) {
            driver.stop();
            driver = null;
            driverIndex = -1;
        }
        InputDriver.halt(player);
        // Release sneak too — pillar/place hold it every tick; without this it lingers
        // through a mid-path replan's planning ticks (the next path's drive() clears it,
        // but only once it starts). Mirrors PlayerNav.stop().
        player.setShiftKeyDown(false);
    }
}
