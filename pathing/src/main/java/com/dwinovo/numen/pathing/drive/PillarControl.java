package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.body.Controls.Key;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Maneuver;

/**
 * 垫柱:头顶要挖的先挖开;然后站到这一列正中、停稳,原地起跳,脚离开这一格(身子整个在它上面)就往脚下这一格放一块,
 * 落在它上面。跳起来没来得及放、又落回原地,就再跳一次。
 */
final class PillarControl extends Control {

    PillarControl(Rig rig, Maneuver m, Maneuver next) {
        super(rig, m, next);
    }

    @Override
    Beat tick() {
        Edit last = m.edits().isEmpty() ? null : m.edits().get(m.edits().size() - 1);
        boolean placesUnder = last instanceof Edit.Place place && place.pos().equals(m.from());
        Beat edits = editsInPlace(placesUnder ? allEdits() - 1 : allEdits());
        if (edits != null) {
            return edits;
        }
        if (!placesUnder || work.done(last)) {
            // 块已经在脚下:等身子落上去
            keys().release(Key.JUMP);
            keys().release(Key.FORWARD);
            keys().release(Key.BACK);
            return Beat.IDLE;
        }
        if (rig.entity.onGround()) {
            if (!settle()) {
                return Beat.IDLE;
            }
            keys().press(Key.JUMP);
            return Beat.IDLE;
        }
        keys().release(Key.JUMP);
        if (rig.entity.getY() < m.from().getY() + 1) {
            return Beat.IDLE;
        }
        return work.tick(last);
    }
}
