package com.dwinovo.numen.pathing.search;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.dwinovo.numen.pathing.plan.ActionCosts;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

/**
 * 候选路线:同一个目标出几条不同的路给调用方挑。用惩罚法——搜出一条后,把已有候选经过的节点在按位置代价表里加价再搜,
 * 与已有候选重叠过高的丢弃。不引入随机,同样的输入出同样的候选。
 *
 * <p>每一次搜索都是完整的 {@link AStar}:改动预算在展开时就生效,出来的每条候选都在预算内。候选的代价按调用方给的成本模型
 * 重新定价——加价只为了逼出别的路,走的时候不带着它。一次搜索没到目标就收工:朝目标推进的半截路不是候选。
 */
public final class RoutePlanner {

    /** 一次最多出几条候选。 */
    public static final int MAX_CANDIDATES = 3;
    /** 已有候选经过的节点,再被踩一次加的价:相当于平地走一格的价钱涨到三倍。 */
    static final double PENALTY_PER_CELL = 2 * ActionCosts.WALK_ONE_BLOCK;
    /** 新路的节点落在已有候选上的比例高过这个数,就不算一条新路。 */
    static final double MAX_OVERLAP = 0.7;

    private RoutePlanner() {}

    /**
     * 一次候选查询。
     *
     * @param wanted  要几条(1 到 {@link #MAX_CANDIDATES})
     * @param arrival 走到起点的那一步(接着一条路线往下规划时,见 {@link Search#after});从身体脚下起为 null
     */
    public record Query(SearchView view, CostModel model, BlockPos start, Goal goal, int budget, int wanted,
                        Route.Leg arrival) {

        public Query {
            if (wanted < 1 || wanted > MAX_CANDIDATES) {
                throw new IllegalArgumentException("候选条数要在 1 到 " + MAX_CANDIDATES + " 之间:" + wanted);
            }
            start = start.immutable();
        }

        /** 从身体脚下起的一次查询。 */
        public Query(SearchView view, CostModel model, BlockPos start, Goal goal, int budget, int wanted) {
            this(view, model, start, goal, budget, wanted, null);
        }

        /** 这次查询按 {@code model} 的一次完整搜索。 */
        Search search(CostModel model) {
            return new Search(view, model, start, goal, budget, Favoring.NONE).after(arrival);
        }
    }

    /**
     * 查询的结论。
     *
     * @param candidates 找到的候选,按先后;可能为空
     * @param unreached  收工的那次搜索没到目标时它为什么停;攒够了候选才收工为 null
     * @param partial    一条候选都没找到时,那次搜索朝目标推进的半程路线(离起点够远才有,见 {@link AStar});否则为 null
     * @param breathless 收工的那次搜索因为憋不住气丢下过步子({@link SearchResult#breathless})
     */
    public record Plan(List<Route> candidates, SearchResult.Stop unreached, Route partial, boolean breathless) {

        public Plan {
            candidates = List.copyOf(candidates);
        }
    }

    /** 在当前线程上跑完一次查询。 */
    public static Plan run(Query query, BooleanSupplier cancelled) {
        RouteSpec spec = query.model().spec();
        List<Route> found = new ArrayList<>();
        List<Route> searched = new ArrayList<>();
        LongOpenHashSet covered = new LongOpenHashSet();
        for (int i = 0; i < query.wanted(); i++) {
            CostModel model = query.model().withSpec(
                    spec.edit().positions(spec.positions().plus(penalty(searched))).build());
            SearchResult result = AStar.run(query.search(model), cancelled);
            if (!result.arrived()) {
                // 只有头一次(还没有加价)的半程路线是朝目标的真实推进;逼备选时搜出的半截不是
                Route partial = found.isEmpty() && result.route() != null ? result.route().repriced(query.model()) : null;
                return new Plan(found, result.stop(), partial, result.breathless());
            }
            Route route = result.route();
            if (!overlapsTooMuch(route, covered)) {
                found.add(route.repriced(query.model()));
            }
            searched.add(route);
            for (BlockPos node : route.nodes()) {
                covered.add(node.asLong());
            }
        }
        return new Plan(found, null, null, false);
    }

    /** 已有候选经过的每个节点加价;同一节点被几条路经过就加几次。 */
    private static PositionCosts penalty(List<Route> searched) {
        PositionCosts.Builder b = PositionCosts.builder();
        for (Route route : searched) {
            for (BlockPos node : route.nodes()) {
                b.add(Use.PASS, node.asLong(), PENALTY_PER_CELL);
            }
        }
        return b.build();
    }

    private static boolean overlapsTooMuch(Route route, LongOpenHashSet covered) {
        if (covered.isEmpty()) {
            return false;
        }
        List<BlockPos> nodes = route.nodes();
        int shared = 0;
        for (BlockPos node : nodes) {
            if (covered.contains(node.asLong())) {
                shared++;
            }
        }
        return (double) shared / nodes.size() > MAX_OVERLAP;
    }
}
