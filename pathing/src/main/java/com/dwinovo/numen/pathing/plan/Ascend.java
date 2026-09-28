package com.dwinovo.numen.pathing.plan;

import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Stepping;

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
            if (!draft.place(to.below(), from.getX(), f0, from.getZ())) {
                return draft.failure();
            }
            f1 = Footing.height(draft, body, to.getX(), to.getY(), to.getZ());
            if (Double.isNaN(f1)) {
                return Premise.fail(to, Reason.NO_FOOTING);
            }
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
                Strides.inWater(draft, to), Strides.speedFactor(draft, from, f0, to, f1), 0, 0, 1, draft.edits(),
                contact.cells(), contact.exposure(), support));
    }

    /** 站着是从脚下的方块迈上去或跳上去({@link Stepping#between});攀着、浮着是在梯子、水里升上去再挪过去({@link Stepping#fromHold})。 */
    private static Stepping.Step geometry(Draft draft, BodyStats body, BlockPos from, double f0, boolean grounded,
                                          Heading heading, double f1) {
        return grounded
                ? Stepping.between(draft, body, from.getX(), f0, from.getZ(), heading.dx(), heading.dz(), f1)
                : Stepping.fromHold(draft, body, from.getX(), f0, from.getZ(), heading.dx(), heading.dz(), f1);
    }

    @Override
    public double cost(CostModel model, Maneuver m) {
        double move = Strides.pace(model, m);
        if (m.jump()) {
            move = Math.max(move, ActionCosts.JUMP_ONE_BLOCK) + model.spec().jumpPenalty();
        } else if (!m.start().grounded()) {
            move += ActionCosts.CLIMB_UP_ONE;
        }
        return move + model.overhead(m);
    }
}
