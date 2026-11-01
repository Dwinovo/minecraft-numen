package com.dwinovo.numen.pathing.plan;

import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Kinematics;

import net.minecraft.core.BlockPos;

/**
 * 起飞:创造飞行的身体站在地上,连按两下跳({@link Kinematics#TAKEOFF_TAPS} 刻:按、松、按,第二下切成飞行并起跳),飞到头顶上一格的空中。
 *
 * <p>前提:身体许飞(走法集只给许飞的身体);起步站着;头顶上一格的空中放得下身体({@link Stance#afloat}),起飞途中的身体盒也不撞方块。
 * 价钱是按键的几刻加上起飞之后加速到巡航的损失({@link Kinematics#takeoffTicks}),短途因此不起飞。不改地形。
 */
final class Takeoff implements Move {

    @Override
    public MoveKind kind() {
        return MoveKind.TAKEOFF;
    }

    private static final List<Heading> HEADINGS = List.of(Heading.UP);

    @Override
    public List<Heading> headings() {
        return HEADINGS;
    }

    @Override
    public Premise premise(CostModel model, WorldView view, BlockPos from, Stance stance, Heading heading) {
        if (!stance.grounded()) {
            return Premise.fail(from, Reason.WRONG_STANCE);
        }
        BodyStats body = model.body().stats();
        Draft draft = new Draft(model, view);
        BlockPos to = from.above();
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
        return new Premise.Holds(Flight.maneuver(MoveKind.TAKEOFF, heading, from, stance, to, landing, body, draft, contact, null));
    }

    /** 起步不是站着(精确判断必然不成立)答不成立;其余答"可能",价钱就是起飞的价(精确的只会再加按位置的价)。 */
    @Override
    public int quick(Quick q, Heading heading) {
        if (!q.stance().grounded()) {
            return Quick.REJECT;
        }
        return q.likely(0, 1, 0, Kinematics.takeoffTicks(q.model().body().mobility().flyingSpeed()));
    }

    @Override
    public double cost(CostModel model, Maneuver m) {
        return Kinematics.takeoffTicks(Flight.speed(model)) + model.overhead(m);
    }

    @Override
    public double ticks(CostModel model, Maneuver m) {
        return Kinematics.takeoffTicks(Flight.speed(model));
    }
}
