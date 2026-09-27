package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.body.Controls.Key;
import com.dwinovo.numen.pathing.plan.Maneuver;

import net.minecraft.world.phys.Vec3;

/**
 * 下一级、下落:走出边沿,落在相邻一列。在起步那一块上压着速度慢慢走出去——身子离开边沿之后还要在空中飘一段,冲得太快
 * 就落过了头;离地之后照原版空中那一点加速度往落点修正({@link Steering#stop} 连同落地前的那几刻一起推算)。下一步朝同一个
 * 方向接着走时不减速,落远了由段状态机认到后面那一步上。
 */
final class DropControl extends Control {

    /** 走出边沿时的速度(格每刻):慢到落下去不冲过落点那一列,又快到走得出去。 */
    private static final double EDGE_SPEED = 0.1;
    /** 脚比落点高出这么多,就还没落下去。 */
    private static final double DROPPED = 0.3;

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
        keys().set(Key.JUMP, floatUp());
        keys().release(Key.SPRINT);
        Vec3 target = landingSpot();
        if (flows()) {
            Steering.pass(rig.entity, keys(), target.x, target.z);
            return Beat.IDLE;
        }
        if (rig.entity.onGround() && rig.entity.getY() > m.landing().feetY() + DROPPED) {
            // 脚还在上面:压着速度往前走,直到脚下空了——冲出去太快就落过了头。托着脚的不一定只有起步那一块,
            // 梯子顶也托得住,所以看的是离没离地,不是走没走过格边
            Steering.approach(rig.entity, keys(), target.x, target.z, EDGE_SPEED);
            return Beat.IDLE;
        }
        // 离地了(或已经落下去了):照原版空中那一点加速度修正,停在落点上
        Steering.stop(rig.entity, keys(), target.x, target.z, m.landing().feetY());
        return Beat.IDLE;
    }

    @Override
    boolean falls() {
        return true;
    }
}
