package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.body.Aim;
import com.dwinovo.numen.pathing.body.Controls.Key;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.MoveKind;

import net.minecraft.world.phys.Vec3;

/**
 * 平走、斜走、上一级:走进相邻的一列。要起跳时(整块、楼梯背面)贴近那一列就按跳,离地就松开;楼梯正面、下半砖是
 * 走上去的,不跳。从梯子顶上、水面上跨上岸是攀上去、游上去的:按住跳朝那一列走。
 *
 * <p>脚下搭桥而能点的面只剩起步那一块的侧面时(规划的 {@link com.dwinovo.numen.pathing.plan.Maneuver#sneak()}),
 * 按住潜行朝落点走——原版潜行走不出边沿——探出去到看得见那个面,再回身点它。
 */
final class StrideControl extends Control {

    StrideControl(Rig rig, com.dwinovo.numen.pathing.plan.Maneuver m, com.dwinovo.numen.pathing.plan.Maneuver next) {
        super(rig, m, next);
    }

    @Override
    Beat tick() {
        Edit e = pending(allEdits());
        if (e != null) {
            if (m.sneak() && e instanceof Edit.Place place && place.pos().equals(m.to().below())) {
                return bridge(place);
            }
            if (!settle()) {
                return Beat.IDLE;
            }
            return work.tick(e);
        }
        move();
        return Beat.IDLE;
    }

    /** 背贴搭桥:潜行探出边沿,直到起步那一块朝前的侧面看得见,再回身点它。 */
    private Beat bridge(Edit.Place place) {
        keys().press(Key.SNEAK);
        keys().release(Key.JUMP);
        keys().release(Key.SPRINT);
        if (Aim.face(rig.entity, place.pos(), place.block()) == null) {
            Vec3 target = center(m.to());
            Aim.faceToward(rig.entity, target.x, target.z);
            keys().press(Key.FORWARD);
            keys().release(Key.BACK);
            return Beat.IDLE;
        }
        keys().release(Key.FORWARD);
        keys().release(Key.BACK);
        return work.tick(place);
    }

    private void move() {
        keys().release(Key.SNEAK);
        Vec3 target = center(m.to());
        boolean climbOut = m.kind() == MoveKind.ASCEND && !m.start().grounded();
        // 站在水里起跳:原版在水里按跳是往上浮,浮到身子够高、再顶着岸边,才被水托上去——所以在水里一直按着
        boolean wadingJump = m.jump() && rig.entity.isInWater();
        boolean jump = climbOut || floatUp() || wadingJump || m.jump() && rig.entity.onGround()
                && (rig.entity.horizontalCollision || ahead() >= jumpPoint());
        keys().set(Key.JUMP, jump);
        boolean flows = flows();
        if (flows) {
            Steering.pass(rig.entity, keys(), target.x, target.z);
        } else {
            Steering.stop(rig.entity, keys(), target.x, target.z, m.landing().feetY());
        }
        keys().set(Key.SPRINT, SprintPolicy.sprint(m, next, flows));
    }

    /** 身体中心走出这么远就起跳:身子前沿快碰到那一列时。 */
    private double jumpPoint() {
        double half = rig.entity.getBbWidth() / 2;
        return 0.5 - half - horizontalSpeed();
    }
}
