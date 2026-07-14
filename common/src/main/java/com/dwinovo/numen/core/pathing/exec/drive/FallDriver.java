package com.dwinovo.numen.core.pathing.exec.drive;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.pathing.movement.Movement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Multi-block fall (no water-bucket clutch — that would need a bucket
 * in hand): unlike a descend it does NOT carry momentum
 * — it RECENTRES on the landing cell so it doesn't clip a wall, sneaking while
 * fast-falling ({@code |Δy|>0.4}) to kill horizontal drift and land cleanly.
 */
public final class FallDriver extends MoveDriver {

    /** Optimizer channel: when this fall lines up with the corridor below it, the
     *  executor sets an extended aim — drive() sprints off the ledge at it instead
     *  of braking straight down. Recomputed each tick; null = no override. */
    private Vec3 overrideAim;

    FallDriver(NumenPlayer player, Movement mv, double speed) {
        super(player, mv, speed);
    }

    public void setOverrideAim(Vec3 aim) {
        this.overrideAim = aim;
    }

    @Override
    public void drive() {
        if (overrideAim != null) {
            // Overshoot: sprint forward at the extended aim so we clear the ledge and ride the
            // fall over the corridor below, instead of braking straight down.
            player.setShiftKeyDown(false);
            InputDriver.lookAt(player, overrideAim);
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
            InputDriver.lookAt(player, fallAim());   // landing cell, biased off an adjacent ladder
            player.zza = 1.0f;
            player.xxa = 0.0f;
            player.setSprinting(false);
        } else {
            player.setShiftKeyDown(false);
            InputDriver.halt(player);
        }
    }

    @Override
    public boolean arrived() {
        // Fall success: feet at dest AND settled within 0.094 of dest.y
        // (lilypad tolerance); or landed in water and no longer sinking (no water-bucket
        // clutch here).
        if (!feet().equals(mv.dest)) return false;
        if (!player.level().getBlockState(mv.dest).getFluidState().isEmpty()) {
            return player.getDeltaMovement().y >= 0.0;
        }
        return player.getY() - mv.dest.getY() < 0.094;
    }

    /** Safe only before stepping off the edge (still at src). */
    @Override
    public boolean safeToCancel(boolean scaffoldCommitted, int ticksOnCurrent) {
        return feet().equals(mv.src);
    }

    @Override
    public String debugFlags() {
        return overrideAim != null ? "fallOverride=true" : "";
    }

    /** Aim at the landing cell, nudged 0.125 away from an adjacent ladder/vine so the
     *  fall doesn't grab it mid-drop. */
    private Vec3 fallAim() {
        Vec3 c = Vec3.atCenterOf(mv.dest);
        for (Direction d : new Direction[]{
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            var s = player.level().getBlockState(mv.dest.relative(d));
            if (s.is(Blocks.LADDER) || s.is(Blocks.VINE)) {
                return c.add(-d.getStepX() * 0.125, 0.0, -d.getStepZ() * 0.125);
            }
        }
        return c;
    }
}
