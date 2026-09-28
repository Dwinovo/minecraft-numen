package com.dwinovo.numen.pathing.plan;

import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Semantics;
import com.dwinovo.numen.pathing.world.Semantics.Kind;

import net.minecraft.core.BlockPos;

/**
 * 攀爬:顺着可攀爬的格(第 0 层语义 {@link Kind#CLIMBABLE}:原版 {@code #climbable} 标签——梯子、藤蔓、垂泪藤、缠怨藤、
 * 洞穴藤蔓、脚手架——以及接在梯子上的开着的活板门)上下一格。原版里身体在可攀爬的格里按住跳或贴着墙推就往上爬,
 * 不推就慢慢往下滑,所以藤蔓不必贴着墙,哪一种可攀爬的格都是同一个走法。
 *
 * <p>往上:脚所在的格能攀爬,上面一格也能攀爬、身体在那里放得下;头顶挡着的门(阁楼口的活板门)开关一下,别的挖开——
 * 挂在梯子上挖脚不着地,原版挖得慢五倍,定价自然算进去。爬到顶端再往上是 {@link Ascend} 一步跨到旁边的方块上。
 * 往下:脚所在的格能攀爬,下面一格攀得住或站得住。
 */
final class Climb implements Move {

    @Override
    public MoveKind kind() {
        return MoveKind.CLIMB;
    }

    @Override
    public List<Heading> headings() {
        return List.of(Heading.UP, Heading.DOWN);
    }


    @Override
    public Premise premise(CostModel model, WorldView view, BlockPos from, Stance stance, Heading heading) {
        if (!Semantics.is(view, from, Kind.CLIMBABLE)) {
            return Premise.fail(from, Reason.WRONG_STANCE);
        }
        BodyStats body = model.body().stats();
        Draft draft = new Draft(model, view);
        BlockPos to = from.offset(0, heading.dy(), 0);
        double f0 = stance.feetY();
        if (heading.dy() > 0) {
            // 先腾出头顶(打开的活板门接在梯子上也能攀),再看上面一格攀不攀得住
            if (!draft.clear(Strides.at(draft, body, to, to.getY()), true,
                    from.getX(), f0, from.getZ(), stance.grounded())) {
                return draft.failure();
            }
            if (!Semantics.is(draft, to, Kind.CLIMBABLE)) {
                return Premise.fail(to, Reason.NO_FOOTING);
            }
        }
        Stance landing = Stance.at(draft, body, to);
        if (landing == null || (landing.kind() != Stance.Kind.CLIMBING && !landing.grounded())) {
            return Premise.fail(to, heading.dy() > 0 ? Reason.NO_CLEARANCE : Reason.NO_FOOTING);
        }
        Contact contact = new Contact(body, from, f0).column(to.getX(), to.getZ(), f0, landing.feetY());
        BlockPos support = landing.support(to.getX(), to.getZ());
        if (!contact.admit(draft, model, support, false)) {
            return draft.failure();
        }
        return new Premise.Holds(new Maneuver(MoveKind.CLIMB, heading, from, stance, to, landing, false, false, false,
                Strides.inWater(draft, to), 1, Math.max(0, f0 - landing.feetY()), 0, 0, draft.edits(), contact.cells(), contact.exposure(),
                support));
    }

    @Override
    public double cost(CostModel model, Maneuver m) {
        double move = m.to().getY() > m.from().getY() ? ActionCosts.CLIMB_UP_ONE : ActionCosts.CLIMB_DOWN_ONE;
        return move + model.overhead(m);
    }
}
