package com.dwinovo.numen.pathing.benchmark;


import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.search.AStar;
import com.dwinovo.numen.pathing.search.Favoring;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Search;
import com.dwinovo.numen.pathing.search.SearchResult;
import com.dwinovo.numen.pathing.search.SearchView;

import net.minecraft.core.BlockPos;

/**
 * 标准答案求解器:同一套走法生成与成本模型({@link AStar} 的 {@code Moves}/{@code CostModel}/{@code Goal}),权重 1,不分段、
 * 不惰性地限时、不带这一趟的记忆、不沿旧路,在整块区域的快照上一次搜到底。放弃的条件只有一个:展开节点数用完 {@link #NODE_CAP}。
 *
 * <p>权重 1 搜到了目标,价钱就是最优价({@link Answers.Status#OPTIMAL});能走到的都搜完了没有目标,是证明过的无解
 * ({@link Answers.Status#UNSOLVABLE});能走到的都搜完了没有目标、可路伸出了区域(没加载的那边是什么不知道),没有答案
 * ({@link Answers.Status#UNKNOWN});节点上限用完了,没有最优解可记,改按生产权重再搜一遍取一个已知的上界
 * ({@link Answers.Status#BEST_KNOWN},次优比只作参考),再搜不出也是 {@link Answers.Status#UNKNOWN}。
 */
final class Reference {

    /** 权重 1 的搜索最多展开几个节点;用完就算"算不动"。一个节点连同它的边在内存里约一千字节,同时跑几个求解器({@link Answers#solvers})就占几倍。 */
    static final int NODE_CAP = 2_000_000;

    /**
     * 求解的结论。
     *
     * @param expanded      权重 1 那一遍展开的节点数(这道题的难度);用完上限就是上限
     * @param startEstimate 起点的估价
     */
    record Solved(Answers.Status status, double cost, int expanded, double startEstimate) {}

    private Reference() {}

    static Solved solve(SearchView whole, CostModel model, BlockPos start, Goal goal) {
        Search search = new Search(whole, model, start, goal, NODE_CAP, Favoring.NONE);
        SearchResult exact = AStar.run(search, 1.0, () -> false);
        if (exact.arrived()) {
            return new Solved(Answers.Status.OPTIMAL, exact.route().cost(), exact.expanded(), exact.startEstimate());
        }
        if (exact.stop() == SearchResult.Stop.EXHAUSTED) {
            return new Solved(Answers.Status.UNSOLVABLE, Double.NaN, exact.expanded(), exact.startEstimate());
        }
        if (exact.stop() == SearchResult.Stop.UNLOADED) {
            // 走得到的都搜过了,没有目标,路却伸出了区域:再按生产权重搜也搜不出权重 1 漏掉的路,不必再搜
            return new Solved(Answers.Status.UNKNOWN, Double.NaN, exact.expanded(), exact.startEstimate());
        }
        SearchResult known = AStar.run(search, AStar.WEIGHT, () -> false);
        return known.arrived()
                ? new Solved(Answers.Status.BEST_KNOWN, known.route().cost(), exact.expanded(), exact.startEstimate())
                : new Solved(Answers.Status.UNKNOWN, Double.NaN, exact.expanded(), exact.startEstimate());
    }
}
