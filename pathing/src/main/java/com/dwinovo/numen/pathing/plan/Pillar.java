package com.dwinovo.numen.pathing.plan;

import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Semantics;

import net.minecraft.core.BlockPos;

/**
 * 垫柱:原地起跳,在脚刚离开的那一格放一块,落在它上面,升一个节点。
 *
 * <p>前提:起步时站在地上,脚所在那一格不泡在液体里(水里跳不起来);那一格放得进一块垫路料(要改地形、要有料,放的那一刻
 * 身体已经跳离了它);放下后脚落在新方块上的高度不超过起跳能升的高度;头顶挡着的挖开、门就开关。在梯子、藤蔓上往上是
 * {@link Climb},不是垫柱。
 */
final class Pillar implements Move {

    @Override
    public MoveKind kind() {
        return MoveKind.PILLAR;
    }

    @Override
    public List<Heading> headings() {
        return List.of(Heading.UP);
    }


    @Override
    public Premise premise(CostModel model, WorldView view, BlockPos from, Stance stance, Heading heading) {
        if (!stance.grounded() || Strides.feetInFluid(view, from)) {
            return Premise.fail(from, Reason.WRONG_STANCE);
        }
        BodyStats body = model.body().stats();
        Draft draft = new Draft(model, view);
        BlockPos to = from.above();
        double f0 = stance.feetY();
        double peak = f0 + body.jumpHeight(Semantics.jumpFactor(view, from.getX(), f0, from.getZ()));
        // 头顶先腾出来:身体要在垫好的那一块上放得下
        if (!draft.clear(Strides.at(draft, body, to, to.getY()), true, from.getX(), f0, from.getZ(), true)) {
            return draft.failure();
        }
        // 放的那一刻身体已经跳到落点的脚高
        if (!draft.place(from, from.getX(), to.getY(), from.getZ())) {
            return draft.failure();
        }
        double f1 = Footing.height(draft, body, to.getX(), to.getY(), to.getZ());
        if (Double.isNaN(f1)) {
            return Premise.fail(to, Reason.NO_FOOTING);
        }
        if (f1 > peak + Footing.EPSILON) {
            return Premise.fail(to, Reason.TOO_HIGH);
        }
        Stance landing = Stance.at(draft, body, to);
        if (landing == null) {
            return Premise.fail(to, Reason.NO_CLEARANCE);
        }
        Contact contact = new Contact(body, from, f0).column(from.getX(), from.getZ(), f0, f1);
        BlockPos support = landing.support(to.getX(), to.getZ());
        if (!contact.admit(draft, model, support, false)) {
            return draft.failure();
        }
        return new Premise.Holds(new Maneuver(MoveKind.PILLAR, heading, from, stance, to, landing, true, false, false, false,
                Strides.submerged(draft, body, from, stance, to, landing), 1, 0, 0, 0, draft.edits(), contact.cells(), contact.exposure(), support));
    }

    @Override
    public double cost(CostModel model, Maneuver m) {
        return Strides.jump(model) + model.overhead(m);
    }

    @Override
    public double ticks(CostModel model, Maneuver m) {
        return ActionCosts.JUMP_ONE_BLOCK + model.workTicks(m);
    }
}
