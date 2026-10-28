package com.dwinovo.numen.pathing.plan;

import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Stepping;
import com.dwinovo.numen.pathing.world.Kinematics;

import net.minecraft.core.BlockPos;

/**
 * 上一级:进东南西北相邻一列,落在高一个节点。楼梯从正面是走上去的(两个半格的坎),整块、楼梯背面与侧面要起跳——
 * 走还是跳只由第 0 层 {@link Stepping} 从碰撞箱推导。挂在梯子上爬到顶、浮在水面上够到岸边,也是这一步:身体是攀上去、
 * 游上去的,不起跳。
 *
 * <p>落点那一格托不住脚时在它下面垫一块台阶(只在站着时;要改地形、要有料)。过不去时,把身体在落点的脚高上挪过去
 * 途中与落定后挡着的格腾出来(门就开关、其余挖开;起步那一列头顶起跳要的净空也在其中),再判一次。
 */
final class Ascend implements Move {

    @Override
    public MoveKind kind() {
        return MoveKind.ASCEND;
    }

    @Override
    public List<Heading> headings() {
        return Heading.CARDINAL;
    }


    @Override
    public Premise premise(CostModel model, WorldView view, BlockPos from, Stance stance, Heading heading) {
        BodyStats body = model.body().stats();
        Draft draft = new Draft(model, view);
        BlockPos to = from.offset(heading.dx(), 1, heading.dz());
        double f0 = stance.feetY();
        boolean grounded = stance.grounded();

        double f1 = Footing.height(draft, body, to.getX(), to.getY(), to.getZ());
        if (Double.isNaN(f1)) {
            if (!grounded) {
                return Premise.fail(to, Reason.NO_FOOTING);
            }
            if (!draft.placeInSight(to.below(), from.getX(), f0, from.getZ())) {
                return draft.failure();
            }
            f1 = Footing.height(draft, body, to.getX(), to.getY(), to.getZ());
            if (Double.isNaN(f1)) {
                return Premise.fail(to, Reason.NO_FOOTING);
            }
        }
        if (stance.kind() == Stance.Kind.SWIMMING && f1 > Strides.exitCeiling(draft, body, from) + Footing.EPSILON) {
            // 从水里出水:岸沿比水面高得多,蹿不上去
            return Premise.fail(to, Reason.TOO_HIGH);
        }
        Stepping.Step step = geometry(draft, body, from, f0, grounded, heading, f1);
        if (step == Stepping.Step.BLOCKED) {
            // 身体在落点的脚高上从起步那一列挪到落点:途中与落定后挡着的格,连同起跳时头顶那一格
            List<BlockPos> blockers = Strides.union(Strides.at(draft, body, to, f1),
                    Strides.across(draft, body, from, heading, f1));
            if (!draft.clear(blockers, true, from.getX(), f0, from.getZ(), grounded)) {
                return draft.failure();
            }
            step = geometry(draft, body, from, f0, grounded, heading, f1);
            if (step == Stepping.Step.BLOCKED) {
                return Premise.fail(to, Reason.TOO_HIGH);
            }
        }
        Stance landing = Stance.at(draft, body, to);
        if (landing == null || !landing.grounded()) {
            return Premise.fail(to, Reason.NO_CLEARANCE);
        }
        boolean rises = step == Stepping.Step.JUMP;
        Contact contact = new Contact(body, from, f0).column(to.getX(), to.getZ(), f0, f1);
        if (rises) {
            contact.column(from.getX(), from.getZ(), f0, f1);
        }
        BlockPos support = landing.support(to.getX(), to.getZ());
        if (!contact.admit(draft, model, support, false)) {
            return draft.failure();
        }
        // 攀着、浮着时身体是攀上去、游上去的,只有站着时要跨过高坎才起跳
        boolean jump = rises && grounded;
        return new Premise.Holds(new Maneuver(MoveKind.ASCEND, heading, from, stance, to, landing, jump, false, false,
                Strides.inWater(draft, to), Strides.submerged(draft, body, from, stance, to, landing), Strides.speedFactor(draft, from, f0, to, f1), 0, 0, 1, draft.edits(),
                contact.cells(), contact.exposure(), support,
                Strides.reversible(draft, body, from, stance, to, landing)));
    }

    /** 站着是从脚下的方块迈上去或跳上去({@link Stepping#between});攀着、浮着是在梯子、水里升上去再挪过去({@link Stepping#fromHold})。 */
    private static Stepping.Step geometry(Draft draft, BodyStats body, BlockPos from, double f0, boolean grounded,
                                          Heading heading, double f1) {
        return grounded
                ? Stepping.between(draft, body, from.getX(), f0, from.getZ(), heading.dx(), heading.dz(), f1)
                : Stepping.fromHold(draft, body, from.getX(), f0, from.getZ(), heading.dx(), heading.dz(), f1,
                        // 从水里出水的身体靠冲劲蹿出岸沿,梯子上爬上去不用
                        Strides.inWater(draft, from) ? Stepping.WATER_EXIT_LIFT : 0);
    }

    /**
     * 平地上站着的节点,要踏上去的那一格是整块、它上面身体要占的格与起步那一列头顶都是空的,就是起跳上去;上面有整块挡着,
     * 要挖掉每一块再跳(不许挖、挖不动的过不去);要踏的那一格是空的,要垫一块台阶(没料或不许放就过不去)。要挖要放的,与标志字
     * 说不清的格,答"可能":代价按最快的步速,只挖不垫时台阶还在、照样起跳,加上起跳的工夫与罚分,再加要挖要放的那几格的最低价。
     */
    @Override
    public int quick(Quick q, Heading heading) {
        int dx = heading.dx();
        int dz = heading.dz();
        if (!q.plain()) {
            return q.likely(dx, 1, dz, q.fastest());
        }
        int step = q.cell(dx, 0, dz);
        if (!Quick.simple(step)) {
            return q.likely(dx, 1, dz, q.fastest());
        }
        boolean blocked = false;
        for (int dy = 1; dy <= q.top() + 1; dy++) {
            int flags = q.cell(dx, dy, dz);
            if (!Quick.simple(flags)) {
                return q.likely(dx, 1, dz, q.fastest());
            }
            blocked |= Quick.whole(flags);
        }
        int head = q.cell(0, q.top() + 1, 0);
        if (!Quick.simple(head)) {
            return q.likely(dx, 1, dz, q.fastest());
        }
        double edits = 0;
        if (Quick.pass(step)) {
            if (!q.mayPlace()) {
                return Quick.REJECT;
            }
            edits += q.placeFloor();
        }
        if (blocked || Quick.whole(head)) {
            if (!q.mayDig()) {
                return Quick.REJECT;
            }
            edits += q.digFloor(dx, 1, q.top() + 1, dz) + q.digFloor(0, q.top() + 1, q.top() + 1, 0);
            if (Double.isInfinite(edits)) {
                return Quick.REJECT;
            }
        }
        if (Quick.pass(step)) {
            return q.likely(dx, 1, dz, q.working() + edits);
        }
        double jump = movement(q.working(), true, q.stepJump(), true, 0) + q.jumpPenalty();
        if (blocked || Quick.whole(head)) {
            return q.likely(dx, 1, dz, jump + edits);
        }
        if (!q.calm()) {
            return q.likely(dx, 1, dz, movement(q.pace(), true, q.stepJump(), true, 0) + q.jumpPenalty());
        }
        q.entering();
        for (int dy = 0; dy <= q.top() + 1; dy++) {
            q.enter(dx, dy, dz);
        }
        q.enter(0, q.top() + 1, 0);
        if (q.forbidsPassing() || !q.standsOn(dx, 0, dz, step)) {
            return Quick.REJECT;
        }
        double move = movement(q.pace(), true, q.stepJump(), true, 0);
        return q.holds(q.x() + dx, q.y() + 1, q.z() + dz, move + q.jumpPenalty() + q.overhead(dx, 0, dz), move);
    }

    @Override
    public double cost(CostModel model, Maneuver m) {
        return movement(model, m) + (m.jump() ? model.spec().jumpPenalty() : 0) + model.overhead(m);
    }

    @Override
    public double ticks(CostModel model, Maneuver m) {
        return movement(model, m) + model.workTicks(m);
    }

    /** 身体上去的刻数:一格的步速;跳上去的至少是起跳落到那一高度的工夫,从水里、梯子上够上去的另加爬一格。 */
    private static double movement(CostModel model, Maneuver m) {
        return movement(Strides.pace(model, m), m.jump(), m.jump() ? Strides.jump(model, Strides.rise(m)) : 0, m.start().grounded(),
                !m.jump() && !m.start().grounded() ? Kinematics.climbUpTicksPerBlock(model.body().stats()) : 0);
    }

    /** 同上,步速、要不要跳、起跳落到那一高度的工夫({@code jump} 时才用)、起步时是不是站着、爬一格的工夫(攀着浮着起步时才用)已经给出。 */
    static double movement(double pace, boolean jump, double jumpTicks, boolean grounded, double climbUp) {
        if (jump) {
            return Math.max(pace, jumpTicks);
        }
        return grounded ? pace : pace + climbUp;
    }
}
