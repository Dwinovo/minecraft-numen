package com.dwinovo.numen.pathing.plan;

import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;

import net.minecraft.core.BlockPos;

/**
 * 降落:飞着的身体飞到一个站得住的地方,落地即停飞(原版客户端的规则)。方向是东南西北平着、下一格、上一格,四个斜方向平着,以及正下方。
 *
 * <p>前提:起步飞着;落点是站着({@link Stance#at},踩着东西、身体放得下)——水里、梯子上不是降落;沿途的身体盒不撞方块;落点踩的格与新进入的格
 * 过路线规格({@link Contact})。创造飞行的身体落地不摔({@code Player.causeFallDamage} 在能飞时直接返回)。价钱是飞过去的耗时加停下来要付的:
 * 从巡航速度停在落点上({@link Flight#stopLoss}),落点就在手边,冲过头就掉出落点那一格。
 */
final class Land implements Move {

    @Override
    public MoveKind kind() {
        return MoveKind.LAND;
    }

    @Override
    public List<Heading> headings() {
        return Flight.ALIGHT;
    }

    @Override
    public boolean departsFrom(Stance stance) {
        return stance.kind() == Stance.Kind.FLYING;
    }

    @Override
    public Premise premise(CostModel model, WorldView view, BlockPos from, Stance stance, Heading heading) {
        if (stance.kind() != Stance.Kind.FLYING) {
            return Premise.fail(from, Reason.WRONG_STANCE);
        }
        BodyStats body = model.body().stats();
        Draft draft = new Draft(model, view);
        BlockPos to = from.offset(heading.dx(), heading.dy(), heading.dz());
        Stance landing = Stance.at(draft, body, to);
        if (landing == null || !landing.grounded()) {
            return Premise.fail(to, Reason.NO_FOOTING);
        }
        List<BlockPos> blockers = Flight.blockers(draft, body, from, stance.feetY(), to, landing.feetY());
        if (!blockers.isEmpty()) {
            return Premise.fail(blockers.get(0), Reason.NO_CLEARANCE);
        }
        Contact contact = Flight.contact(body, from, stance.feetY(), to, landing.feetY());
        BlockPos support = landing.support(to.getX(), to.getZ());
        if (!contact.admit(draft, model, support, false)) {
            return draft.failure();
        }
        return new Premise.Holds(Flight.maneuver(MoveKind.LAND, heading, from, stance, to, landing, body, draft, contact, support));
    }

    /** 起步不是飞着答不成立;其余答"可能",价钱是这个方向降落至少要的刻数。 */
    @Override
    public int quick(Quick q, Heading heading) {
        if (q.stance().kind() != Stance.Kind.FLYING) {
            return Quick.REJECT;
        }
        return q.likely(heading.dx(), heading.dy(), heading.dz(), cheapest(q.model(), heading));
    }

    /** 这个方向降落至少多少刻:竖直只算一定要走的整格(落点脚高在落点格里往上最多 1 格),加刹车的损失。 */
    private static double cheapest(CostModel model, Heading heading) {
        return Flight.ticks(model, heading, Math.max(0, Math.abs(heading.dy()) - 1)) + Flight.stopLoss(model);
    }

    @Override
    public double cost(CostModel model, Maneuver m) {
        return ticks(model, m) + model.overhead(m);
    }

    @Override
    public double ticks(CostModel model, Maneuver m) {
        return Flight.ticks(model, m.heading(), m.landing().feetY() - m.start().feetY()) + Flight.stopLoss(model);
    }
}
