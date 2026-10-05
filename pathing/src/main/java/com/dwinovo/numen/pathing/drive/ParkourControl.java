package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.body.Aim;
import com.dwinovo.numen.pathing.body.Controls.Key;
import com.dwinovo.numen.pathing.plan.Maneuver;

import net.minecraft.world.phys.Vec3;

/**
 * 跑酷:朝落点跑(规划要疾跑就疾跑),在起步那一块的边上起跳——下一刻身子就要离开边沿的那一刻;腾空之后照原版空中的
 * 那一点加速度朝落点修正,落地不冲过头。
 */
final class ParkourControl extends Control {

    ParkourControl(Rig rig, Maneuver m, Maneuver next) {
        super(rig, m, next);
    }

    @Override
    Beat tick() {
        keys().release(Key.SNEAK);
        Vec3 target = center(m.to());
        keys().set(Key.SPRINT, SprintPolicy.sprint(m, next, flows()));
        double edge = 0.5 + rig.entity.getBbWidth() / 2;
        if (rig.entity.onGround() && ahead() < edge) {
            Aim.faceToward(rig.entity, target.x, target.z);
            keys().press(Key.FORWARD);
            keys().release(Key.BACK);
            // 下一刻身子就离开边沿了:这一刻按下跳,原版在下一刻移动之前起跳
            keys().set(Key.JUMP, ahead() + Steering.nextStride(rig.entity) >= edge);
            return Beat.IDLE;
        }
        keys().release(Key.JUMP);
        if (flows()) {
            Steering.pass(rig.entity, keys(), target.x, target.z);
        } else {
            Steering.stop(rig.entity, keys(), target.x, target.z, m.landing().feetY());
        }
        return Beat.IDLE;
    }

    @Override
    boolean falls() {
        return true;
    }
}
