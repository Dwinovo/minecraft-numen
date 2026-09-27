package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.drive.Blockage.Hitch;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.Moves;
import com.dwinovo.numen.pathing.plan.Premise;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;

/**
 * 执行路线上的一步。开始之前在活世界上复核它的前提——与规划时是同一个 {@code Moves.of(kind).premise},同一个方向,
 * 成本模型按此刻的身体与端口现组一份(目标格保护照样并进去);成立就照这一次复核交出的 {@link Maneuver} 去做(世界若已
 * 替它挖开了一格,那一格就不必再挖),不成立就停下,报出是哪一格、什么方块、哪一条前提。执行中由 {@link Watchdog} 看它
 * 有没有超期。
 */
final class Step {

    private final Rig rig;
    private final Maneuver planned;
    private final Maneuver next;
    private final Goal goal;
    private final RouteSpec spec;
    private final Watchdog watchdog;
    private Control control;

    Step(Rig rig, Maneuver planned, Maneuver next, Goal goal, RouteSpec spec, Watchdog watchdog) {
        this.rig = rig;
        this.planned = planned;
        this.next = next;
        this.goal = goal;
        this.spec = spec;
        this.watchdog = watchdog;
    }

    Maneuver planned() {
        return planned;
    }

    /** 这一步此刻是计划内的坠落。 */
    boolean falls() {
        return control != null && control.falls();
    }

    /** 这一步还有收尾的事没做完,身体落在落点上也先不算走完。 */
    boolean holds() {
        return control != null && control.holds();
    }

    Beat tick() {
        if (control == null) {
            CostModel model = Goal.guarded(goal,
                    CostModel.of(spec, rig.snapshot(), rig.terrain, rig.materials, Threats.NONE));
            Premise premise = recheck(model);
            if (premise instanceof Premise.Fails fails) {
                return new Beat.Blocked(new Blockage(fails.cell(), rig.world().getBlockState(fails.cell()),
                        planned.kind(), fails.reason(), null));
            }
            Maneuver fresh = ((Premise.Holds) premise).maneuver();
            if (!fresh.to().equals(planned.to())) {
                return new Beat.Blocked(blocked(planned.to(), Hitch.DIVERTED));
            }
            control = Control.of(rig, fresh, next);
            watchdog.begin(Moves.of(fresh.kind()).cost(model, fresh), rig.entity.position());
        }
        Beat beat = control.tick();
        watchdog.observe(rig.entity.position(), beat instanceof Beat.Going going && going.worked());
        if (beat instanceof Beat.Going && watchdog.overran()) {
            return new Beat.Blocked(blocked(planned.to(), Hitch.STUCK));
        }
        return beat;
    }

    /** 同一个前提函数在活世界上再判一次。 */
    private Premise recheck(CostModel model) {
        LiveWorld world = rig.world();
        BlockPos from = planned.from();
        Stance stance = Stance.at(world, model.body().stats(), from);
        if (stance == null) {
            return Premise.fail(from, com.dwinovo.numen.pathing.plan.Reason.NOT_STANDING);
        }
        return Moves.of(planned.kind()).premise(model, world, from, stance, planned.heading());
    }

    private Blockage blocked(BlockPos cell, Hitch hitch) {
        return new Blockage(cell, rig.world().getBlockState(cell), planned.kind(), null, hitch);
    }
}
