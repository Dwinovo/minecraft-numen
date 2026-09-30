package com.dwinovo.numen.pathing.plan;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Stepping;

import net.minecraft.core.BlockPos;

/**
 * 斜走:进斜对角一列,平着走,或(规格开着时)斜着上一级、下一级。身体从两列的交角擦过去,两侧那两列也要让得开——
 * 切角挡不挡由第 0 层 {@link Stepping} 从四列的碰撞箱推导。斜走不改地形,只开关挡路的门。
 */
final class Diagonal implements Move {

    private static final List<Heading> HEADINGS;

    static {
        List<Heading> all = new ArrayList<>();
        for (Heading h : Heading.DIAGONAL) {
            all.add(h);
            all.add(h.withDy(1));
            all.add(h.withDy(-1));
        }
        HEADINGS = List.copyOf(all);
    }

    @Override
    public MoveKind kind() {
        return MoveKind.DIAGONAL;
    }

    @Override
    public List<Heading> headings() {
        return HEADINGS;
    }


    @Override
    public Premise premise(CostModel model, WorldView view, BlockPos from, Stance stance, Heading heading) {
        if ((heading.dy() > 0 && !model.spec().diagonalAscend()) || (heading.dy() < 0 && !model.spec().diagonalDescend())) {
            return Premise.fail(from, Reason.DISABLED);
        }
        if (!stance.grounded()) {
            return Premise.fail(from, Reason.WRONG_STANCE);
        }
        BodyStats body = model.body().stats();
        Draft draft = new Draft(model, view);
        BlockPos to = from.offset(heading.dx(), heading.dy(), heading.dz());
        double f0 = stance.feetY();
        double f1 = Footing.height(draft, body, to.getX(), to.getY(), to.getZ());
        if (Double.isNaN(f1)) {
            return Premise.fail(to, Reason.NO_FOOTING);
        }
        int x = from.getX();
        int z = from.getZ();
        double low = Math.min(f0, f1);
        double high = Math.max(f0, f1);
        Stepping.Step step = Stepping.between(draft, body, x, f0, z, heading.dx(), heading.dz(), f1);
        if (step == Stepping.Step.BLOCKED) {
            // 挡着的若是门,开关一下再判;别的不挖
            List<BlockPos> blockers = Strides.union(Strides.at(draft, body, to, f1),
                    Strides.across(draft, body, from, heading, high));
            if (!draft.clear(blockers, false, x, f0, z, true)) {
                return draft.failure();
            }
            step = Stepping.between(draft, body, x, f0, z, heading.dx(), heading.dz(), f1);
            if (step == Stepping.Step.BLOCKED) {
                return Premise.fail(to, Reason.NO_CLEARANCE);
            }
        }
        Stance landing = Stance.at(draft, body, to);
        if (landing == null || !landing.grounded()) {
            return Premise.fail(to, Reason.NO_CLEARANCE);
        }
        boolean jump = step == Stepping.Step.JUMP;
        Contact contact = new Contact(body, from, f0)
                .column(to.getX(), to.getZ(), low, high)
                .column(x + heading.dx(), z, low, high)
                .column(x, z + heading.dz(), low, high);
        BlockPos support = landing.support(to.getX(), to.getZ());
        boolean wading = Strides.inWater(draft, to);
        double drop = Math.max(0, f0 - f1);
        int damage = Strides.fallDamage(model, draft, landing, support, drop, wading);
        if (!model.body().bears(damage)) {
            return Premise.fail(support, Reason.TOO_FAR_TO_FALL);
        }
        if (!contact.admit(draft, model, support, f0 - f1 > 0.5)) {
            return draft.failure();
        }
        boolean sprint = model.maySprint() && !wading && !jump && draft.edits().isEmpty();
        return new Premise.Holds(new Maneuver(MoveKind.DIAGONAL, heading, from, stance, to, landing, jump, sprint, false, wading,
                Strides.submerged(draft, body, from, stance, to, landing), Strides.speedFactor(draft, from, f0, to, f1), drop, damage, 1, draft.edits(),
                contact.cells(), contact.exposure(), support));
    }

    @Override
    public double cost(CostModel model, Maneuver m) {
        return movement(model, m) + (m.jump() ? model.spec().jumpPenalty() : 0)
                + (m.drop() > 0.5 ? Strides.bruise(m) : 0) + model.overhead(m);
    }

    @Override
    public double ticks(CostModel model, Maneuver m) {
        return movement(model, m) + model.workTicks(m);
    }

    /** 身体斜着过去的刻数:斜走一格,要跳的至少是起跳上一格的工夫,落差过半格的加上落地。 */
    private static double movement(CostModel model, Maneuver m) {
        double move = Strides.pace(model, m) * Math.sqrt(2);
        if (m.jump()) {
            move = Math.max(move, ActionCosts.JUMP_ONE_BLOCK);
        }
        return m.drop() > 0.5 ? move + Strides.landing(m) : move;
    }
}
