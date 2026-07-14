package com.dwinovo.numen.core.pathing.exec.drive;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.pathing.movement.Movement;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Step up one block. Drive forward toward
 * dest, then JUMP when the body is ALIGNED to the move axis (small lateral
 * drift) and either the head is clear or we're close enough — critically NOT
 * gated on forward speed, so hitting the step (which zeroes forward speed) still
 * triggers the jump instead of stalling against the wall.
 */
public final class AscendDriver extends MoveDriver {

    /** Sprint-carried into this ascend (the optimizer skipped the traverse before it):
     *  sprint up the step instead of jumping from a standstill. */
    private boolean sprint;

    AscendDriver(NumenPlayer player, Movement mv, double speed) {
        super(player, mv, speed);
    }

    /** Optimizer channel: sprint-skip into this ascend. */
    public void setSprint(boolean sprint) {
        this.sprint = sprint;
    }

    @Override
    public void drive() {
        // Sprint up only when we sprint-skipped into this ascend (sprintableAscend);
        // a normal ascend jumps from a standstill.
        InputDriver.stepToward(player, Vec3.atBottomCenterOf(mv.dest), sprint);
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
        if (headBonkClear()) {
            InputDriver.jump(player);   // head's clear above — jump now (InputDriver gates onGround)
            return;
        }
        if (flatDistToNext > 1.2 || sideDist > 0.2) {
            return;   // too far / off-axis — would bonk an adjacent block
        }
        InputDriver.jump(player);
    }

    @Override
    public boolean arrived() {
        // Ascend success: feet==dest OR one cell further horizontally
        // (the jump can carry us a cell past dest).
        BlockPos feet = feet();
        if (feet.equals(mv.dest)) return true;
        int dx = mv.dest.getX() - mv.src.getX();
        int dz = mv.dest.getZ() - mv.src.getZ();
        return feet.equals(mv.dest.offset(dx, 0, dz));
    }

    /** Live floor check — restores a step block dug since planning (jumping at a
     *  missing step grinds in place, or slides the body off a ledge). */
    @Override
    public BlockPos scaffoldCell() {
        return floorUnderDest();
    }

    /** Unsafe once we've STARTED placing the step block —
     *  i.e. once the scaffold phase began or finished. */
    @Override
    public boolean safeToCancel(boolean scaffoldCommitted, int ticksOnCurrent) {
        return !scaffoldCommitted;
    }

    @Override
    public String debugFlags() {
        return sprint ? "sprintAsc=true" : "";
    }

    /** Head-bonk check: the four horizontals above src+2 are passable, so a
     *  jump won't smack the body's head into a ceiling block. */
    private boolean headBonkClear() {
        BlockPos up2 = mv.src.above(2);
        for (Direction d : new Direction[]{
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (!BlockHelper.canWalkThrough(player.level(), up2.relative(d))) {
                return false;
            }
        }
        return true;
    }
}
