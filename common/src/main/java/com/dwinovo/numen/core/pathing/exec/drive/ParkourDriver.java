package com.dwinovo.numen.core.pathing.exec.drive;

import com.dwinovo.numen.entity.InputDriver;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.movement.Movement;
import net.minecraft.world.phys.Vec3;

/** Running jump over a gap. Holds the jump until clear of the takeoff block,
 *  so the arc starts from the edge, not the centre. */
final class ParkourDriver extends MoveDriver {

    ParkourDriver(NumenPlayer player, Movement mv, double speed) {
        super(player, mv, speed);
    }

    @Override
    public void drive() {
        int gap = Math.max(Math.abs(mv.dest.getX() - mv.src.getX()),
                Math.abs(mv.dest.getZ() - mv.src.getZ()));
        InputDriver.stepToward(player, Vec3.atBottomCenterOf(mv.dest), gap >= 4);   // a 4-gap needs sprint physics
        if (player.onGround() && horizontalDistTo(mv.src) > 0.7) {
            InputDriver.jump(player);
        }
    }

    @Override
    public boolean arrived() {
        return feet().equals(mv.dest);
    }

    /** Only cancellable on the takeoff (0th) tick — no momentum knowledge after. */
    @Override
    public boolean safeToCancel(boolean scaffoldCommitted, int ticksOnCurrent) {
        return ticksOnCurrent == 0;
    }
}
