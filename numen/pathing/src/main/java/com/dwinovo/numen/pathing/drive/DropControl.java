package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.world.Stepping;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 下一级、下落:走出边沿,落在相邻一列。要在落点上收脚时,在起步那一块上走到再快就会落过落点那一列为止({@link Steering#approach}
 * 按运动学预判落点),之后松开滑出去——身子离开边沿之后还要在空中飘一段;离地之后照原版空中那一点加速度修正,只保证落在
 * 落点那一列里、停住({@link Steering#stop} 连同落地前的那几刻一起推算),不拉回列中心——到达一格是站在这一格上,不是站在它的中心,
 * 下一步要原地动手时才回中心({@link Control#settle})。下一步朝同一个方向接着走时不减速(疾跑也一样,{@link Gait}),落远了
 * 由段状态机认到后面那一步上。
 *
 * <p>规划要倒水接住的坠落({@link Edit.Catch}):走出去之前把水桶拿到手上——只有这样的坠落才备水桶;落到够得着落点脚下那块
 * 顶面时低头倒水,落进水里之后把水收回桶里,收回了这一步才算完。</p>
 */
final class DropControl extends Control {

    /** 落点那一列里,身体中心最远落在沿前进方向离起步列中心多远:整列的外沿(一格半)往里收一点,预判有偏差也还在这一列里。 */
    private static final double LANDING_END = 1.4;
    /** 落在落点那一列里:身体中心走过列中心后最多再偏这么远(半格往里收一点,预判有偏差也还在这一列里)。 */
    private static final double COLUMN = 0.4;
    /** 身体中心沿前进方向离起步列中心这么远,身子(半个身宽另算)就整个走出了起步那一列。 */
    private static final double LEFT_EDGE = 0.5;
    /** 看身体被什么托着时,往脚底下探这么深。 */
    private static final double SUPPORT_REACH = 0.01;
    /** 脚比落点高出这么多,就还没落下去。 */
    private static final double DROPPED = 0.3;

    DropControl(Rig rig, Maneuver m, Gait.Stride stride) {
        super(rig, m, stride);
        Edit last = m.edits().isEmpty() ? null : m.edits().get(m.edits().size() - 1);
        this.caught = last instanceof Edit.Catch c ? c : null;
        this.beyond = beyond(rig, m);
    }

    /**
     * 落在落点那一列里、身体中心走过列中心之后最多再偏多远:一列的容许范围({@link #COLUMN}),落点前面紧挨着比落点高的东西时,再往前身子就
     * 撞上去、迈上它(落点只比它低半格以内,原版自动迈步)而落到别的节点上,所以不超过冲劲滑到撞上它之前的余量({@link Stepping#slideRoom})。
     * 落点不是站着的(水、梯子)不受这个限制。
     */
    private static double beyond(Rig rig, Maneuver m) {
        if (!m.landing().grounded()) {
            return COLUMN;
        }
        return Math.min(COLUMN, Stepping.slideRoom(rig.world(), rig.snapshot().stats(), m.to().getX(), m.landing().feetY(),
                m.to().getZ(), m.heading().dx(), m.heading().dz()));
    }

    /**
     * 倒水接住这一刻要做的,不管走:还在起步那一块上时先把水桶拿到手上;落到够得着的高度就倒;落定之后收回。
     */
    private Beat water() {
        boolean up = rig.entity.getY() > m.landing().feetY() + DROPPED;
        if (up && rig.entity.onGround()) {
            rig.act(rig.hotbar.grip(
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
    /** 落点那一列里,身体中心走过列中心后最多再偏多远,见 {@link #beyond(Rig, Maneuver)}。 */
    private final double beyond;

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
        if (up) {
            // 脚还在上面。身子已经整个离开了起步那块、却还没落下去,是落点那一列里有东西托着(落点边上的门板顶、薄板顶、落点前面更高的地面):
            // 朝列中心走会在托着的边上来回,不能按"停准在列中心"来修正(差得比一下按键滑出去的还小),所以朝离开托着的那一边走:
            // 托着的在前面就往回退,在后面就往前走,走出托着的范围就落下去了
            boolean stranded = ahead() >= LEFT_EDGE + rig.entity.getBbWidth() / 2;
            Vec3 aim = stranded ? awayFromSupport(target) : target;
            if (stranded && horizontalSpeed() < STILL) {
                keys().release(Key.SPRINT);
                Steering.pass(rig, aim.x, aim.z);
                return water;
            }
            // 往前走到再快就会落过落点那一列为止,之后滑出去。托着脚的不一定只有起步那一块,
            // 梯子顶也托得住,所以看的是离没离地,不是走没走过格边
            Steering.approach(rig, aim.x, aim.z, 0.5 + rig.entity.getBbWidth() / 2 - ahead(), LANDING_END - ahead(), m.drop());
            return water;
        }
        // 离地了(或已经落下去了):照原版空中那一点加速度修正,落在落点那一列里停住;落点是攀着的(梯子)时要落在让开碰撞箱的那一点上
        if (m.landing().kind() == Stance.Kind.CLIMBING) {
            Steering.stop(rig, target.x, target.z, m.landing().feetY());
        } else {
            // 还没走到中心时,身子要整个离开起步那块的边沿(落点比起步低得不多时,压在边沿上就还没落下去)
            Steering.stop(rig, target.x, target.z, m.landing().feetY(), 0.5 - rig.entity.getBbWidth() / 2, beyond,
                    m.heading().dx(), m.heading().dz());
        }
        return water;
    }

    /**
     * 身体被什么托着、该往哪边走才离开它:脚底下碰得到的碰撞箱,中心在身体中心沿前进方向的前面就朝后(往落点中心的方向退),在后面就朝前。
     * 脚底下什么也碰不到(正落下去)按落点中心。
     */
    private Vec3 awayFromSupport(Vec3 center) {
        ServerPlayer body = rig.entity;
        int hx = m.heading().dx();
        int hz = m.heading().dz();
        double axis = body.getX() * hx + body.getZ() * hz;
        double offset = 0;
        boolean supported = false;
        for (VoxelShape shape : body.level().getBlockCollisions(body, body.getBoundingBox().move(0, -SUPPORT_REACH, 0))) {
            AABB box = shape.bounds();
            offset += box.getCenter().x * hx + box.getCenter().z * hz - axis;
            supported = true;
        }
        if (!supported) {
            return center;
        }
        int away = offset > 0 ? -1 : 1;
        return new Vec3(body.getX() + away * hx, 0, body.getZ() + away * hz);
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
