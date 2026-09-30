package com.dwinovo.numen.pathing.search;

import java.util.Objects;

import com.dwinovo.numen.pathing.plan.Breath;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Maneuver;

import net.minecraft.core.BlockPos;

/**
 * 一次搜索要的全部输入:读哪份世界、按哪份成本模型、从哪个节点出发、去哪、最多展开几个节点、沿不沿旧路,以及执行时分段
 * 搜的两样:展开到多少个节点就先交出半程、起点是接在哪一步后面。
 *
 * @param start    起点节点:从身体脚下搜时由 {@link Origin} 从身体的真实位置定出,接着一条路线往下搜时是那条路线的终点
 * @param budget   最多展开几个节点;结论不随机器快慢和 tick 速率变
 * @param handOver 展开到这么多个节点还没到目标、又已经有一段离起点足够远的半程路线,就先交出它({@link AStar});
 *                 不小于 {@code budget} 时搜到底才交。也按节点数计,结论同样不随机器快慢变
 * @param arrival  走到起点的那一步:接着一条路线的终点往下搜时,身体到那里时怎么待着、那一步做过哪些改动,都照它算——
 *                 快照里还没有那一步垫下的块;从身体脚下搜为 null
 * @param air      身体在起点时憋气的样子:从脚下搜是身体此刻的({@link Breath#now}),接着一条路线往下搜是走完那一步时的
 */
public record Search(SearchView view, CostModel model, BlockPos start, Goal goal, int budget, Favoring favoring,
                     int handOver, Maneuver arrival, Breath.Air air) {

    public Search {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(favoring, "favoring");
        Objects.requireNonNull(air, "air");
        start = start.immutable();
        if (budget <= 0) {
            throw new IllegalArgumentException("展开预算要是正数:" + budget);
        }
        if (handOver <= 0) {
            throw new IllegalArgumentException("先交半程的节点数要是正数:" + handOver);
        }
        if (arrival != null && !arrival.to().equals(start)) {
            throw new IllegalArgumentException("起点 " + start + " 不是那一步的落点 " + arrival.to());
        }
    }

    /** 搜到底才交、从身体脚下出发的一次搜索:憋气从身体此刻的样子起。 */
    public Search(SearchView view, CostModel model, BlockPos start, Goal goal, int budget, Favoring favoring) {
        this(view, model, start, goal, budget, favoring, budget, null, model.body().breath().now());
    }

    /** 同一次搜索,展开到 {@code nodes} 个节点、又有了够远的半程路线,就先交出它。 */
    public Search handingOverAt(int nodes) {
        return new Search(view, model, start, goal, budget, favoring, nodes, arrival, air);
    }

    /** 同一次搜索,起点是 {@code step} 这一步的落点:接着一条路线往下搜,憋气从走完这一步时的样子起。 */
    public Search after(Route.Leg step) {
        return step == null ? this
                : new Search(view, model, start, goal, budget, favoring, handOver, step.maneuver(), step.air());
    }

    /** 同一次搜索,憋气从 {@code from} 起(诊断问"憋得住的话有没有路"时是 {@link Breath#UNLIMITED})。 */
    public Search breathing(Breath.Air from) {
        return new Search(view, model, start, goal, budget, favoring, handOver, arrival, from);
    }
}
