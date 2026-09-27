package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.body.Controls.Key;
import com.dwinovo.numen.pathing.plan.Maneuver;

import net.minecraft.world.phys.Vec3;

/**
 * 下一级、下落:走出边沿,落在相邻一列。走出去的快慢按落差定——身子离开边沿之后还要在空中飘一段,冲得太快就落过了头
 * ({@link Steering} 连同空中那几刻一起推算,挑能停在落点上的按键);下一步朝同一个方向接着走时不减速,落远了由段状态机
 * 认到后面那一步上。
 */
final class DropControl extends Control {

    DropControl(Rig rig, Maneuver m, Maneuver next) {
        super(rig, m, next);
    }

    @Override
    Beat tick() {
        Beat edits = editsInPlace(allEdits());
        if (edits != null) {
            return edits;
        }
        keys().release(Key.SNEAK);
        keys().release(Key.JUMP);
        keys().release(Key.SPRINT);
        Vec3 target = center(m.to());
        if (flows()) {
            Steering.pass(rig.entity, keys(), target.x, target.z);
            return Beat.IDLE;
        }
        // 身子整个离开起步那一列(中心过了格边再走半个身宽)脚下就空了
        double edge = 0.5 + rig.entity.getBbWidth() / 2 - ahead();
        int airtime = Steering.fallTicks(m.drop(), rig.entity.getGravity());
        Steering.toward(rig.entity, keys(), target.x, target.z, rig.entity.onGround() ? edge : 0, airtime,
                m.landing().feetY());
        return Beat.IDLE;
    }

    @Override
    boolean falls() {
        return true;
    }
}
