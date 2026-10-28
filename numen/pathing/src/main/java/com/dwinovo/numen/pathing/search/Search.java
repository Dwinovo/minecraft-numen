package com.dwinovo.numen.pathing.search;

import java.util.Objects;

import com.dwinovo.numen.pathing.plan.Breath;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Maneuver;

import net.minecraft.core.BlockPos;

/**
 * 一次搜索要的全部输入:读哪份世界、按哪份成本模型、从哪个节点出发、去哪、最多展开几个节点、最多想多久、沿不沿旧路,以及接着一条路线
 * 往下搜时的两样:起点是接在哪一步后面、这一趟的记忆。搜索的目标就是真终点,没有"一段"。
 *
 * @param start    起点节点:从身体脚下搜时由 {@link Origin} 从身体的真实位置定出,接着一条路线往下搜时是那条路线的终点
 * @param budget   最多展开几个节点:只防一次搜索吃掉太多内存,也是这一趟还剩多少节点({@code drive.Allowance});按节点数计,
 *                 不随机器快慢变
 * @param memory   这一趟的记忆冻下来的一份({@link TripMemory#frozen}):估价里抬高过的增量,和不许再走的步
 * @param arrival  走到起点的那一步:接着一条路线的终点往下搜时,身体到那里时怎么待着、那一步做过哪些改动,都照它算——
 *                 快照里还没有那一步垫下的块;从身体脚下搜为 null
 * @param air      身体在起点时憋气的样子:从脚下搜是身体此刻的({@link Breath#now}),接着一条路线往下搜是走完那一步时的
 * @param deadline 最多想多久:时间到了交出手里最好的半程;{@link Deadline#NONE} 不限时
 */
public record Search(SearchView view, CostModel model, BlockPos start, Goal goal, int budget, Favoring favoring,
                     TripMemory.Frozen memory, Maneuver arrival, Breath.Air air, Deadline deadline) {

    public Search {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(favoring, "favoring");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(air, "air");
        Objects.requireNonNull(deadline, "deadline");
        start = start.immutable();
        if (budget <= 0) {
            throw new IllegalArgumentException("展开预算要是正数:" + budget);
        }
        if (arrival != null && !arrival.to().equals(start)) {
            throw new IllegalArgumentException("起点 " + start + " 不是那一步的落点 " + arrival.to());
        }
    }

    /** 从身体脚下出发、不限时的一次搜索:憋气从身体此刻的样子起,没有记忆。 */
    public Search(SearchView view, CostModel model, BlockPos start, Goal goal, int budget, Favoring favoring) {
        this(view, model, start, goal, budget, favoring, TripMemory.Frozen.NONE, null, model.body().breath().now(),
                Deadline.NONE);
    }

    /** 同一次搜索,最多想到 {@code deadline}。 */
    public Search within(Deadline deadline) {
        return new Search(view, model, start, goal, budget, favoring, memory, arrival, air, deadline);
    }

    /** 同一次搜索,带着这一趟的记忆。 */
    public Search remembering(TripMemory.Frozen memory) {
        return new Search(view, model, start, goal, budget, favoring, memory, arrival, air, deadline);
    }

    /** 同一次搜索,起点是 {@code step} 这一步的落点:接着一条路线往下搜,憋气从走完这一步时的样子起。 */
    public Search after(Route.Leg step) {
        return step == null ? this
                : new Search(view, model, start, goal, budget, favoring, memory, step.maneuver(), step.air(), deadline);
    }

    /** 同一次搜索,憋气从 {@code from} 起(诊断问"憋得住的话有没有路"时是 {@link Breath#UNLIMITED})。 */
    public Search breathing(Breath.Air from) {
        return new Search(view, model, start, goal, budget, favoring, memory, arrival, from, deadline);
    }

    /**
     * 同一份快照、起点与目标,换一份成本模型再搜(诊断问"换一种条件有没有路"时):不沿旧路,也不带这一趟的记忆——抬高过的估价
     * 是对着原来的规格算的,放宽之后真实代价更低,它不再是下界;也不限时,"有没有路"只由节点上限定,与机器快慢无关。
     */
    public Search under(CostModel other) {
        return new Search(view, other, start, goal, budget, Favoring.NONE, TripMemory.Frozen.NONE, arrival, air, Deadline.NONE);
    }
}
