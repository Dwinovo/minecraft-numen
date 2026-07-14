package com.dwinovo.numen.core.pathing.exec.drive;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.pathing.movement.Movement;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Flat walk one cell (may bridge over air via the executor's scaffold phase). */
final class TraverseDriver extends MoveDriver {

    TraverseDriver(NumenPlayer player, Movement mv, double speed) {
        super(player, mv, speed);
    }

    @Override
    public void drive() {
        // Correct DEPTH before advancing — but "wait for it to settle" is only ever
        // meaningful in LIQUID (bobbing across a water surface, where buoyancy will
        // genuinely pull the body back to the lane; holding forward there porpoises).
        // On LAND the drive is a precondition-free seek: below the lane → hop up;
        // ABOVE the lane → walk toward dest and let vanilla's step-down take the
        // one-block drop. Waiting on solid ground can never settle — it froze the
        // body for the whole movement timeout.
        int feetY = feet().getY();
        if (feetY != mv.dest.getY()) {
            boolean inLiquid = !player.level().getBlockState(feet()).getFluidState().isEmpty();
            if (inLiquid) {
                InputDriver.halt(player);   // let buoyancy re-seat us on the lane
            } else if (feetY < mv.dest.getY()) {
                InputDriver.halt(player);
                InputDriver.jump(player);   // step-up hop
            } else {
                InputDriver.stepToward(player, Vec3.atBottomCenterOf(mv.dest), false);
            }
        } else {
            // On the lane: advance. Don't sprint across a floor we just placed
            // (sprint momentum on a fresh bridge risks carrying past its edge).
            InputDriver.stepToward(player, Vec3.atBottomCenterOf(mv.dest),
                    sprintBase() && mv.toPlace == null);
        }
    }

    @Override
    public boolean arrived() {
        // Traverse SUCCESS only once the floor under dest exists
        // (canWalkOn(dest.below)) — don't declare a bridge traverse done before the
        // placed floor is there. Then feet==dest, or a 1-2 cell sprint overshoot in the
        // move direction.
        BlockPos feet = feet();
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

    /** Live floor check — bridges the planned gap AND any floor dug since planning
     *  (walking into an unexpected hole is a multi-block fall). */
    @Override
    public BlockPos scaffoldCell() {
        return floorUnderDest();
    }

    /** A sneak-bridge over air can't be abandoned until its floor exists — planned
     *  bridge or a live repair of a floor dug since planning, same rule. */
    @Override
    public boolean safeToCancel(boolean scaffoldCommitted, int ticksOnCurrent) {
        return BlockHelper.canWalkOn(player.level(), mv.dest.below());
    }

    /** Walk-while-breaking: keep approaching + sprint while
     *  the front block breaks — but only if neither break cell (dest feet + head) is
     *  something to avoid walking into, and we aren't already pressed against the
     *  block (Chebyshev dist >= 0.83). */
    @Override
    public void duringBreak() {
        if (!BlockHelper.avoidWalkingInto(player.level(), mv.dest)
                && !BlockHelper.avoidWalkingInto(player.level(), mv.dest.above())
                && chebyshevDistTo(mv.dest) >= 0.83) {
            player.zza = 1.0f;
            player.setSprinting(speed >= 1.0);
        }
    }
}
