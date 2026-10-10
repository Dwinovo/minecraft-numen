package com.dwinovo.numen.pathing.drive;

import java.util.List;

import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.plan.Stance;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 一步的控制器:照规划交出的 {@link Maneuver}(连同要做的改动)把这一步变成按键、视角与手上的动作。每种走法一个,
 * 由 {@link #of} 按走法取。控制器只管"这一刻按什么";一步做完没有,由段状态机看身体落在了哪个节点上。
 *
 * <p>改动按规划的先后一件件做,站着做:身体先回到起步那一列的中心、停稳,再松开移动键转头动手——脚下站稳,
 * 瞄点与手够得着才与规划时一致,放进邻格的方块也不会被自己的身体挡住。个别改动要在走动中做(垫柱在跳起来之后放、
 * 背贴搭桥要探出边沿),由各自的控制器接管。
 */
abstract class Control {

    /** 站在一列上算"在中心"的水平距离:身体宽 0.6,离中心不到 0.15 时整个身子都在这一列里。 */
    static final double CENTERED = 0.15;
    /** 算停稳的水平速度。 */
    static final double STILL = 0.03;

    final Rig rig;
    final Maneuver m;
    /** 这一步的步态({@link Gait}):疾不疾跑、带不带着冲劲进下一步。 */
    final Gait.Stride stride;
    final Work work;
    /** 下一件要做的改动的下标。 */
    private int edit;

    Control(Rig rig, Maneuver m, Gait.Stride stride) {
        this.rig = rig;
        this.m = m;
        this.stride = stride;
        this.work = new Work(rig, m.kind());
    }

    static Control of(Rig rig, Maneuver m, Gait.Stride stride) {
        return switch (m.kind()) {
            case WALK, DIAGONAL, ASCEND -> new StrideControl(rig, m, stride);
            case DESCEND, FALL -> new DropControl(rig, m, stride);
            case PARKOUR -> new ParkourControl(rig, m, stride);
            case PILLAR -> new PillarControl(rig, m, stride);
            case DOWNWARD -> new DownwardControl(rig, m, stride);
            case CLIMB -> new ClimbControl(rig, m, stride);
            case SWIM -> new SwimControl(rig, m, stride);
        };
    }

    /** 这一刻。 */
    abstract Beat tick();

    /** 这一步是不是落在空中的坠落(计划内的):身体离地时对外声明。 */
    boolean falls() {
        return false;
    }

    /** 身体已经落在这一步的落点上,这一步却还有收尾的事没做完(比如把接坠落的水收回):段状态机先不往后认步。 */
    boolean holds() {
        return false;
    }

    // ==================== 改动 ====================

    /** 下一件还没做完的改动;前 {@code until} 件都做完了为 null。 */
    final Edit pending(int until) {
        List<Edit> edits = m.edits();
        while (edit < until && work.done(edits.get(edit))) {
            edit++;
        }
        return edit < until ? edits.get(edit) : null;
    }

    /** 站在起步那一列上做完前 {@code until} 件改动:都做完为 null,否则是这一刻的结果。 */
    final Beat editsInPlace(int until) {
        Edit e = pending(until);
        if (e == null) {
            return null;
        }
        if (!settle()) {
            return Beat.IDLE;
        }
        return work.tick(e);
    }

    /** 全部改动的件数。 */
    final int allEdits() {
        return m.edits().size();
    }

    // ==================== 身体 ====================

    /**
     * 停稳在起步那一列上:站着就回到中心停下;攀着就按住潜行不往下滑;浮着就按住跳不往下沉。停稳了松开移动键,返回 true。
     */
    final boolean settle() {
        keys().release(Key.SPRINT);
        keys().release(Key.JUMP);
        Stance.Kind kind = m.start().kind();
        if (kind == Stance.Kind.CLIMBING) {
            keys().press(Key.SNEAK);
        } else if (kind == Stance.Kind.SWIMMING && rig.entity.getY() < m.from().getY() + HOLD_AFLOAT) {
            keys().press(Key.JUMP);
        }
        Vec3 c = center(m.from());
        if (horizontalDistance(c) < CENTERED && horizontalSpeed() < STILL) {
            keys().release(Key.FORWARD);
            keys().release(Key.BACK);
            return true;
        }
        Steering.stop(rig, c.x, c.z, m.start().feetY());
        return false;
    }

    /**
     * 落点是浮在水里的:身体落进水里会沉下去,脚沉到落点那一格的下半截就按住跳浮上来(原版在水里按住跳就往上浮)。
     * 返回这一刻要不要按跳。
     */
    final boolean floatUp() {
        return m.landing().kind() == Stance.Kind.SWIMMING && rig.entity.isInWater()
                && rig.entity.getY() < m.to().getY() + FLOAT;
    }

    /** 落进水里、或停在水里的终点时,脚低于那一格底上这么多就按跳浮起来:这一步落点的 {@link #floatUp} 与收场停在终点都按它。 */
    static final double FLOAT = 0.3;

    /** 浮着起步、停稳在起步那一列上({@link #settle})时,脚低于那一格底上这么多就按跳:只是不往下沉,比落进水里要浮起的 {@link #FLOAT} 小。 */
    static final double HOLD_AFLOAT = 0.1;

    final com.dwinovo.numen.api.entity.Controls keys() {
        return rig.keys;
    }

    static Vec3 center(BlockPos node) {
        return new Vec3(node.getX() + 0.5, node.getY(), node.getZ() + 0.5);
    }

    /** 让开梯子碰撞箱时多留的一点。 */
    private static final double CLEAR = 0.05;

    /**
     * 这一步落下去时身体中心该落在的那一点。平常是落点那一列的中心;落点是攀着的(梯子)时,要让开那一格的碰撞箱——
     * 梯子只贴着墙占一薄片,顶面与旁边的地面齐平,脚底压着它就站在了梯子顶上,抓不住梯子。挪开的方向取挪得最少的那一边。
     */
    final Vec3 landingSpot() {
        BlockPos to = m.to();
        if (m.landing().kind() != Stance.Kind.CLIMBING) {
            return center(to);
        }
        double half = rig.entity.getBbWidth() / 2;
        double x = 0.5;
        double z = 0.5;
        var level = rig.world();
        for (AABB solid : level.getBlockState(to).getCollisionShape(level, to).toAabbs()) {
            AABB box = solid.inflate(CLEAR, 0, CLEAR);
            if (box.minX >= x + half || box.maxX <= x - half || box.minZ >= z + half || box.maxZ <= z - half) {
                continue;
            }
            double west = x + half - box.minX;
            double east = box.maxX - (x - half);
            double north = z + half - box.minZ;
            double south = box.maxZ - (z - half);
            double least = Math.min(Math.min(west, east), Math.min(north, south));
            if (least == west) {
                x -= west;
            } else if (least == east) {
                x += east;
            } else if (least == north) {
                z -= north;
            } else {
                z += south;
            }
        }
        return new Vec3(to.getX() + x, to.getY(), to.getZ() + z);
    }

    final double horizontalDistance(Vec3 point) {
        double dx = point.x - rig.entity.getX();
        double dz = point.z - rig.entity.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    final double horizontalSpeed() {
        Vec3 v = rig.entity.getDeltaMovement();
        return Math.sqrt(v.x * v.x + v.z * v.z);
    }

    /** 身体中心沿这一步的水平方向离起步那一列中心走出了多远。 */
    final double ahead() {
        double dx = m.heading().dx();
        double dz = m.heading().dz();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length == 0) {
            return 0;
        }
        Vec3 c = center(m.from());
        return ((rig.entity.getX() - c.x) * dx + (rig.entity.getZ() - c.z) * dz) / length;
    }
}
