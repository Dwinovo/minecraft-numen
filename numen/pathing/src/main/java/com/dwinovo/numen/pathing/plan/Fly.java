package com.dwinovo.numen.pathing.plan;

import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;

import net.minecraft.core.BlockPos;

/**
 * 飞:飞着的身体在空中挪一格——东南西北与四个斜方向平飞,上、下,以及朝东南西北边飞边升降一格。落在空中的另一格({@link Stance#afloat}):
 * 那一格要是站得住(有东西托、是水、是梯子)就不是飞,是降落({@link Land})。
 *
 * <p>前提:起步飞着;落点放得下身体;起步到落点沿途的身体盒不撞方块({@link Flight#blockers},斜着飞按包络,宁可多挡);新进入的格过路线规格
 * ({@link Contact},排除的种类、禁令、挨着伤身格的加价)。价钱是水平疾飞与竖直升降里长的那个({@link Flight#ticks}),按稳态速度算,
 * 起飞的加速与降落的刹车另在 {@link Takeoff}、{@link Land}。不改地形。
 */
final class Fly implements Move {

    @Override
    public MoveKind kind() {
        return MoveKind.FLY;
    }

    @Override
    public List<Heading> headings() {
        return Flight.CRUISE;
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
        Stance landing = Stance.afloat(draft, body, to.getX(), to.getY(), to.getZ());
        if (landing == null) {
            return Premise.fail(to, Reason.NO_CLEARANCE);
        }
        List<BlockPos> blockers = Flight.blockers(draft, body, from, stance.feetY(), to, landing.feetY());
        if (!blockers.isEmpty()) {
            return Premise.fail(blockers.get(0), Reason.NO_CLEARANCE);
        }
        Contact contact = Flight.contact(body, from, stance.feetY(), to, landing.feetY());
        if (!contact.admit(draft, model, null, false)) {
            return draft.failure();
        }
        return new Premise.Holds(Flight.maneuver(MoveKind.FLY, heading, from, stance, to, landing, body, draft, contact, null));
    }

    /** 起步不是飞着答不成立;其余答"可能",价钱是这个方向的飞行耗时(精确的只会再加按位置的价)。 */
    @Override
    public int quick(Quick q, Heading heading) {
        if (q.stance().kind() != Stance.Kind.FLYING) {
            return Quick.REJECT;
        }
        return q.likely(heading.dx(), heading.dy(), heading.dz(), Flight.ticks(q.model(), heading));
    }

    @Override
    public double cost(CostModel model, Maneuver m) {
        return Flight.ticks(model, m.heading()) + model.overhead(m);
    }

    @Override
    public double ticks(CostModel model, Maneuver m) {
        return Flight.ticks(model, m.heading());
    }
}
