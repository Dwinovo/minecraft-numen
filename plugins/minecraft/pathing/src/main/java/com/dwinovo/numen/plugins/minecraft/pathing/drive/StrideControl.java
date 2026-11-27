package com.dwinovo.numen.plugins.minecraft.pathing.drive;

import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.plugins.minecraft.pathing.plan.Edit;
import com.dwinovo.numen.plugins.minecraft.pathing.plan.MoveKind;
import com.dwinovo.numen.plugins.minecraft.pathing.world.Kinematics;
import com.dwinovo.numen.plugins.minecraft.pathing.world.Semantics;

import net.minecraft.world.phys.Vec3;

/**
 * 平走、斜走、上一级:走进相邻的一列。要起跳时(整块、楼梯背面)贴近那一列就按跳,离地就松开;楼梯正面、下半砖是
 * 走上去的,不跳。从梯子顶上、水面上跨上岸是攀上去、游上去的:按住跳朝那一列走。
 */
final class StrideControl extends Control {

    StrideControl(Rig rig, com.dwinovo.numen.plugins.minecraft.pathing.plan.Maneuver m, Gait.Stride stride) {
        super(rig, m, stride);
    }

    @Override
    Beat tick() {
        Edit e = pending(allEdits());
        if (e != null) {
            if (!settle()) {
                return Beat.IDLE;
            }
            return work.tick(e);
        }
        move();
        return Beat.IDLE;
    }

    private void move() {
        keys().release(Key.SNEAK);
        Vec3 target = center(m.to());
        // 够到落点的高度之前一直按着;够到了还按着,落点是浅水(岸上的一汪水)时身子就浮在水底之上,落不到落点上
        boolean climbOut = m.kind() == MoveKind.ASCEND && !m.start().grounded()
                && (rig.entity.getY() < m.landing().feetY() - 0.01 || rig.entity.horizontalCollision);
        // 站在水里起跳:原版在水里按跳是往上浮,浮到身子够高、再顶着岸边,才被水托上去——所以够不着落点的高度、或迎面撞着东西时一直按着;
        // 够着了还按着,身子就浮在水底之上,落不到落点上
        boolean wadingJump = m.jump() && rig.entity.isInWater()
                && (rig.entity.getY() < m.landing().feetY() - 0.01 || rig.entity.horizontalCollision);
        // 脚已经不比落点低(跳过一道薄板落在了板顶,落点是板后更低的半砖)时,只有撞着东西才再跳:提前起跳是为了够到落点,
        // 够到了再按只会一路蹦过落点
        boolean jump = climbOut || floatUp() || wadingJump || m.jump() && rig.entity.onGround()
                && (rig.entity.horizontalCollision
                        || rig.entity.getY() < m.landing().feetY() - 0.01 && ahead() >= jumpPoint());
        keys().set(Key.JUMP, jump);
        if (stride.flows()) {
            Steering.pass(rig, target.x, target.z);
        } else {
            Steering.stop(rig, target.x, target.z);
        }
        keys().set(Key.SPRINT, stride.sprint());
    }

    /**
     * 身体中心走出这么远就起跳:从离地到脚升过这一级的高度要几刻({@link Kinematics#riseTicks}),这几刻里身子每刻往前挪的距离按
     * 下一刻的({@link Steering#nextStride}:上一刻留下的速度加这一刻按前进的加速度),挪到前沿碰上那一列时脚刚好高过去。
     * 起得晚了,身子先撞在立面上停住,再从静止起跳,一级台阶白白多费几刻。
     */
    private double jumpPoint() {
        double half = rig.entity.getBbWidth() / 2;
        double factor = Semantics.jumpFactor(rig.world(), m.from().getX(), m.start().feetY(), m.from().getZ());
        double ticks = Kinematics.riseTicks(rig.snapshot().stats(), factor, m.landing().feetY() - m.start().feetY());
        return 0.5 - half - Steering.nextStride(rig.entity) * ticks;
    }
}
