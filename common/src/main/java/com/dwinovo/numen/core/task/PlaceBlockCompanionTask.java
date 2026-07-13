package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.PlaceManeuver;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.base.GoToThenDoTask;
import com.dwinovo.numen.core.task.base.Precondition;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code place_block} on the player body: walk within reach (full pathfinding —
 * digs / bridges / climbs to get there), then place like a real player with the
 * shared {@link PlaceManeuver} "edge sneak" — hold sneak, edge to the block's rim
 * so the support face comes into view, look at it, and place natively.
 *
 * <p>A "navigate to a target then do one bounded thing" task, so it grows on
 * {@link GoToThenDoTask}: {@link #buildNav()} walks to the cell, {@link #reached()}
 * gates the place, {@link #act()} runs the placement maneuver.
 */
public final class PlaceBlockCompanionTask extends GoToThenDoTask<PlaceBlockTaskRecord> {

    private static final double REACH_SQR = 4.5 * 4.5;
    private static final double WALK_SPEED = 1.0;

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
        if (maneuver == null) {
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
                fail(maneuver.failReason(), maneuver.failType());
                yield TaskState.FAILED;
            }
            case RUNNING -> TaskState.RUNNING;
        };
    }

    /**
     * Reproduce the old nav-failure copy exactly (wrap the nav's own reason), still a plain
     * give-up — no recovery ladder is attached this stage. Overridden only because the base
     * default reports the bare {@code reason}, which would change the model-facing string.
     */
    @Override
    protected TaskState handleNavFailure(FailureType type, String reason) {
        fail("can't reach a spot to place at " + coords() + " (" + reason + ")", type);
        return TaskState.FAILED;
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
