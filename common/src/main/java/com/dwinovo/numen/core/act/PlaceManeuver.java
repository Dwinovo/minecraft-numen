package com.dwinovo.numen.core.act;

import com.dwinovo.numen.entity.InputDriver;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.task.FailureType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * The live "edge sneak" block placement, shared by {@code place_block} and the
 * pathfinder's bridge / step scaffolding — placing physically, the way a careful
 * player does (a block is never teleport-popped in): HOLD SNEAK (so it can't walk
 * off the ledge), and each tick run the one-shot resolver
 * ({@link Placement#resolveDetailed}) from wherever the body stands right now.
 * A resolved hit → the eyes lock onto it and the press fires. A structured
 * failure branches immediately: NO_SUPPORT and a persistent entity in the cell
 * fail fast with the diagnosis (no timeout grind); an occluded / out-of-reach
 * view keeps the body edging toward the nearest candidate face for the bounded
 * window, and the final diagnosis (with the resolver's suggested stance, exposed
 * via {@link #suggestedStance()}) rides the failure up to the task layer. The
 * press is never trusted as the outcome: the world state is checked after every
 * click, and a vanilla refusal is recorded rather than swallowed.
 *
 * <p>The block source ({@code slotFinder}) and the done-check ({@code placed})
 * are injected so the same maneuver serves a specific block ({@code place_block})
 * or any scaffold block (the pathfinder).
 *
 * <p>Optional {@link Hints} steer orientation: the shuffle target is ordered by the
 * requested {@code axis}, the aim height is biased high/low for the {@code half},
 * and a placement is held back until a dry-run {@code getStateForPlacement}
 * predicts the requested {@code facing}/half — the body keeps working around the
 * block until it can place it the right way round, just like a player walking to
 * the correct side. Hints are inert for the pathfinder.
 */
public final class PlaceManeuver {

    public enum Status { RUNNING, DONE, FAILED }

    /** Orientation the caller wants the placed block to end up with (any field null = don't care).
     *  {@code strict} withholds the press for the whole window and FAILS rather than compromise —
     *  for batch building, where a wrong-way stair means rework; the default takes what it can
     *  get after a grace (the right call for a single LLM-ordered block). */
    public record Hints(Direction facing, Direction.Axis axis, Boolean topHalf, boolean strict) {
        public static final Hints NONE = new Hints(null, null, null, false);
        public Hints(Direction facing, Direction.Axis axis, Boolean topHalf) {
            this(facing, axis, topHalf, false);
        }
        public boolean isEmpty() {
            return facing == null && axis == null && topHalf == null;
        }
    }

    private static final Direction[] FACES = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN, Direction.UP};
    private static final int LIMIT_TICKS = 60;
    /** Ticks an entity may squat in the target cell before the maneuver surfaces the
     *  BLOCKED_BY_ENTITY diagnosis — mobs wander through cells constantly, so a moment
     *  of patience beats failing on a chicken mid-stride. */
    private static final int ENTITY_GRACE_TICKS = 10;

    private final NumenPlayer player;
    private final BlockPos placeAt;
    private final IntSupplier slotFinder;   // hotbar/inventory slot of a placeable block, -1 if none
    private final BooleanSupplier placed;    // is placeAt now filled the way we want?
    private final Hints hints;
    private final Block block;               // for the dry-run; null for the pathfinder (no hints)
    private final Predicate<BlockState> stateVerifier;

    private int ticks;
    private String failReason = "couldn't place";
    private FailureType failType = FailureType.OCCLUDED;
    /** Vanilla's verdict on the last press that left the world unchanged (null = never pressed). */
    private String lastRefusal;
    /** Consecutive ticks spent backing off with our own box still inside the target cell —
     *  a body that can't clear the cell it must fill (boxed in) is a distinct, fast failure. */
    private int selfBlockedTicks;
    private static final int SELF_CLEAR_TICKS = 30;
    /** Consecutive ticks the resolver reported an entity squatting in the target cell. */
    private int entityBlockedTicks;
    /** The resolver's last positional diagnosis (occluded / out of reach) — its message and
     *  suggested stance ride the eventual timeout failure up to the task layer. */
    private PlaceResolution lastDiag;
    /** Progressive punch-out of a soft block occluding every sample ray (tall grass, a
     *  snow layer, a leaf) — instead of grinding the timeout with the view blocked. */
    private Interaction occluderBreak;
    private BlockPos occluderPos;
    /** A press was withheld because the predicted orientation missed the hints (strict-fail
     *  diagnosis), and whether the eventual compromise was already logged. */
    private boolean orientationWithheld;
    private boolean orientationCompromiseLogged;

    /** Horizontal (max-axis) distance to the target cell within which sneak engages — close
     *  enough that the next steps may cross a rim. Farther out the body walks at full speed;
     *  the old always-on sneak was a flat speed tax on every placement approach. */
    private static final double SNEAK_NEAR_DIST = 1.1;

    /** Sneak exactly when it earns its keep: leaning over the work (near the cell) or
     *  committed to the press (the crouch must be active before the click). */
    private void applySneak(boolean aboutToPress) {
        double dist = Math.max(Math.abs(player.getX() - (placeAt.getX() + 0.5)),
                Math.abs(player.getZ() - (placeAt.getZ() + 0.5)));
        player.setShiftKeyDown(aboutToPress || dist < SNEAK_NEAR_DIST);
    }

    /** Pathfinder / orientation-agnostic placement. */
    public PlaceManeuver(NumenPlayer player, BlockPos placeAt,
                         IntSupplier slotFinder, BooleanSupplier placed) {
        this(player, placeAt, slotFinder, placed, Hints.NONE, null);
    }

    /** Oriented placement: {@code block} + {@code hints} drive the support-face / aim choice. */
    public PlaceManeuver(NumenPlayer player, BlockPos placeAt,
                         IntSupplier slotFinder, BooleanSupplier placed,
                         Hints hints, Block block) {
        this(player, placeAt, slotFinder, placed, hints, block, null);
    }

    /** Oriented placement with a full predicted-state verifier for exact build cells. */
    public PlaceManeuver(NumenPlayer player, BlockPos placeAt,
                         IntSupplier slotFinder, BooleanSupplier placed,
                         Hints hints, Block block, Predicate<BlockState> stateVerifier) {
        this.player = player;
        this.placeAt = placeAt.immutable();
        this.slotFinder = slotFinder;
        this.placed = placed;
        this.hints = hints == null ? Hints.NONE : hints;
        this.block = block;
        this.stateVerifier = stateVerifier;
    }

    public String failReason() {
        return failReason;
    }

    /** Structured cause of a {@link Status#FAILED}, for the reactive task layer to branch on. */
    public FailureType failType() {
        return failType;
    }

    public Status tick() {
        if (placed.getAsBoolean()) return Status.DONE;
        if (slotFinder.getAsInt() < 0) {
            failReason = "out of blocks to place";
            failType = FailureType.NO_MATERIAL;
            return Status.FAILED;
        }
        // For a slab/stair `half` hint, bias the click height up (top) or down (bottom)
        // so the placement lands on that half.
        Double aimY = (hints.topHalf() != null)
                ? placeAt.getY() + (hints.topHalf() ? 0.72 : 0.28)
                : null;

        // One-shot resolution from where the body stands right now: a verified hit, or
        // the structured reason none exists. Never fabricate a hit: no resolved hit means
        // fail fast (unfixable causes), keep edging (positional causes), or time out.
        PlaceResolution res = Placement.resolveDetailed(player, placeAt, true, aimY, this::acceptsResolvedHit);
        if (!res.ok()) {
            if (stateVerifier != null || hints.strict()) {
                orientationWithheld = true;
            }
            switch (res.reason()) {
                case NO_SUPPORT -> {
                    // No stance change can create support — fail structured, zero ticks wasted.
                    failReason = res.message();
                    failType = FailureType.NO_SUPPORT;
                    return Status.FAILED;
                }
                case BLOCKED_BY_ENTITY -> {
                    // An entity squats in the cell: every press is doomed while it stays.
                    // Hold position through a short grace (mobs wander), then surface the
                    // diagnosis instead of grinding the timeout into a misleading "occluded".
                    applySneak(false);
                    InputDriver.halt(player);
                    if (++entityBlockedTicks >= ENTITY_GRACE_TICKS) {
                        failReason = res.message();
                        failType = FailureType.ENTITY_BLOCKED;
                        Constants.LOG.info("[numen-path] place gave up at {} after {} ticks: {}",
                                placeAt.toShortString(), ticks, failReason);
                        return Status.FAILED;
                    }
                }
                case NO_LINE_OF_SIGHT, OUT_OF_REACH -> {
                    // Positional: edge toward the nearest candidate face so one comes into
                    // view. The stance ladder above this maneuver is the real "different
                    // angle" mechanism — the body never oscillates here.
                    entityBlockedTicks = 0;
                    lastDiag = res;
                    applySneak(false);
                    // A SOFT occluder named by the resolver (tall grass, a snow layer, a
                    // leaf between the eyes and the face): punch it out instead of edging
                    // around it — the view opens in a few ticks.
                    BlockPos blocking = res.occluder();
                    if (stateVerifier == null && blocking != null && softOccluder(blocking)) {
                        punchOccluder(blocking);
                    } else {
                        stopPunching();
                        Vec3 aim = shuffleAimPoint(aimY);
                        if (aim != null) {
                            edgeToward(aim);
                        } else {
                            InputDriver.halt(player);
                        }
                    }
                }
            }
        } else {
            entityBlockedTicks = 0;
            lastDiag = null;   // a face IS in view — an earlier positional diagnosis is stale
            stopPunching();
            applySneak(true);   // committed to the press — the crouch must precede the click
            BlockHitResult hit = res.hit();
            lookAtResolvedHit(res);
            if (player.getBoundingBox().intersects(new AABB(placeAt))) {
                // Our own collision box is (partly) inside the cell being filled — a placed
                // block may never overlap an entity, so vanilla refuses EVERY such press.
                // The classic case is a same-level scaffold: sneaking lets the body lean past
                // the cell boundary, and pressing toward the face pins it there forever.
                // Back straight off (face still in view); the press fires the tick the box
                // clears the cell.
                player.zza = -1.0f;
                player.xxa = 0.0f;
                player.setSprinting(false);
                // Backing off isn't working (boxed in — e.g. asked to fill the very cell the
                // body stands in inside a 1×1 shaft): fail FAST with the true story instead
                // of grinding the timeout and reporting a misleading "view is blocked".
                if (++selfBlockedTicks >= SELF_CLEAR_TICKS) {
                    failReason = "I'm standing in the very cell to fill at " + placeAt.toShortString()
                            + " and can't step out of it (boxed in). Move me a block away first,"
                            + " or use goto with a higher y — pillaring up places beneath my"
                            + " own feet properly.";
                    failType = FailureType.OCCLUDED;
                    Constants.LOG.info("[numen-path] place gave up at {} after {} ticks: {}",
                            placeAt.toShortString(), ticks, failReason);
                    return Status.FAILED;
                }
            } else {
                selfBlockedTicks = 0;
                // A face is visible and the cell is clear of our body: stand still and press.
                // The press waits one tick for the crouch to register — the sneak is also the
                // edge protection, so the click never precedes it. With orientation hints the
                // press is held back until a dry-run predicts the right state — under strict
                // hints for the WHOLE window (a wrong-way stair is rework), otherwise only
                // through a grace; while held back, keep working around the block.
                BlockState predicted = predict(hit, res.yaw(), res.pitch());
                if (block != null && predicted == null) {
                    failReason = "the selected face at " + placeAt.toShortString()
                            + " would not accept that block from here";
                    failType = FailureType.OCCLUDED;
                    return Status.FAILED;
                }
                boolean matches = stateVerifier == null
                        ? matchesHints(predicted)
                        : predicted != null && stateVerifier.test(predicted);
                boolean mustMatch = stateVerifier != null || !hints.isEmpty();
                boolean placementOk = !mustMatch || matches
                        || (stateVerifier == null && !hints.strict() && ticks > (LIMIT_TICKS * 3) / 5);
                if (!placementOk) {
                    orientationWithheld = true;
                    applySneak(false);   // circling the block, not pressing — full walk speed far out
                    edgeToward(hit.getLocation());
                } else {
                    if (stateVerifier == null && !hints.isEmpty() && !matches && !orientationCompromiseLogged) {
                        orientationCompromiseLogged = true;
                        Constants.LOG.info(
                                "[numen-path] place at {} compromising on orientation after {} ticks"
                                        + " (hints {})",
                                placeAt.toShortString(), ticks, hints);
                    }
                    player.zza = 0.0f;
                    player.xxa = 0.0f;
                    player.setSprinting(false);
                    if (player.isCrouching() && doPlace(hit)) {
                        // Ticks-to-commit is THE speed metric for the per-tick multi-face
                        // resolution — one line per successful maneuver, kept on permanently.
                        Constants.LOG.info("[numen-path] place committed at {} on tick {} (face {})",
                                placeAt.toShortString(), ticks, hit.getDirection());
                        return Status.DONE;
                    }
                }
            }
        }
        if (++ticks > LIMIT_TICKS) {
            // Precedence: a strict-orientation withhold is its own story; then a recorded
            // vanilla refusal (the truest); then the resolver's last diagnosis (occluded /
            // out of reach, LLM-readable); then the generic no-line summary.
            if ((stateVerifier != null || hints.strict()) && orientationWithheld && lastRefusal == null) {
                if (stateVerifier != null) {
                    failReason = "a face at " + placeAt.toShortString() + " was in view, but no stance"
                            + " within the window would place the requested block state";
                } else {
                    failReason = "a face at " + placeAt.toShortString() + " was in view, but no stance"
                            + " within the window would place the block the requested way round"
                            + " (hints " + hints + ") — strict orientation refuses to compromise";
                }
                failType = FailureType.OCCLUDED;
                Constants.LOG.info("[numen-path] place gave up at {} after {} ticks: {}",
                        placeAt.toShortString(), ticks, failReason);
                return Status.FAILED;
            }
            if (lastRefusal != null) {
                failReason = "a support face at " + placeAt.toShortString() + " was in view but every press was "
                        + "refused (" + lastRefusal + ") — the cell itself may be obstructed (an entity, "
                        + "or my own body standing in it)";
                failType = FailureType.OCCLUDED;
            } else if (lastDiag != null) {
                failReason = lastDiag.message();
                failType = lastDiag.reason() == PlaceResolution.Reason.OUT_OF_REACH
                        ? FailureType.OUT_OF_REACH
                        : FailureType.OCCLUDED;
            } else {
                failReason = "couldn't get a clear line to a support face at " + placeAt.toShortString()
                        + " — the view to it is blocked (a wall between, or the body is boxed in). Try a more "
                        + "open spot next to solid ground.";
                failType = FailureType.OCCLUDED;
            }
            Constants.LOG.info("[numen-path] place gave up at {} after {} ticks: {}",
                    placeAt.toShortString(), ticks, failReason);
            return Status.FAILED;
        }
        return Status.RUNNING;
    }

    /** The resolver's suggested reposition (a standable spot from which the best support
     *  face should be visible), from the last positional diagnosis — null when none. The
     *  task layer's recovery ladder navigates here first instead of sampling blind stances. */
    public Vec3 suggestedStance() {
        return lastDiag == null ? null : lastDiag.suggestedStance();
    }

    /** Look at {@code p} and push toward it; sneak (applied by the caller when near the
     *  work) pins the body at the rim instead of letting it walk off. Past the aim point
     *  (leaning right over the face) pushing further forward can only bury the sightline
     *  deeper — back off a step to re-open it instead; the old always-forward push pinned
     *  the body at the rim with the view forever half a degree short. */
    private void edgeToward(Vec3 p) {
        InputDriver.lookAt(player, p);
        double horiz = Math.max(Math.abs(p.x - player.getX()), Math.abs(p.z - player.getZ()));
        player.zza = horiz < 0.29 ? -1.0f : 1.0f;
        player.xxa = 0.0f;
        player.setSprinting(false);
    }

    /** Quick, guilt-free to punch out: near-instant hardness (tall grass, snow layers,
     *  leaves), never a protected block, never a fluid. */
    private boolean softOccluder(BlockPos pos) {
        BlockState state = player.level().getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) return false;
        if (com.dwinovo.numen.core.pathing.util.BlockHelper.shouldAvoidBreaking(player.level(), pos)) {
            return false;
        }
        float hardness = state.getDestroySpeed(player.level(), pos);
        return hardness >= 0.0f && hardness <= 0.2f;
    }

    /** Hold still and swing at the occluder until it pops (progressive, real facing). */
    private void punchOccluder(BlockPos blocking) {
        if (occluderBreak == null || !blocking.equals(occluderPos)) {
            stopPunching();
            occluderPos = blocking.immutable();
            occluderBreak = Interaction.attackBlock(player, occluderPos);
            Constants.LOG.info("[numen-path] place at {} punching occluder {} out of the sightline",
                    placeAt.toShortString(), occluderPos.toShortString());
        }
        player.zza = 0.0f;
        player.xxa = 0.0f;
        player.setSprinting(false);
        switch (occluderBreak.tick()) {
            case DONE, FAILED -> stopPunching();
            case RUNNING -> { }
        }
    }

    private void stopPunching() {
        if (occluderBreak != null) {
            occluderBreak.stop();
            occluderBreak = null;
            occluderPos = null;
        }
    }

    /** The aim point to edge toward while no face is in line of sight: the first
     *  hint-ordered neighbour that can be placed against (face centre, half-biased),
     *  else the (replaceable) target block itself, else nothing. */
    private Vec3 shuffleAimPoint(Double aimY) {
        for (Direction dir : orderedFaces()) {
            BlockPos against = placeAt.relative(dir);
            if (!Placement.canPlaceAgainst(player.level(), against, dir.getOpposite())) continue;
            return new Vec3(
                    (placeAt.getX() + against.getX() + 1.0) * 0.5,
                    aimY != null ? aimY : (placeAt.getY() + against.getY() + 0.5) * 0.5,
                    (placeAt.getZ() + against.getZ() + 1.0) * 0.5);
        }
        return player.level().getBlockState(placeAt).isAir() ? null : Vec3.atCenterOf(placeAt);
    }

    /** Try support faces in an order that tends to yield the requested pillar axis first; the clicked
     *  face's axis becomes the log axis (top face → Y, E/W face → X, N/S face → Z). */
    private Direction[] orderedFaces() {
        if (hints.axis() == null) return FACES;
        return switch (hints.axis()) {
            case Y -> new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
            case X -> new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH, Direction.DOWN, Direction.UP};
            case Z -> new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN, Direction.UP};
        };
    }

    private boolean acceptsResolvedHit(BlockHitResult hit) {
        if (block == null) {
            return true;
        }
        BlockState predicted = predict(hit);
        if (predicted == null) {
            return false;
        }
        if (stateVerifier != null) {
            return stateVerifier.test(predicted);
        }
        if (hints.isEmpty()) {
            return true;
        }
        return matchesHints(predicted) || (!hints.strict() && ticks > (LIMIT_TICKS * 3) / 5);
    }

    private void lookAtResolvedHit(PlaceResolution res) {
        if (res.hasRotation()) {
            player.setYRot(res.yaw());
            player.setYHeadRot(res.yaw());
            player.setXRot(res.pitch());
        } else {
            InputDriver.lookAt(player, res.hit().getLocation());
        }
    }

    /** The blockstate this hit would place right now (vanilla's own rules), or null if unknown. */
    private BlockState predict(BlockHitResult hit) {
        return predict(hit, null, null);
    }

    private BlockState predict(BlockHitResult hit, Float yaw, Float pitch) {
        if (block == null) return null;
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        try {
            if (yaw != null && pitch != null) {
                player.setYRot(yaw);
                player.setXRot(pitch);
            }
            BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND,
                    new ItemStack(block.asItem()), hit);
            BlockState state = block.getStateForPlacement(context);
            if (state == null || !context.canPlace() || !state.canSurvive(player.level(), placeAt)
                    || !placementPlausible(state)) {
                return null;
            }
            return state;
        } catch (RuntimeException e) {
            return null;
        } finally {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
    }

    private boolean placementPlausible(BlockState state) {
        VoxelShape shape = state.getCollisionShape(player.level(), placeAt);
        return shape.isEmpty() || player.level().isUnobstructed(null,
                shape.move(placeAt.getX(), placeAt.getY(), placeAt.getZ()));
    }

    /** Does the predicted state satisfy every hint that applies to it? (Unknown property = no veto.) */
    private boolean matchesHints(BlockState s) {
        if (s == null) return true;
        if (hints.facing() != null) {
            Direction f = facingOf(s);
            if (f != null && f != hints.facing()) return false;
        }
        if (hints.axis() != null) {
            Direction.Axis a = axisOf(s);
            if (a != null && a != hints.axis()) return false;
        }
        if (hints.topHalf() != null) {
            Boolean top = topHalfOf(s);
            if (top != null && top.booleanValue() != hints.topHalf().booleanValue()) return false;
        }
        return true;
    }

    private static Direction facingOf(BlockState s) {
        if (s.hasProperty(BlockStateProperties.FACING)) return s.getValue(BlockStateProperties.FACING);
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return s.getValue(BlockStateProperties.HORIZONTAL_FACING);
        return null;
    }

    private static Direction.Axis axisOf(BlockState s) {
        if (s.hasProperty(BlockStateProperties.AXIS)) return s.getValue(BlockStateProperties.AXIS);
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) return s.getValue(BlockStateProperties.HORIZONTAL_AXIS);
        return null;
    }

    private static Boolean topHalfOf(BlockState s) {
        if (s.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            SlabType t = s.getValue(BlockStateProperties.SLAB_TYPE);
            return t == SlabType.DOUBLE ? null : t == SlabType.TOP;
        }
        if (s.hasProperty(BlockStateProperties.HALF)) {
            return s.getValue(BlockStateProperties.HALF) == Half.TOP;
        }
        return null;
    }

    private boolean doPlace(BlockHitResult hit) {
        int slot = slotFinder.getAsInt();
        if (slot < 0) return false;
        player.holdInHand(slot);   // real hotbar-select / swap-to-hand, not an aliasing overwrite
        Interaction use = Interaction.useBlock(player, hit, InteractionHand.MAIN_HAND);
        use.tick();
        if (placed.getAsBoolean()) return true;
        lastRefusal = use.lastUseOutcome();   // the world is the verdict; keep vanilla's word for the autopsy
        return false;
    }

    /** Release sneak / halt — call when the owning task or move ends. */
    public void stop() {
        stopPunching();
        player.setShiftKeyDown(false);
        InputDriver.halt(player);
    }
}
