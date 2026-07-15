package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.exec.BlockDigger;
import com.dwinovo.numen.core.pathing.exec.PlaceManeuver;
import com.dwinovo.numen.core.pathing.exec.Placement;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.task.base.GoToThenDoTask;
import com.dwinovo.numen.core.task.base.Precondition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code place_block} on the player body: walk within reach (full pathfinding —
 * digs / bridges / climbs to get there), then place like a real player with the
 * shared {@link PlaceManeuver} "edge sneak" — hold sneak, edge to the block's rim
 * so the support face comes into view, look at it, and place natively.
 *
 * <p>A "navigate to a target then do one bounded thing" task, so it grows on
 * {@link GoToThenDoTask}: {@link #buildNav()} walks to the cell, {@link #reached()}
 * gates the place, {@link #act()} runs the placement maneuver.
 *
 * <h2>Recovery ladder (failure path only — the success path is untouched)</h2>
 * Instead of bouncing the first substrate failure back to the LLM, bounded
 * alternative EXECUTIONS of the same place are tried in order:
 * <ol>
 *   <li>the direct edge-sneak maneuver from wherever nav parked us (today's rung);</li>
 *   <li>DIRECTED REPOSITION — when the placement resolver diagnosed the failure with a
 *       suggested stance (a standable spot computed to see the best support face), walk
 *       there first and retry: one nav straight to the geometric answer;</li>
 *   <li>REPOSITION — up to {@value #MAX_ALT_STANCES} alternate stances (adjacent to
 *       the target; anywhere within {@value #STANCE_NEAR_RADIUS} blocks of it; then
 *       on the rim ABOVE it, so the down-face comes into view — the one stance that
 *       works when all four sides are walled in — always excluding cells already
 *       failed from), each followed by a fresh maneuver;</li>
 *   <li>DIG-OUT (on {@code OCCLUDED} only) — clear at most {@value #MAX_OCCLUDERS_DUG}
 *       identifiable, non-protected blocks off the line to a support face, then retry
 *       the maneuver once; if no clean occluder is identifiable the rung is skipped;</li>
 *   <li>exhausted — ONE structured failure saying what was tried.</li>
 * </ol>
 * Implemented as an inline state machine ({@link Phase} + attempt counters) rather
 * than a {@code RecoveryLadder} of child tasks: every rung is a parameter variation
 * (stance goal / dig target) of the SAME two primitives this task already owns
 * (nav + {@link PlaceManeuver}), and keeping them in fields is what makes
 * {@code Suspendable} resume trivial — a preempted tick re-drives them in place.
 * The boundary rule holds: every stance hugs the target (≤{@value #STANCE_NEAR_RADIUS}
 * blocks), no material is acquired, {@code NO_MATERIAL} is never laddered, and the
 * dig rung refuses {@code BlockHelper.shouldAvoidBreaking} blocks.
 */
public final class PlaceBlockCompanionTask extends GoToThenDoTask<PlaceBlockTaskRecord> {

    private static final double REACH_SQR = 4.5 * 4.5;
    private static final double WALK_SPEED = 1.0;
    /** Ladder bound: alternate stances to try after an occluded / unreachable place. */
    private static final int MAX_ALT_STANCES = 3;
    /** Ladder bound: occluding blocks the dig-out rung may clear in total. */
    private static final int MAX_OCCLUDERS_DUG = 2;
    /** Radius of the loosest alternate-stance goal — still hugging the target. */
    private static final double STANCE_NEAR_RADIUS = 2.5;
    /** Neighbour directions a block can be placed against (all six — the resolver
     *  considers UP too: placing against a ceiling is legal). */
    private static final Direction[] SUPPORT_FACES = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
            Direction.DOWN, Direction.UP};

    /** Which ladder rung {@link #act()} is currently executing. */
    private enum Phase { PLACING, REPOSITIONING, DIGGING }

    private Phase phase = Phase.PLACING;
    /** The direct (first) edge-sneak maneuver actually ran — for the exhausted message. */
    private boolean directPlaceTried;
    /** Alternate stances consumed so far (≤ {@link #MAX_ALT_STANCES}). */
    private int altStancesTried;
    /** The dig-out rung has been entered (it can never be re-entered). */
    private boolean digTried;
    /** Blocks the dig-out rung has removed (≤ {@link #MAX_OCCLUDERS_DUG}). */
    private int occludersDug;
    /** Ticks spent in the dig rung — a hard cap so an effectively-unbreakable occluder
     *  (obsidian, no pick) exhausts the rung instead of grinding until the deadline. */
    private int digTicks;
    private static final int DIG_TICK_CAP = 100;
    /** Feet cells a maneuver already failed from — excluded from later stance goals. */
    private final Set<BlockPos> badStances = new HashSet<>();
    /** The resolver's suggested reposition from the last maneuver failure (a standable
     *  spot computed to see the best support face) — consumed by the directed rung. */
    private Vec3 stanceHint;
    /** The directed-reposition rung has been used (it never re-enters). */
    private boolean stanceHintTried;
    /** Lazily-created digger for the dig-out rung. */
    private BlockDigger digger;

    private PlaceManeuver maneuver;
    /** Success copy captured at the place — recorded here (not recomputed in the base's
     *  templated result) so both the "already there" and the maneuver-DONE branches keep
     *  their exact message. */
    private String successMsg = "done";

    public PlaceBlockCompanionTask(NumenPlayer player, PlaceBlockTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        return List.of(
                () -> PlayerInv.count(player.getInventory(), r.item) <= 0
                        ? new Precondition.Failure("no " + r.label + " in inventory to place",
                                FailureType.NO_MATERIAL)
                        : null,
                // Occupancy is the one thing worth a fast, clear message; everything else (support faces,
                // modded placement rules) is left to vanilla's own placement to accept or reject — we don't
                // second-guess it with our own heuristic. Vanilla's `canBeReplaced` is the same test it uses.
                () -> {
                    BlockState existing = player.level().getBlockState(r.pos);
                    if (!existing.isAir() && !existing.canBeReplaced()) {
                        return new Precondition.Failure("target " + coords() + " is already occupied by "
                                + BuiltInRegistries.BLOCK.getKey(existing.getBlock()).getPath(),
                                FailureType.TARGET_LOST);
                    }
                    return null;
                });
    }

    @Override
    protected PlayerNav buildNav() {
        return new PlayerNav(player, r.pos, WALK_SPEED, this::withinReach);
    }

    @Override
    protected boolean reached() {
        // Fold the old top-of-tick "already placed → success" check in here: if the target
        // is already the requested block (even before we're within reach) act() short-circuits
        // to SUCCESS, exactly as the pre-migration tick did.
        return alreadyPlaced() || withinReach();
    }

    @Override
    protected TaskState act() {
        if (alreadyPlaced()) {
            successMsg = "placed " + r.label + " at " + coords() + orientation();
            return TaskState.SUCCESS;
        }
        return switch (phase) {
            case PLACING -> tickPlace();
            case REPOSITIONING -> tickReposition();
            case DIGGING -> tickDig();
        };
    }

    /** Rung "place from here": the edge-sneak maneuver — the pre-ladder success path,
     *  byte-identical when it works. On failure, prerequisite causes kick straight back
     *  to the LLM; everything else advances the ladder. */
    private TaskState tickPlace() {
        if (maneuver == null) {
            if (altStancesTried == 0 && !digTried) directPlaceTried = true;
            maneuver = new PlaceManeuver(player, r.pos,
                    () -> PlayerInv.findSlot(player.getInventory(), r.item),
                    () -> player.level().getBlockState(r.pos).is(r.block),
                    new PlaceManeuver.Hints(r.facing, r.axis, r.topHalf), r.block);
        }
        return switch (maneuver.tick()) {
            case DONE -> {
                successMsg = "placed " + r.label + " at " + coords() + orientation();
                yield TaskState.SUCCESS;
            }
            case FAILED -> {
                FailureType cause = maneuver.failType();
                String why = maneuver.failReason();
                Vec3 hint = maneuver.suggestedStance();
                maneuver.stop();     // release sneak before repositioning / digging
                maneuver = null;
                // NO_MATERIAL is a prerequisite gap — NEVER laddered. NO_SUPPORT is
                // target geometry (no solid neighbour exists at all): no stance change
                // or dig can create support. ENTITY_BLOCKED means a creature squats in
                // the cell: waiting or luring it away is the model's call, no stance
                // change or dig moves it. All three kick back immediately with the
                // resolver's own diagnosis as the model-facing reason.
                if (cause == FailureType.NO_MATERIAL || cause == FailureType.NO_SUPPORT
                        || cause == FailureType.ENTITY_BLOCKED) {
                    fail(why, cause);
                    yield TaskState.FAILED;
                }
                badStances.add(currentFeet());   // don't come back to this stance
                if (hint != null) stanceHint = hint;
                yield advanceLadder(cause, why);
            }
            case RUNNING -> TaskState.RUNNING;
        };
    }

    /** Rung "reposition": drive the stance nav (while within reach of the target the
     *  base loop routes every tick through {@link #act()}, so the ladder ticks it here;
     *  out of reach, the base loop drives the same nav itself). */
    private TaskState tickReposition() {
        if (nav == null) {   // defensive: stance nav lost — fall back to a fresh attempt
            phase = Phase.PLACING;
            return TaskState.RUNNING;
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> {
                phase = Phase.PLACING;   // fresh maneuver from the new stance next tick
                yield TaskState.RUNNING;
            }
            case FAILED -> advanceLadder(nav.failType(),
                    "couldn't reach an alternate stance (" + nav.failReason() + ")");
        };
    }

    /**
     * Rung "dig-out" ({@code OCCLUDED} only, entered once): clear at most
     * {@link #MAX_OCCLUDERS_DUG} identifiable blocks off the line to a support face,
     * then retry the maneuver once. If no clean, safe occluder is identifiable the
     * rung is skipped (exhausts) rather than grinding blindly; protected blocks
     * ({@code shouldAvoidBreaking}) are never dug.
     */
    private TaskState tickDig() {
        // Line clear now? Retry the maneuver (once — the ladder can't re-enter this rung).
        if (Placement.resolve(player, r.pos, true) != null) {
            phase = Phase.PLACING;
            return TaskState.RUNNING;
        }
        if (occludersDug >= MAX_OCCLUDERS_DUG) {
            return exhaust(FailureType.OCCLUDED, "the view to a support face is still blocked"
                    + " after clearing " + occludersDug + " blocks");
        }
        BlockPos occ = findOccluder();
        if (occ == null) {
            return exhaust(FailureType.OCCLUDED, "no single safe occluder is identifiable to"
                    + " clear — the view to a support face is boxed in by terrain");
        }
        if (++digTicks > DIG_TICK_CAP) {
            if (digger != null) digger.cancel();
            return exhaust(FailureType.OCCLUDED, "the occluding block is too hard to break"
                    + " through with what I'm carrying");
        }
        if (digger == null) digger = new BlockDigger(player);
        TaskState out = switch (digger.digStep(occ)) {
            // BROKE_OCCLUDER = the digger's own fallback removed a block in front of OUR
            // occluder — still one block gone, so it counts toward the same bound.
            case BROKE_TARGET, BROKE_OCCLUDER -> {
                occludersDug++;
                yield TaskState.RUNNING;   // re-check the line next tick
            }
            case PROGRESSING -> TaskState.RUNNING;
            case NO_SHOT -> exhaust(FailureType.OCCLUDED,
                    "couldn't get a clear shot at the occluding block");
        };
        // digStep re-verifies line of sight from the STANDING eye and may fall back to
        // "break the incorrect block" on whatever its own ray hits — which findOccluder's
        // CROUCHING-eye vetting never saw. If it latched onto a block we must not break
        // (our footing, the support, the target), abort the rung instead of letting the
        // fallback dig the ground out from under us. (Multi-tick survival digs are caught
        // here on their first PROGRESSING tick, before any break completes; the only
        // insta-broken blocks are soft plants, which are never footing or support.)
        BlockPos latched = digger.current();
        if (latched != null && !latched.equals(occ) && !digSafe(latched)) {
            digger.cancel();
            return exhaust(FailureType.OCCLUDED, "clearing the view would mean breaking"
                    + " a block I must not (my footing or the support itself)");
        }
        return out;
    }

    /** May the digger's fallback legitimately break {@code p} while clearing the view?
     *  Refuses the target cell, our footing, any usable support block, and protected blocks. */
    private boolean digSafe(BlockPos p) {
        BlockPos feet = player.blockPosition();
        if (p.equals(r.pos) || p.equals(feet) || p.equals(feet.below())) return false;
        for (Direction dir : SUPPORT_FACES) {
            BlockPos against = r.pos.relative(dir);
            if (p.equals(against)
                    && Placement.canPlaceAgainst(player.level(), against, dir.getOpposite())) {
                return false;
            }
        }
        return !BlockHelper.shouldAvoidBreaking(player.level(), p);
    }

    /**
     * After a rung failed with {@code cause}: next stance if any remain, else the
     * dig-out rung (once, {@code OCCLUDED} only), else exhausted — one structured
     * failure. {@code detail} is the last failure's model-facing reason, carried
     * into the exhausted message.
     */
    private TaskState advanceLadder(FailureType cause, String detail) {
        // Directed rung first: the resolver already computed where to stand — walk there
        // instead of sampling a blind stance. One shot; a second failure falls through
        // to the generic rungs below.
        if (stanceHint != null && !stanceHintTried) {
            stanceHintTried = true;
            startHintNav(stanceHint);
            stanceHint = null;
            phase = Phase.REPOSITIONING;
            return TaskState.RUNNING;
        }
        if (altStancesTried < MAX_ALT_STANCES) {
            altStancesTried++;
            startStanceNav(altStancesTried);
            phase = Phase.REPOSITIONING;
            return TaskState.RUNNING;
        }
        if (!digTried && cause == FailureType.OCCLUDED) {
            digTried = true;
            phase = Phase.DIGGING;
            return TaskState.RUNNING;
        }
        return exhaust(cause, detail);
    }

    /** The ONE structured give-up: says what was tried, carries the last cause. */
    private TaskState exhaust(FailureType cause, String detail) {
        List<String> tried = new ArrayList<>();
        if (directPlaceTried) tried.add("direct edge-place");
        if (stanceHintTried) tried.add("the stance the placement diagnosis suggested");
        if (altStancesTried > 0) {
            tried.add(altStancesTried + " alternate stance" + (altStancesTried == 1 ? "" : "s"));
        }
        if (digTried) {
            tried.add(occludersDug > 0
                    ? "clearing " + occludersDug + " occluder" + (occludersDug == 1 ? "" : "s")
                    : "looking for an occluder to clear");
        }
        if (tried.isEmpty()) tried.add("walking within reach");
        fail("couldn't place " + r.label + " at " + coords() + " — tried "
                + String.join(", ", tried) + "; " + detail, cause);
        return TaskState.FAILED;
    }

    /** Replace the nav with one toward alternate stance #{@code attempt}. */
    private void startStanceNav(int attempt) {
        stopNav();
        NavGoal goal = stanceGoal(attempt);
        nav = PlayerNav.toGoal(player, () -> goal, WALK_SPEED, () -> goal.isAt(currentFeet()));
    }

    /** Replace the nav with one toward the resolver's suggested stance — the same
     *  avoid-set discipline as the blind stances (never the target cell, its column,
     *  or a stance already failed from), aimed at one computed cell (±1 of slack). */
    private void startHintNav(Vec3 hint) {
        stopNav();
        BlockPos cell = BlockPos.containing(hint);
        Set<BlockPos> avoid = new HashSet<>(badStances);   // copied — the search reads it off-thread
        avoid.add(r.pos);
        avoid.add(r.pos.above());
        NavGoal base = NavGoal.near(cell, 1.0);
        NavGoal goal = new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                return base.isAt(feet) && !avoid.contains(feet);
            }
            @Override public double heuristic(BlockPos from) {
                return base.heuristic(from);
            }
            @Override public BlockPos center() {
                return base.center();
            }
        };
        nav = PlayerNav.toGoal(player, () -> goal, WALK_SPEED, () -> goal.isAt(currentFeet()));
    }

    /** Stance #1: any cell adjacent to the target; stance #2: anywhere within
     *  {@link #STANCE_NEAR_RADIUS} of it; stance #3: on the rim ABOVE the target
     *  (adjacent to the cell above it, feet higher than it), leaning over so the
     *  down-face comes into view — the placement that still works when all four
     *  sides are walled in. All exclude the target cell itself and every stance a
     *  maneuver already failed from — SAME bounded goal, different feet. */
    private NavGoal stanceGoal(int attempt) {
        NavGoal base = switch (attempt) {
            case 1 -> NavGoal.adjacent(r.pos);
            case 2 -> NavGoal.near(r.pos, STANCE_NEAR_RADIUS);
            default -> NavGoal.adjacent(r.pos.above());
        };
        boolean fromAbove = attempt >= 3;
        Set<BlockPos> avoid = new HashSet<>(badStances);   // copied — the search reads it off-thread
        avoid.add(r.pos);                                  // never stand IN the cell being filled
        avoid.add(r.pos.above());                          // ... nor in the column right above it
        return new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                if (fromAbove && feet.getY() <= r.pos.getY()) return false;   // rim stance must be above
                return base.isAt(feet) && !avoid.contains(feet);
            }
            @Override public double heuristic(BlockPos from) {
                return base.heuristic(from);
            }
            @Override public BlockPos center() {
                return base.center();
            }
        };
    }

    /**
     * The single block occluding the sneaking eye's line to a support-face aim point
     * (the same face points the maneuver aims at), or {@code null} when none is cleanly
     * identifiable. Refuses: the support itself, the target cell, our own footing, and
     * any {@code shouldAvoidBreaking} (protected) block.
     */
    private BlockPos findOccluder() {
        Level level = player.level();
        Vec3 eye = new Vec3(player.getX(),
                player.getY() + player.getEyeHeight(Pose.CROUCHING), player.getZ());
        double reach = player.blockInteractionRange();
        BlockPos feet = player.blockPosition();
        for (Direction dir : SUPPORT_FACES) {
            BlockPos against = r.pos.relative(dir);
            if (!Placement.canPlaceAgainst(level, against, dir.getOpposite())) continue;
            Vec3 facePoint = new Vec3(
                    (r.pos.getX() + against.getX() + 1.0) * 0.5,
                    (r.pos.getY() + against.getY() + 0.5) * 0.5,
                    (r.pos.getZ() + against.getZ() + 1.0) * 0.5);
            if (facePoint.distanceToSqr(eye) > reach * reach) continue;
            BlockHitResult hit = level.clip(new ClipContext(
                    eye, facePoint, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.BLOCK) continue;
            BlockPos p = hit.getBlockPos();
            if (p.equals(against) || p.equals(r.pos)) continue;        // that face isn't occluded
            if (p.equals(feet) || p.equals(feet.below())) continue;    // never dig out our own footing
            if (BlockHelper.shouldAvoidBreaking(level, p)) continue;   // protected — not ours to clear
            return p.immutable();
        }
        return null;
    }

    /** Slab-aware feet cell (the pathing node the stance goals judge). */
    private BlockPos currentFeet() {
        return BlockHelper.playerFeet(player.level(), player.getX(), player.getY(), player.getZ());
    }

    /**
     * Nav failures feed the same ladder: the reposition stances double as looser
     * approach goals when the exact-cell approach found no path (rung "reposition"
     * recovers {@code NO_PATH}/{@code BOXED_IN} too, not just {@code OCCLUDED}).
     * Mid-dig the body has nowhere further to fall back to — exhaust.
     */
    @Override
    protected TaskState handleNavFailure(FailureType type, String reason) {
        if (phase == Phase.DIGGING) {
            return exhaust(type, reason);
        }
        String detail = phase == Phase.REPOSITIONING
                ? "couldn't reach an alternate stance (" + reason + ")"
                : "can't reach a spot to place at " + coords() + " (" + reason + ")";
        return advanceLadder(type, detail);
    }

    private boolean alreadyPlaced() {
        return player.level().getBlockState(r.pos).is(r.block);
    }

    private boolean withinReach() {
        return player.onGround() && player.distanceToSqr(Vec3.atCenterOf(r.pos)) <= REACH_SQR;
    }

    private String coords() {
        return r.pos.getX() + "," + r.pos.getY() + "," + r.pos.getZ();
    }

    /** Report the ACTUAL orientation the block landed in (so the model can see + correct it), flagging
     *  any property that didn't come out the way it asked. Empty for blocks with no orientation. */
    private String orientation() {
        BlockState s = player.level().getBlockState(r.pos);
        List<String> parts = new ArrayList<>();
        Direction f = s.hasProperty(BlockStateProperties.FACING) ? s.getValue(BlockStateProperties.FACING)
                : s.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? s.getValue(BlockStateProperties.HORIZONTAL_FACING)
                : null;
        if (f != null) parts.add("facing " + f.getName() + mismatch(r.facing != null && r.facing != f, r.facing));
        Direction.Axis ax = s.hasProperty(BlockStateProperties.AXIS) ? s.getValue(BlockStateProperties.AXIS)
                : s.hasProperty(BlockStateProperties.HORIZONTAL_AXIS) ? s.getValue(BlockStateProperties.HORIZONTAL_AXIS)
                : null;
        if (ax != null) parts.add("axis " + ax.getName() + mismatch(r.axis != null && r.axis != ax, r.axis));
        Boolean top = topHalf(s);
        if (top != null) {
            parts.add((top ? "top" : "bottom") + " half"
                    + mismatch(r.topHalf != null && !r.topHalf.equals(top), r.topHalf == null ? null : (r.topHalf ? "top" : "bottom")));
        }
        return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
    }

    private static Boolean topHalf(BlockState s) {
        if (s.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            SlabType t = s.getValue(BlockStateProperties.SLAB_TYPE);
            return t == SlabType.DOUBLE ? null : t == SlabType.TOP;
        }
        if (s.hasProperty(BlockStateProperties.HALF)) {
            return s.getValue(BlockStateProperties.HALF) == Half.TOP;
        }
        return null;
    }

    private static String mismatch(boolean differs, Object wanted) {
        return differs ? " [wanted " + wanted + "]" : "";
    }

    @Override
    protected void cleanup() {
        super.cleanup();   // stopNav + clear the path overlay
        if (maneuver != null) maneuver.stop();
        if (digger != null) digger.cancel();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("block", r.label);
        data.put("x", r.pos.getX());
        data.put("y", r.pos.getY());
        data.put("z", r.pos.getZ());
        return data;
    }

    @Override
    protected String successMessage() {
        return successMsg;
    }

    @Override
    protected String timeoutMessage() {
        return "timed out before placing " + r.label + " at " + coords();
    }

    @Override
    protected String cancelledMessage() {
        return "place_block interrupted";
    }
}
