package com.dwinovo.numen.core.pathing.exec;

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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * The live "edge sneak" block placement, shared by {@code place_block} and the
 * pathfinder's bridge / step scaffolding — placing physically, the way a careful
 * player does (a block is never teleport-popped in): HOLD SNEAK (so it can't walk
 * off the ledge), and every tick re-resolve ALL candidate support faces (four
 * sides, below, and the replaceable target itself) by real raycast from wherever
 * the body stands right now. The instant ANY face is in honest line of sight, the
 * eyes lock onto it and the press fires — the eyes do the searching, tick by tick;
 * the body only edges toward a face when none is visible yet. The press is never
 * trusted as the outcome: the world state is checked after every click, and a
 * vanilla refusal is recorded rather than swallowed.
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

    /** Orientation the caller wants the placed block to end up with (any field null = don't care). */
    public record Hints(Direction facing, Direction.Axis axis, Boolean topHalf) {
        public static final Hints NONE = new Hints(null, null, null);
        public boolean isEmpty() {
            return facing == null && axis == null && topHalf == null;
        }
    }

    private static final Direction[] FACES = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};
    private static final int LIMIT_TICKS = 60;

    private final NumenPlayer player;
    private final BlockPos placeAt;
    private final IntSupplier slotFinder;   // hotbar/inventory slot of a placeable block, -1 if none
    private final BooleanSupplier placed;    // is placeAt now filled the way we want?
    private final Hints hints;
    private final Block block;               // for the dry-run; null for the pathfinder (no hints)

    private int ticks;
    private String failReason = "couldn't place";
    private FailureType failType = FailureType.OCCLUDED;
    /** Vanilla's verdict on the last press that left the world unchanged (null = never pressed). */
    private String lastRefusal;

    /** Pathfinder / orientation-agnostic placement. */
    public PlaceManeuver(NumenPlayer player, BlockPos placeAt,
                         IntSupplier slotFinder, BooleanSupplier placed) {
        this(player, placeAt, slotFinder, placed, Hints.NONE, null);
    }

    /** Oriented placement: {@code block} + {@code hints} drive the support-face / aim choice. */
    public PlaceManeuver(NumenPlayer player, BlockPos placeAt,
                         IntSupplier slotFinder, BooleanSupplier placed,
                         Hints hints, Block block) {
        this.player = player;
        this.placeAt = placeAt.immutable();
        this.slotFinder = slotFinder;
        this.placed = placed;
        this.hints = hints == null ? Hints.NONE : hints;
        this.block = block;
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
        // Physical impossibility check: no solid neighbour to place against AND nothing in
        // the cell to click-replace — no stance change can create support, so fail structured.
        if (!Placement.hasAnySupport(player.level(), placeAt)
                && player.level().getBlockState(placeAt).isAir()) {
            failReason = "can't place at " + placeAt.toShortString() + " — nothing solid beside or below "
                    + "it to place against (it's over air). Pick a cell that touches solid ground.";
            failType = FailureType.NO_SUPPORT;
            return Status.FAILED;
        }

        player.setShiftKeyDown(true);   // sneak: never walk off the ledge while working
        // For a slab/stair `half` hint, bias the click height up (top) or down (bottom)
        // so the placement lands on that half.
        Double aimY = (hints.topHalf() != null)
                ? placeAt.getY() + (hints.topHalf() ? 0.72 : 0.28)
                : null;

        // Re-resolve EVERY candidate face fresh each tick, by real raycast from where the
        // body stands right now — whichever face is genuinely in line of sight wins this
        // tick. Never fabricate a hit: no face in view means keep edging, or time out.
        BlockHitResult hit = Placement.resolve(player, placeAt, true, aimY);
        if (hit != null) {
            // A face is visible: pin toward it (sneak holds the body at the rim) and press.
            // The press waits one tick for the crouch to register — the sneak is also the
            // edge protection, so the click never precedes it. With orientation hints the
            // press is held back until a dry-run predicts the right state — but never past
            // a grace window.
            edgeToward(hit.getLocation());
            boolean orientationOk = hints.isEmpty()
                    || ticks > (LIMIT_TICKS * 3) / 5         // grace: take what we can get
                    || matchesHints(predict(hit));
            if (orientationOk && player.isCrouching() && doPlace(hit)) {
                // Ticks-to-commit is THE speed metric for the per-tick multi-face resolution —
                // one line per successful maneuver, cheap enough to keep on permanently.
                Constants.LOG.info("[numen-path] place committed at {} on tick {} (face {})",
                        placeAt.toShortString(), ticks, hit.getDirection());
                return Status.DONE;
            }
        } else {
            // No face in sight yet: edge (sneaking) toward the nearest candidate face so one
            // comes into view. The stance ladder above this maneuver is the real "different
            // angle" mechanism — the body never oscillates here.
            Vec3 aim = shuffleAimPoint(aimY);
            if (aim != null) {
                edgeToward(aim);
            } else {
                InputDriver.halt(player);
            }
        }
        if (++ticks > LIMIT_TICKS) {
            failReason = lastRefusal != null
                    ? "a support face at " + placeAt.toShortString() + " was in view but every press was "
                        + "refused (" + lastRefusal + ") — the cell itself may be obstructed (an entity, "
                        + "or my own body standing in it)"
                    : "couldn't get a clear line to a support face at " + placeAt.toShortString()
                        + " — the view to it is blocked (a wall between, or the body is boxed in). Try a more "
                        + "open spot next to solid ground.";
            failType = FailureType.OCCLUDED;
            Constants.LOG.info("[numen-path] place gave up at {} after {} ticks: {}",
                    placeAt.toShortString(), ticks, failReason);
            return Status.FAILED;
        }
        return Status.RUNNING;
    }

    /** Look at {@code p} and push toward it; sneak (held by the caller every tick) pins
     *  the body at the rim instead of letting it walk off. */
    private void edgeToward(Vec3 p) {
        InputDriver.lookAt(player, p);
        player.zza = 1.0f;
        player.xxa = 0.0f;
        player.setSprinting(false);
    }

    /** The aim point to edge toward while no face is in line of sight: the first
     *  hint-ordered neighbour that can be placed against (face centre, half-biased),
     *  else the (replaceable) target block itself, else nothing. */
    private Vec3 shuffleAimPoint(Double aimY) {
        for (Direction dir : orderedFaces()) {
            BlockPos against = placeAt.relative(dir);
            if (!Placement.canPlaceAgainst(player.level(), against)) continue;
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
            case Y -> new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
            case X -> new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH, Direction.DOWN};
            case Z -> new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};
        };
    }

    /** The blockstate this hit would place right now (vanilla's own rules), or null if unknown. */
    private BlockState predict(BlockHitResult hit) {
        if (block == null) return null;
        try {
            return block.getStateForPlacement(
                    new BlockPlaceContext(player, InteractionHand.MAIN_HAND, new ItemStack(block.asItem()), hit));
        } catch (RuntimeException e) {
            return null;
        }
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
        player.setShiftKeyDown(false);
        InputDriver.halt(player);
    }
}
