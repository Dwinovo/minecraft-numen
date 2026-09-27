package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.body.Controls.Key;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Maneuver;

import net.minecraft.world.phys.Vec3;

/**
 * 下一级、下落:走出边沿,落在相邻一列。在起步那一块上压着速度慢慢走出去——身子离开边沿之后还要在空中飘一段,冲得太快
 * 就落过了头;离地之后照原版空中那一点加速度往落点修正({@link Steering#stop} 连同落地前的那几刻一起推算)。下一步朝同一个
 * 方向接着走时不减速,落远了由段状态机认到后面那一步上。
 *
 * <p>规划要倒水接住的坠落({@link Edit.Catch}):走出去之前把水桶拿到手上——只有这样的坠落才备水桶;落到够得着落点脚下那块
 * 顶面时低头倒水,落进水里之后把水收回桶里,收回了这一步才算完。</p>
 */
final class DropControl extends Control {

    /** 走出边沿时的速度(格每刻):慢到落下去不冲过落点那一列,又快到走得出去。 */
    private static final double EDGE_SPEED = 0.1;
    /** 脚比落点高出这么多,就还没落下去。 */
    private static final double DROPPED = 0.3;

    DropControl(Rig rig, Maneuver m, Maneuver next) {
        super(rig, m, next);
        Edit last = m.edits().isEmpty() ? null : m.edits().get(m.edits().size() - 1);
        this.caught = last instanceof Edit.Catch c ? c : null;
    }

    /**
     * 倒水接住这一刻要做的,不管走:还在起步那一块上时先把水桶拿到手上;落到够得着的高度就倒;落定之后收回。
     */
    private Beat water() {
        boolean up = rig.entity.getY() > m.landing().feetY() + DROPPED;
        if (up && rig.entity.onGround()) {
            rig.act(com.dwinovo.numen.pathing.body.Hotbar.grip(rig.entity,
                    net.minecraft.world.item.Items.WATER_BUCKET).action());
            return Beat.IDLE;
        }
        if (!work.poured()) {
            // 落地时水还没倒下去:这一下已经摔了,没有水可收
            return up ? work.tick(caught) : Beat.IDLE;
        }
        if (up || work.done(caught)) {
            return Beat.IDLE;
        }
        // 落进水里了:低头把水收回
        return work.tick(caught);
    }

    /** 倒了水还没收回:这一步还没完。 */
    @Override
    boolean holds() {
        return caught != null && work.poured() && !work.done(caught);
    }

    /** 这一步要倒水接住的那一件;没有为 null。 */
    private final Edit.Catch caught;

    @Override
    Beat tick() {
        Beat edits = editsInPlace(caught == null ? allEdits() : allEdits() - 1);
        if (edits != null) {
            return edits;
        }
        // 接水先做:它转头看着落点,空中修正落点就沿着这个朝向前后调
        Beat water = caught == null ? Beat.IDLE : water();
        boolean collecting = holds() && rig.entity.getY() <= m.landing().feetY() + DROPPED;
        if (!(water instanceof Beat.Going) || collecting) {
            keys().release(Key.FORWARD);
            keys().release(Key.BACK);
            return water;
        }
        keys().release(Key.SNEAK);
        keys().set(Key.JUMP, floatUp());
        keys().release(Key.SPRINT);
        Vec3 target = landingSpot();
        if (flows()) {
            Steering.pass(rig.entity, keys(), target.x, target.z);
            return water;
        }
        if (rig.entity.onGround() && rig.entity.getY() > m.landing().feetY() + DROPPED) {
            // 脚还在上面:压着速度往前走,直到脚下空了——冲出去太快就落过了头。托着脚的不一定只有起步那一块,
            // 梯子顶也托得住,所以看的是离没离地,不是走没走过格边
            Steering.approach(rig.entity, keys(), target.x, target.z, EDGE_SPEED);
            return water;
        }
        // 离地了(或已经落下去了):照原版空中那一点加速度修正,停在落点上
        Steering.stop(rig.entity, keys(), target.x, target.z, m.landing().feetY());
        return water;
    }

    @Override
    boolean falls() {
        return true;
    }
}
