package com.dwinovo.numen.core.pathing.exec.drive;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.pathing.movement.Movement;
import net.minecraft.world.phys.Vec3;

/** Dig straight down one cell: the executor's dig phase breaks the floor; the drive
 *  just keeps centred over the column so gravity drops us straight onto the dug cell
 *  (recentre over the break cell rather than free-drifting). */
final class DigDownDriver extends MoveDriver {

    DigDownDriver(NumenPlayer player, Movement mv, double speed) {
        super(player, mv, speed);
    }

    @Override
    public void drive() {
        if (horizontalDistTo(mv.dest) > 0.2) {
            InputDriver.stepToward(player, Vec3.atBottomCenterOf(mv.dest), false);
        } else {
            InputDriver.halt(player);
        }
    }

    @Override
    public boolean arrived() {
        return feet().equals(mv.dest);
    }
}
