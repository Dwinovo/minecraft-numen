package com.dwinovo.numen.core.pathing.exec.drive;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.pathing.movement.Movement;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Step down one block: aim one block past
 * dest (fakeDest) for the first ~20 ticks WHILE still near the start
 * (fromStart&lt;1.25), to carry momentum off the ledge; then aim at dest. Stop
 * pushing once horizontally on dest so gravity settles us cleanly.
 */
public final class DescendDriver extends MoveDriver {

    /** Sprint-carried into this descend (optimizer, re-asserted per tick, never latched). */
    private boolean sprint;
    /** Ticks we've actually DRIVEN this descend
     *  (incremented only inside the drive branch, NOT on break/idle ticks) — gates the 20-tick
     *  fakeDest momentum window so a descend that breaks first doesn't burn it before moving. */
    private int driveTicks;

    DescendDriver(NumenPlayer player, Movement mv, double speed) {
        super(player, mv, speed);
    }

    /** Optimizer channel: sprint this descend this tick (cleared and re-decided per tick). */
    public void setSprint(boolean sprint) {
        this.sprint = sprint;
    }

    public boolean isSprinting() {
        return sprint;
    }

    @Override
    public void drive() {
        if (safeMode()) {
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
            // driveTicks++ < 20: post-increment inside the drive branch (counts only
            // ticks we actually drive), gating the fakeDest momentum carry.
            boolean earlyWindow = driveTicks++ < 20 && horizontalDistTo(mv.src) < 1.25;
            // Hold the forward (fakeDest) aim through the airborne phase to avoid the jarring ~180°
            // yaw snap (we hard-set yaw rather than rotating smoothly) — but ONLY on a controlled,
            // non-sprint descend, where overshoot is ≤1 cell (dest/fakeDest, both count as arrived).
            // A sprint-carried descend (a stair chain, 2+ in a row) keeps the exact-aim rule —
            // switch to dest once past the early window — so its momentum is steered back onto the
            // planned line instead of drifting off it.
            boolean commitForward = (!player.onGround() && !sprint) || earlyWindow;
            Vec3 aim = commitForward
                    ? Vec3.atBottomCenterOf(new BlockPos(
                            2 * mv.dest.getX() - mv.src.getX(),
                            mv.dest.getY(),
                            2 * mv.dest.getZ() - mv.src.getZ()))
                    : Vec3.atBottomCenterOf(mv.dest);
            InputDriver.stepToward(player, aim, sprint);
        } else {
            InputDriver.halt(player);
        }
    }

    @Override
    public boolean arrived() {
        // Descend success: feet at dest OR the overshoot fakeDest, AND
        // settled within 0.5 of dest.y (or dest is liquid) — don't advance while still
        // falling through the destination cell.
        BlockPos feet = feet();
        BlockPos fakeDest = new BlockPos(
                2 * mv.dest.getX() - mv.src.getX(), mv.dest.getY(), 2 * mv.dest.getZ() - mv.src.getZ());
        if (!feet.equals(mv.dest) && !feet.equals(fakeDest)) return false;
        return !player.level().getBlockState(mv.dest).getFluidState().isEmpty()
                || player.getY() - mv.dest.getY() < 0.5;
    }

    /** Descend safe mode: a hazard just past dest (sprint-overshoot risk)
     *  OR the skip-to-ascend overshoot-glitch geometry (a wall at foot level with air above).
     *  Public: the executor's sprint optimizer gates its descend-sprint decision on this. */
    public boolean safeMode() {
        if (hazardJustPast()) return true;
        int dx = mv.dest.getX() - mv.src.getX();
        int dz = mv.dest.getZ() - mv.src.getZ();
        if (dx == 0 && dz == 0) return false;
        BlockPos into = mv.dest.offset(dx, 0, dz);
        return !BlockHelper.canWalkThrough(player.level(), into)
                && BlockHelper.canWalkThrough(player.level(), into.above())
                && BlockHelper.canWalkThrough(player.level(), into.above(2));
    }

    @Override
    public String debugFlags() {
        return "sprintDesc=" + sprint;
    }
}
