package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.pathing.plan.Maneuver;

import net.minecraft.world.phys.Vec3;

/**
 * 游:往上按住跳(原版在水里按住跳就往上浮),往下按住潜行(原版客户端潜行时往下沉),平挪时脚沉到这一格的下半截就按跳托住;
 * 水平方向朝落点那一列游。水里不疾跑:疾跑会变成游泳姿势,身子只剩 0.6 高。
 */
final class SwimControl extends Control {

    SwimControl(Rig rig, Maneuver m, Maneuver next) {
        super(rig, m, next);
    }

    @Override
    Beat tick() {
        Beat edits = editsInPlace(allEdits());
        if (edits != null) {
            return edits;
        }
        keys().release(Key.SPRINT);
        int dy = m.to().getY() - m.from().getY();
        double feet = rig.entity.getY();
        keys().set(Key.JUMP, dy > 0 || dy == 0 && feet < m.to().getY() + FLOAT);
        keys().set(Key.SNEAK, dy < 0);
        Vec3 c = center(m.to());
        // 水里没有地面可借力,Steering 只在地上转身;横着游的时候身子自由转,每刻先朝向落点那一列(竖着游不转)
        if (m.heading().horizontal()) {
            rig.look.faceToward(c.x, c.z);
        }
        if (flows()) {
            Steering.pass(rig, c.x, c.z);
        } else {
            Steering.stop(rig, c.x, c.z, m.landing().feetY());
        }
        return Beat.IDLE;
    }
}
