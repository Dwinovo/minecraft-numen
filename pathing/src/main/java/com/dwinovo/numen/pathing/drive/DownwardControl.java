package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.body.Controls.Key;
import com.dwinovo.numen.pathing.plan.Maneuver;

/**
 * 向下挖:站到这一列正中停稳(身子整个在这一列里,挖空了才掉得下去,不会被旁边的方块托住),挖掉脚下那一格,落下去。
 */
final class DownwardControl extends Control {

    DownwardControl(Rig rig, Maneuver m, Maneuver next) {
        super(rig, m, next);
    }

    @Override
    Beat tick() {
        Beat edits = editsInPlace(allEdits());
        if (edits != null) {
            return edits;
        }
        keys().release(Key.FORWARD);
        keys().release(Key.BACK);
        keys().release(Key.JUMP);
        return Beat.IDLE;
    }

    @Override
    boolean falls() {
        return true;
    }
}
