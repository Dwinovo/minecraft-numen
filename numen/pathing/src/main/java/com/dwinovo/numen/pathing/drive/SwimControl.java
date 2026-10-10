package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.pathing.plan.Maneuver;

import net.minecraft.world.phys.Vec3;

/**
 * 游:往上按住跳(原版在水里按住跳就往上浮),往下按住潜行(原版客户端潜行时往下沉),平挪时脚沉到这一格的下半截就按跳托住;
 * 水平方向朝落点那一列游。水里不疾跑:疾跑会变成游泳姿势,身子只剩 0.6 高。
 *
 * <p>落点是水底的浅水格(规划算它是站着,脚踩在水底上,{@link com.dwinovo.numen.pathing.plan.Stance.Kind#GROUND}):
 * 原版水里不按键每刻只沉 0.005 格,身子浮在水底之上老远,永远落不到水底;所以平挪到了落点那一列的中心,就按住潜行沉下去,
 * 沉到踩上水底为止——不再按跳托住,段状态机才认得"站在了落点上"。
 */
final class SwimControl extends Control {

    SwimControl(Rig rig, Maneuver m, Gait.Stride stride) {
        super(rig, m, stride);
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
        Vec3 c = center(m.to());
        if (m.landing().grounded()) {
            boolean sinking = dy < 0 || dy == 0 && horizontalDistance(c) < CENTERED && !rig.entity.onGround();
            keys().set(Key.JUMP, dy > 0);
            keys().set(Key.SNEAK, sinking);
        } else {
            keys().set(Key.JUMP, dy > 0 || dy == 0 && feet < m.to().getY() + FLOAT);
            keys().set(Key.SNEAK, dy < 0);
        }
        // 水里没有地面可借力,Steering 只在地上转身;横着游的时候身子自由转,每刻先朝向落点那一列(竖着游不转)
        if (m.heading().horizontal()) {
            rig.look.faceToward(c.x, c.z);
        }
        if (stride.flows()) {
            Steering.pass(rig, c.x, c.z);
        } else {
            Steering.stop(rig, c.x, c.z, m.landing().feetY());
        }
        return Beat.IDLE;
    }
}
