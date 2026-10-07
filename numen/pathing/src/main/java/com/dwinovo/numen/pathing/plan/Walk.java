package com.dwinovo.numen.pathing.plan;

import java.util.List;
import java.util.Set;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Faces;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Stepping;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 平走:进东南西北相邻一列,落在同一个节点高度(脚高可以在这一格里上下,比如走上下半砖、走下地毯)。
 *
 * <p>前提:起步时站着或攀着(浮着平挪是 {@link Swim});落点那一格托得住脚——托不住时,那一格是水或能攀爬就走进去浮着或
 * 攀着,否则在它脚下搭一块桥(要改地形、要有料;只剩起步那块的侧面可贴时潜行探出去放)。几何照第 0 层 {@link Stepping}
 * 判;过不去时,把身体走过去途中与落定后挡着它的格腾出来——门就开关、其余挖开——再判一次。
 */
final class Walk implements Move {

    @Override
    public MoveKind kind() {
        return MoveKind.WALK;
    }

    @Override
    public List<Heading> headings() {
        return Heading.CARDINAL;
    }


    @Override
    public Premise premise(CostModel model, WorldView view, BlockPos from, Stance stance, Heading heading) {
        if (stance.kind() == Stance.Kind.SWIMMING) {
            return Premise.fail(from, Reason.WRONG_STANCE);
        }
        BodyStats body = model.body().stats();
        Draft draft = new Draft(model, view);
        BlockPos to = from.offset(heading.dx(), 0, heading.dz());
        double f0 = stance.feetY();
        boolean grounded = stance.grounded();
        boolean sneak = false;
        boolean hanging = false;

        double f1 = Footing.height(draft, body, to.getX(), to.getY(), to.getZ());
        if (Double.isNaN(f1)) {
            if (Stance.hangs(draft, to)) {
                // 走进水里浮着,或走到梯子上攀着
                hanging = true;
                f1 = to.getY();
            } else {
                // 脚下搭一块桥:站在起步那一格上放
                if (!grounded) {
                    return Premise.fail(to, Reason.NO_FOOTING);
                }
                BlockPos bridge = to.below();
                if (!draft.place(bridge, from.getX(), f0, from.getZ())) {
                    return draft.failure();
                }
                f1 = Footing.height(draft, body, to.getX(), to.getY(), to.getZ());
                if (Double.isNaN(f1)) {
                    return Premise.fail(to, Reason.NO_FOOTING);
                }
                sneak = onlyBackFace(draft, model, bridge, from);
            }
        }
        Stepping.Step step = geometry(draft, body, from, f0, grounded, heading, f1, hanging);
        if (step == Stepping.Step.BLOCKED) {
            List<BlockPos> blockers = Strides.union(Strides.at(draft, body, to, f1),
                    Strides.across(draft, body, from, heading, Math.max(f0, f1)));
            if (!draft.clear(blockers, true, from.getX(), f0, from.getZ(), grounded)) {
                return draft.failure();
            }
            step = geometry(draft, body, from, f0, grounded, heading, f1, hanging);
            if (step == Stepping.Step.BLOCKED) {
                return Premise.fail(to, f1 - f0 > body.stepHeight() ? Reason.TOO_HIGH : Reason.NO_CLEARANCE);
            }
        }
        Stance landing = Stance.at(draft, body, to);
        if (landing == null) {
            return Premise.fail(to, Reason.NO_CLEARANCE);
        }
        boolean jump = step == Stepping.Step.JUMP;
        Contact contact = new Contact(body, from, f0).column(to.getX(), to.getZ(), f0, f1);
        if (jump) {
            contact.column(from.getX(), from.getZ(), f0, f1);
        }
        BlockPos support = landing.support(to.getX(), to.getZ());
        if (!contact.admit(draft, model, support, f0 - f1 > 0.5)) {
            return draft.failure();
        }
        boolean wading = Strides.inWater(draft, to);
        boolean sprint = model.maySprint() && !wading && !jump && draft.edits().isEmpty();
        return new Premise.Holds(new Maneuver(MoveKind.WALK, heading, from, stance, to, landing, jump, sprint, sneak, wading,
                Strides.submerged(draft, body, from, stance, to, landing), Strides.speedFactor(draft, from, f0, to, f1), Math.max(0, f0 - f1), 0, 1, draft.edits(),
                contact.cells(), contact.exposure(), support));
    }

    /**
     * 走过去的几何:攀在梯子上起步时,在梯子那一列里挪过去({@link Stepping#fromHold});站着起步、落点托得住脚时照
     * {@link Stepping#between};走进水里、梯子上时,迈得过去且脚下没有东西把身体托在这个节点之上就行(水与梯子接住身体是
     * 语义,碰撞箱表达不了)。
     */
    private static Stepping.Step geometry(Draft draft, BodyStats body, BlockPos from, double f0, boolean grounded,
                                          Heading heading, double f1, boolean hanging) {
        if (!grounded) {
            return Stepping.fromHold(draft, body, from.getX(), f0, from.getZ(), heading.dx(), heading.dz(), f1);
        }
        if (!hanging) {
            return Stepping.between(draft, body, from.getX(), f0, from.getZ(), heading.dx(), heading.dz(), f1);
        }
        double after = Stepping.walkOff(draft, body, from.getX(), f0, from.getZ(), heading.dx(), heading.dz(), f1);
        return after <= f1 + Footing.EPSILON ? Stepping.Step.WALK : Stepping.Step.BLOCKED;
    }

    /**
     * 往桥位放的这一块,能点的面只剩起步那一列脚下那块朝前的侧面:要潜行探出边沿、回身去点它。
     */
    private static boolean onlyBackFace(Draft draft, CostModel model, BlockPos bridge, BlockPos from) {
        Set<Direction> faces = Faces.against(draft, bridge, model.placing().orElseThrow());
        Direction back = Direction.getNearest(from.getX() - bridge.getX(), 0, from.getZ() - bridge.getZ());
        return faces.size() == 1 && faces.contains(back);
    }

    @Override
    public double cost(CostModel model, Maneuver m) {
        return movement(model, m) + (m.jump() ? model.spec().jumpPenalty() : 0) + model.overhead(m);
    }

    @Override
    public double ticks(CostModel model, Maneuver m) {
        return movement(model, m) + model.workTicks(m);
    }

    /** 身体走过去的刻数:一格的步速,要跳的至少是起跳上一格的工夫。 */
    private static double movement(CostModel model, Maneuver m) {
        double move = Strides.pace(model, m);
        return m.jump() ? Math.max(move, ActionCosts.JUMP_ONE_BLOCK) : move;
    }
}
