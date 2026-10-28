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
 * @param advance  一段走到哪算完:到了目标,或到了一个相对目标的估价比起点低 {@code advance} 刻的节点({@link AStar});无穷大是只认目标。
 *                 估价已经是每种目标统一的"还差多少",段就用它量(不含这一趟记忆里抬高的增量),不另造"离终点多远"
 */
public record Search(SearchView view, CostModel model, BlockPos start, Goal goal, int budget, Favoring favoring,
                     double advance, TripMemory.Frozen memory, Maneuver arrival, Breath.Air air, Deadline deadline) {

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
        if (!(advance > 0)) {
            throw new IllegalArgumentException("一段要推进的估价要是正数:" + advance);
        }
        if (arrival != null && !arrival.to().equals(start)) {
            throw new IllegalArgumentException("起点 " + start + " 不是那一步的落点 " + arrival.to());
        }
    }

    /** 从身体脚下出发、不限时的一次搜索:憋气从身体此刻的样子起,没有记忆。 */
    public Search(SearchView view, CostModel model, BlockPos start, Goal goal, int budget, Favoring favoring) {
        this(view, model, start, goal, budget, favoring, Double.POSITIVE_INFINITY, TripMemory.Frozen.NONE, null,
                model.body().breath().now(), Deadline.NONE);
    }

    /** 同一次搜索,走到估价比起点低 {@code ticks} 刻的节点就算这一段完了。 */
    public Search advancing(double ticks) {
        return new Search(view, model, start, goal, budget, favoring, ticks, memory, arrival, air, deadline);
    }

    /** 同一次搜索,最多想到 {@code deadline}。 */
    public Search within(Deadline deadline) {
        return new Search(view, model, start, goal, budget, favoring, advance, memory, arrival, air, deadline);
    }

    /** 同一次搜索,带着这一趟的记忆。 */
    public Search remembering(TripMemory.Frozen memory) {
        return new Search(view, model, start, goal, budget, favoring, advance, memory, arrival, air, deadline);
    }

    /**
     * 同一次搜索,接着 {@code route} 往下搜:起点是它的终点,身体到那里时怎么待着、憋气的样子照它最后一步;世界是快照叠上这条路线
     * 从第 {@code done} 步起还没在世界里做的改动({@link PlannedView})——她走到终点时世界就是这样。最后一步的改动随起点叠
     * (展开起点时叠"走到它的那一步",同展开别的节点),这里叠的是它之前的。{@code done} 是已经做完、改动已在快照里的步数:
     * 只搜不走的规划接在上一段后面为 0,边走边接续为身体此刻正在走的那一步(那一步做了一半也不要紧:叠改动叠两遍结果一样)。
     * 没有路线或路线一步都没有,就是这次搜索本身。
     */
    public Search after(Route route, int done) {
        if (route == null || route.legs().isEmpty()) {
            return this;
        }
        int last = route.legs().size() - 1;
        Route.Leg step = route.legs().get(last);
        return new Search(PlannedView.of(view, route.edits(Math.min(done, last), last)), model, start, goal, budget, favoring,
                advance, memory, step.maneuver(), step.air(), deadline);
    }

    /** 同一次搜索,憋气从 {@code from} 起(诊断问"憋得住的话有没有路"时是 {@link Breath#UNLIMITED})。 */
    public Search breathing(Breath.Air from) {
        return new Search(view, model, start, goal, budget, favoring, advance, memory, arrival, from, deadline);
    }

    /**
     * 同一份快照、起点与目标,换一份成本模型再搜(诊断问"换一种条件有没有路"时):不沿旧路,也不带这一趟的记忆——抬高过的估价
     * 是对着原来的规格算的,放宽之后真实代价更低,它不再是下界;也不限时、只认目标,"有没有路"只由节点上限定,与机器快慢无关。
     */
    public Search under(CostModel other) {
        return new Search(view, other, start, goal, budget, Favoring.NONE, Double.POSITIVE_INFINITY, TripMemory.Frozen.NONE, arrival,
                air, Deadline.NONE);
    }
}
