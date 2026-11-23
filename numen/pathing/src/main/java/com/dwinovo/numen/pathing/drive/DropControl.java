package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Maneuver;

import net.minecraft.world.phys.Vec3;

/**
 * 下一级、下落:走出边沿,落在相邻一列。要在落点上收脚时,走到身子整个离开起步那一块,之后停到落点那一点上
 * ({@link Steering#stop}:空中也对得准)。下一步朝同一个方向接着走时不减速(疾跑也一样,{@link Gait}),落远了
 * 由段状态机认到后面那一步上。
 *
 * <p>规划要倒水接住的坠落({@link Edit.Catch}):走出去之前把水桶拿到手上——只有这样的坠落才备水桶;落到够得着落点脚下那块
 * 顶面时低头倒水,落进水里之后把水收回桶里,收回了这一步才算完。</p>
 */
final class DropControl extends Control {

    /** 身体中心沿前进方向离起步列中心这么远,身子(半个身宽另算)就整个走出了起步那一列。 */
    private static final double LEFT_EDGE = 0.5;
    /** 脚比落点高出这么多,就还没落下去。 */
    private static final double DROPPED = 0.3;

    DropControl(Rig rig, Maneuver m, Gait.Stride stride) {
        super(rig, m, stride);
        Edit last = m.edits().isEmpty() ? null : m.edits().get(m.edits().size() - 1);
        this.caught = last instanceof Edit.Catch c ? c : null;
    }

    /**
     * 倒水接住这一刻要做的,不管走:还在起步那一块上时先把水桶拿到手上;落到够得着的高度就倒;落定之后收回。
     */
    private Beat water() {
        boolean up = rig.entity.getY() > m.landing().feetY() + DROPPED;
        if (up && rig.entity.onGround()) {
            rig.act(rig.hotbar.grip(com.dwinovo.numen.api.entity.Stacks.Which.of(
                    net.minecraft.world.item.Items.WATER_BUCKET)).action());
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
        // 接水先做:它转头看着落点
        Beat water = caught == null ? Beat.IDLE : water();
        boolean collecting = holds() && rig.entity.getY() <= m.landing().feetY() + DROPPED;
        if (!(water instanceof Beat.Going) || collecting) {
            keys().release(Key.FORWARD);
            keys().release(Key.BACK);
            return water;
        }
        keys().release(Key.SNEAK);
        keys().set(Key.JUMP, floatUp());
        keys().set(Key.SPRINT, stride.sprint());
        Vec3 target = landingSpot();
        if (stride.flows()) {
            if (passed(target)) {
                // 冲劲把身子带过了落点中心:接着朝前冲,不回身去够中心,落在后面哪一步上由段状态机认
                Steering.pass(rig, rig.entity.getX() + m.heading().dx(), rig.entity.getZ() + m.heading().dz());
            } else {
                Steering.pass(rig, target.x, target.z);
            }
            return water;
        }
        boolean up = rig.entity.onGround() && rig.entity.getY() > m.landing().feetY() + DROPPED;
        if (up && ahead() < LEFT_EDGE + rig.entity.getBbWidth() / 2) {
            // 身子还压着起步那一块:走出边沿
            Steering.pass(rig, target.x, target.z);
            return water;
        }
        // 身子整个离开了起步那一块(脚还被落点那一列边上的门板顶、薄板顶托着也一样),或已经离地:停到落点那一点上。
        // 正好在那一点上,两边贴着格边的薄板都托不着身子;落点是攀着的(梯子)时是让开碰撞箱的那一点
        Steering.stop(rig, target.x, target.z);
        return water;
    }

    /** 身体沿这一步的方向已经走过了落点中心。 */
    private boolean passed(Vec3 target) {
        return (rig.entity.getX() - target.x) * m.heading().dx() + (rig.entity.getZ() - target.z) * m.heading().dz() > 0;
    }

    @Override
    boolean falls() {
        return true;
    }
}
