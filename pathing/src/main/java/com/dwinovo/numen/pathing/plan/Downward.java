package com.dwinovo.numen.pathing.plan;

import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;

import net.minecraft.core.BlockPos;

/**
 * 向下挖:挖掉脚下托着身体的那一格,落下一个节点,站在再下面那一格上。
 *
 * <p>前提:规格开着向下挖;起步时站着,托着脚的正是下面那一格(站在半砖、地毯这类矮方块上时托着脚的是脚所在那一格,
 * 不从这里挖);那一格挖得了(规格、物理、许可,见 {@link CostModel#admitDig});挖掉后落下一个节点就站得住——再往下
 * 空着就不是这一步;落上去摔掉的血身体受得起(朝上的滴水石锥尖落一格就疼,{@link BodySnapshot#fallDamage})。
 * 挂在梯子上往下是 {@link Climb}。
 */
final class Downward implements Move {

    @Override
    public MoveKind kind() {
        return MoveKind.DOWNWARD;
    }

    @Override
    public List<Heading> headings() {
        return List.of(Heading.DOWN);
    }


    @Override
    public Premise premise(CostModel model, WorldView view, BlockPos from, Stance stance, Heading heading) {
        if (!model.spec().downward()) {
            return Premise.fail(from, Reason.DISABLED);
        }
        BlockPos below = from.below();
        if (!stance.grounded() || stance.supportY() != below.getY()) {
            return Premise.fail(from, Reason.WRONG_STANCE);
        }
        BodyStats body = model.body().stats();
        Draft draft = new Draft(model, view);
        double f0 = stance.feetY();
        if (!draft.dig(below, from.getX(), f0, from.getZ(), true)) {
            return draft.failure();
        }
        Stance landing = Stance.at(draft, body, below);
        if (landing == null || !landing.grounded()) {
            return Premise.fail(below, Reason.NO_FOOTING);
        }
        Contact contact = new Contact(body, from, f0).column(from.getX(), from.getZ(), landing.feetY(), f0);
        BlockPos support = landing.support(below.getX(), below.getZ());
        double drop = f0 - landing.feetY();
        boolean wading = Strides.inWater(draft, below);
        int damage = Strides.fallDamage(model, draft, landing, support, drop, wading);
        if (!model.body().bears(damage)) {
            return Premise.fail(support, Reason.TOO_FAR_TO_FALL);
        }
        if (!contact.admit(draft, model, support, drop > 0.5)) {
            return draft.failure();
        }
        return new Premise.Holds(new Maneuver(MoveKind.DOWNWARD, heading, from, stance, below, landing, false, false, false,
                wading, 1, drop, damage, 0, draft.edits(), contact.cells(), contact.exposure(), support));
    }

    @Override
    public double cost(CostModel model, Maneuver m) {
        return Strides.landing(model, m) + model.overhead(m);
    }
}
