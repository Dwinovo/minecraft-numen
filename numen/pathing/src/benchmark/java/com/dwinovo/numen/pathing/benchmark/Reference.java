package com.dwinovo.numen.pathing.benchmark;

import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.search.AStar;
import com.dwinovo.numen.pathing.search.Favoring;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Search;
import com.dwinovo.numen.pathing.search.SearchResult;
import com.dwinovo.numen.pathing.search.SearchView;

import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;

/**
 * 标准答案求解器:同一套走法生成与成本模型({@link AStar} 的 {@code Moves}/{@code CostModel}/{@code Goal}),权重 1,不分段、
 * 不惰性地限时、不带这一趟的记忆、不沿旧路,在整块区域的快照上一次搜到底。放弃的条件只有一个:展开节点数用完调用方给的上限。
 *
 * <p>结论分四种,没有"不知道"这一类含糊的:
 * <ul>
 *   <li>{@link Answers.Status#OPTIMAL}:权重 1 搜到了目标,价钱是区域内这套成本模型下的最优价;</li>
 *   <li>{@link Answers.Status#UNSOLVABLE}:能走到的都搜完了没有目标,没碰到区域边——区域内证明过的无解;</li>
 *   <li>{@link Answers.Status#BEYOND_REGION}:能走到的都搜完了没有目标,可连通块碰到了区域边(区域之外没加载,那边是什么不知道):
 *       这份数据里没有定论,加算力也没用;</li>
 *   <li>{@link Answers.Status#TOO_HARD}:节点上限用完了,算不动。{@link #upperBound} 再按生产权重搜一遍,搜到了路是
 *       {@link Answers.Status#BEST_KNOWN}(价钱只是上界,次优比只作参考),也搜不出来仍是 {@code TOO_HARD}。</li>
 * </ul>
 */
final class Reference {

    /** 第一档节点上限:一个节点连同它的边活在堆里约 {@link Answers#NODE_BYTES} 字节,同时跑几个求解器({@link Answers#solvers})就占几倍。 */
    static final int NODE_CAP = 2_000_000;
    /** 第二档:第一档撞上限的题用它再解一遍。 */
    static final int LARGE_NODE_CAP = 8_000_000;

    /**
     * 求解的结论。
     *
     * @param expanded      权重 1 那一遍展开的节点数(这道题的难度);用完上限就是上限
     * @param startEstimate 起点的估价
     */
    record Solved(Answers.Status status, double cost, int expanded, double startEstimate) {

        /** 有定论:最优价,或区域内证明的无解——题只收这两种。 */
        boolean conclusive() {
            return status == Answers.Status.OPTIMAL || status == Answers.Status.UNSOLVABLE;
        }
    }

    private Reference() {}

    /**
     * 权重 1、最多展开 {@code cap} 个节点的一遍搜索:{@code OPTIMAL}、{@code UNSOLVABLE}、{@code BEYOND_REGION} 或 {@code TOO_HARD}。
     * {@code cancelled} 为真时搜索尽快收手(结论没有意义,调用方丢弃)。
     */
    static Solved solve(SearchView whole, CostModel model, BlockPos start, Goal goal, int cap, BooleanSupplier cancelled) {
        SearchResult exact = AStar.run(new Search(whole, model, start, goal, cap, Favoring.NONE), 1.0, cancelled::getAsBoolean);
        if (exact.arrived()) {
            return new Solved(Answers.Status.OPTIMAL, exact.route().cost(), exact.expanded(), exact.startEstimate());
        }
        Answers.Status status = switch (exact.stop()) {
            case EXHAUSTED -> Answers.Status.UNSOLVABLE;
            case UNLOADED -> Answers.Status.BEYOND_REGION;
            default -> Answers.Status.TOO_HARD;
        };
        return new Solved(status, Double.NaN, exact.expanded(), exact.startEstimate());
    }

    /** {@code TOO_HARD} 的题再按生产权重搜一遍(同样最多 {@code cap} 个节点):搜到了路是 {@code BEST_KNOWN},否则原样。 */
    static Solved upperBound(SearchView whole, CostModel model, BlockPos start, Goal goal, int cap, Solved tooHard, BooleanSupplier cancelled) {
        SearchResult known = AStar.run(new Search(whole, model, start, goal, cap, Favoring.NONE), AStar.WEIGHT, cancelled::getAsBoolean);
        return known.arrived() ? new Solved(Answers.Status.BEST_KNOWN, known.route().cost(), tooHard.expanded(), tooHard.startEstimate()) : tooHard;
    }
}
