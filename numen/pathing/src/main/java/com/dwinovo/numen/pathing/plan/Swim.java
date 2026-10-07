package com.dwinovo.numen.pathing.plan;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Footing;

import net.minecraft.core.BlockPos;

/**
 * 游:脚泡在水里时,往上浮一格、往下沉一格,或朝东南西北平挪一格。往上只游进水里(浮到水面再往上是出水,由
 * {@link Ascend} 一步跨上岸);往下、平挪可以落到水底站着。在水里不挖不放,只开关挡路的门。
 *
 * <p>水流不流由第 0 层语义按原版水流判:流水默认被路线规格排除,放开时照样能游,只是执行时会被推着走。
 */
final class Swim implements Move {

    private static final List<Heading> HEADINGS;

    static {
        List<Heading> all = new ArrayList<>(Heading.CARDINAL);
        all.add(Heading.UP);
        all.add(Heading.DOWN);
        HEADINGS = List.copyOf(all);
    }

    @Override
    public MoveKind kind() {
        return MoveKind.SWIM;
    }

    @Override
    public List<Heading> headings() {
        return HEADINGS;
    }


    @Override
    public Premise premise(CostModel model, WorldView view, BlockPos from, Stance stance, Heading heading) {
        if (!Strides.inWater(view, from)) {
            return Premise.fail(from, Reason.WRONG_STANCE);
        }
        BodyStats body = model.body().stats();
        Draft draft = new Draft(model, view);
        BlockPos to = from.offset(heading.dx(), heading.dy(), heading.dz());
        double f0 = stance.feetY();
        if (heading.dy() > 0 && !Strides.inWater(draft, to)) {
            return Premise.fail(to, Reason.NO_FOOTING);
        }
        double f1 = Footing.height(draft, body, to.getX(), to.getY(), to.getZ());
        double target = Double.isNaN(f1) ? to.getY() : f1;
        List<BlockPos> blockers = heading.horizontal()
                ? Strides.union(Strides.at(draft, body, to, target), Strides.across(draft, body, from, heading, Math.max(f0, target)))
                : Strides.at(draft, body, to, target);
        if (!draft.clear(blockers, false, from.getX(), f0, from.getZ(), stance.grounded())) {
            return draft.failure();
        }
        Stance landing = Stance.at(draft, body, to);
        if (landing == null || landing.kind() == Stance.Kind.CLIMBING) {
            return Premise.fail(to, Reason.NO_CLEARANCE);
        }
        if (landing.grounded() && heading.horizontal() && landing.feetY() > f0 + body.stepHeight() + Footing.EPSILON) {
            // 水底的坎高过迈步高度:那是上一级,不是平挪
            return Premise.fail(to, Reason.TOO_HIGH);
        }
        Contact contact = new Contact(body, from, f0).column(to.getX(), to.getZ(), f0, landing.feetY());
        BlockPos support = landing.support(to.getX(), to.getZ());
        if (!contact.admit(draft, model, support, false)) {
            return draft.failure();
        }
        return new Premise.Holds(new Maneuver(MoveKind.SWIM, heading, from, stance, to, landing, false, false, false, true,
                Strides.submerged(draft, body, from, stance, to, landing), 1, Math.max(0, f0 - landing.feetY()), 0, heading.horizontal() ? 1 : 0, draft.edits(), contact.cells(), contact.exposure(),
                support));
    }

    @Override
    public double cost(CostModel model, Maneuver m) {
        return model.waterStep() + model.overhead(m);
    }

    @Override
    public double ticks(CostModel model, Maneuver m) {
        return model.waterStep() + model.workTicks(m);
    }
}
