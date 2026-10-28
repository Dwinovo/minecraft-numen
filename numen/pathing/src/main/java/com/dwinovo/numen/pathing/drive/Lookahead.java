package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.drive.Blockage.Hitch;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.EditedView;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.Moves;
import com.dwinovo.numen.pathing.plan.Premise;
import com.dwinovo.numen.pathing.plan.Reason;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.plan.WorldView;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;

/**
 * 往前核:路线上的一步开始之前,在活世界上用精确的前提核对从这一步起的 {@link #STEPS} 步。判据与搜索取出时是同一个
 * {@code Moves.of(kind).premise},同一个方向;成本模型按此刻的身体与端口现组一份(目标格保护照样并进去,{@link Goal#guarded})。
 * 搜索读快照,这里读活世界:别人放了方块、水流过来、沙子落下,已核的步失效,在走到跟前之前就看得出来。
 *
 * <p>后面几步读到的世界叠上前面几步的改动({@link EditedView#after}),与搜索展开一个节点时叠"走到它的最近几步"的改动同一种叠法:
 * 前面一步要挖开的格,后面一步的前提看到的是挖开之后的样子,不误判成挡路。窗口只有 {@link #STEPS} 步,在搜索叠的步数
 * ({@code AStar.HISTORY})以内,窗口里前面的步一步不落都叠上;窗口之前已经走完的步,改动已在活世界里。后面一步的起步姿态
 * 是前面一步复核交出的落定姿态,也与搜索一样;头一步的起步姿态按身体此刻所在的活世界定。
 *
 * <p>世界变了不靠订阅事件发现,靠这里每一步开始时读一遍:模块只依赖原版,不另挂接缝。
 */
final class Lookahead {

    /**
     * 往前核几步(含将要开始的这一步)。依据:Baritone {@code PathExecutor} 的 {@code costVerificationLookahead} 也是 5;
     * 疾跑一步约 4 刻,五步约一秒,够在走到跟前之前停下,又只在一步开始时做一次,主线程上是五次前提判断。
     */
    static final int STEPS = 5;

    /** 往前核的结论。 */
    sealed interface Verdict {}

    /** 这几步都成立:照 {@code fresh}(将要开始的这一步在活世界上复核交出的样子)去做,{@code model} 是这一次组的成本模型。 */
    record Clear(Maneuver fresh, CostModel model) implements Verdict {}

    /** {@code step} 这一步不成立,{@code blockage} 是卡在哪一格、为什么。 */
    record Broken(Maneuver step, Blockage blockage) implements Verdict {}

    private Lookahead() {}

    /** 从 {@code course} 正在走的那一步起往前核。 */
    static Verdict verify(Rig rig, Goal goal, RouteSpec spec, Course course) {
        long t0 = System.nanoTime();
        int judged = 0;
        try {
            CostModel model = Goal.guarded(goal, CostModel.of(spec, rig.snapshot(), rig.terrain, rig.materials, Threats.NONE));
            WorldView view = rig.world();
            Maneuver first = null;
            Stance stance = null;
            int end = Math.min(course.size(), course.cur() + STEPS);
            for (int i = course.cur(); i < end; i++) {
                Maneuver planned = course.at(i);
                BlockPos from = planned.from();
                if (first == null) {
                    stance = Stance.at(view, model.body().stats(), from);
                }
                judged++;
                Premise premise = stance == null ? Premise.fail(from, Reason.NOT_STANDING)
                        : Moves.of(planned.kind()).premise(model, view, from, stance, planned.heading());
                if (premise instanceof Premise.Fails fails) {
                    return new Broken(planned, new Blockage(fails.cell(), view.getBlockState(fails.cell()), planned.kind(),
                            fails.reason(), null));
                }
                Maneuver fresh = ((Premise.Holds) premise).maneuver();
                if (!fresh.to().equals(planned.to())) {
                    return new Broken(planned, new Blockage(planned.to(), view.getBlockState(planned.to()), planned.kind(), null,
                            Hitch.DIVERTED));
                }
                if (first == null) {
                    first = fresh;
                }
                view = EditedView.after(view, fresh.edits());
                stance = fresh.landing();
            }
            return new Clear(first, model);
        } finally {
            long nanos = System.nanoTime() - t0;
            rig.tally.rechecked(judged, nanos);
            rig.journal.rechecked(judged, nanos);
        }
    }
}
