package com.dwinovo.numen.pathing.plan;

import com.dwinovo.numen.pathing.TestWorld;

import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 单测里判一步前提的小工具:身体在起点怎么待着由第 1 层自己的 {@link Stance} 定。 */
final class Steps {

    private Steps() {}

    static Premise premise(MoveKind kind, CostModel model, TestWorld world, BlockPos from, Heading heading) {
        Stance stance = Stance.at(world, model.body().stats(), from);
        assertNotNull(stance, "起点 " + from + " 身体待不住");
        return Moves.of(kind).premise(model, world, from, stance, heading);
    }

    static Maneuver holds(MoveKind kind, CostModel model, TestWorld world, BlockPos from, Heading heading) {
        Premise p = premise(kind, model, world, from, heading);
        return assertInstanceOf(Premise.Holds.class, p, () -> kind + " 应当成立,却是 " + p).maneuver();
    }

    static Premise.Fails fails(MoveKind kind, CostModel model, TestWorld world, BlockPos from, Heading heading) {
        Premise p = premise(kind, model, world, from, heading);
        return assertInstanceOf(Premise.Fails.class, p, () -> kind + " 应当不成立,却是 " + p);
    }

    static double cost(CostModel model, Maneuver m) {
        return Moves.of(m.kind()).cost(model, m);
    }
}
