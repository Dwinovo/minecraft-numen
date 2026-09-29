package com.dwinovo.numen.pathing.api;

import java.util.Objects;

import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.search.RoutePlanner;
import com.dwinovo.numen.pathing.spec.RouteSpec;

/**
 * 一次只搜不走的规划:去哪、按什么规格、要几条候选、每次搜索最多展开几个节点,以及从哪儿起——身体脚下,或接在一条已经规划好的
 * 路线后面(一串途经点逐段规划时,下一段从上一段的终点起,照它最后一步的落点与改动接着搜)。
 *
 * @param candidates 要几条(1 到 {@link RoutePlanner#MAX_CANDIDATES})
 * @param after      接在这条路线后面规划;从身体脚下起为 null
 */
public record PlanQuery(Goal goal, RouteSpec spec, int candidates, int budget, Route after) {

    /** 一次最多要几条候选。 */
    public static final int MAX_CANDIDATES = RoutePlanner.MAX_CANDIDATES;

    public PlanQuery {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(spec, "spec");
        if (candidates < 1 || candidates > RoutePlanner.MAX_CANDIDATES) {
            throw new IllegalArgumentException("候选条数要在 1 到 " + RoutePlanner.MAX_CANDIDATES + " 之间:" + candidates);
        }
        if (budget <= 0) {
            throw new IllegalArgumentException("展开预算要是正数:" + budget);
        }
    }

    /** 从身体脚下起的一次规划。 */
    public PlanQuery(Goal goal, RouteSpec spec, int candidates, int budget) {
        this(goal, spec, candidates, budget, null);
    }

    public static PlanQuery of(Goal goal, RouteSpec spec, int candidates) {
        return new PlanQuery(goal, spec, candidates, NavRequest.DEFAULT_BUDGET);
    }

    /** 同一次规划,接在 {@code previous} 后面:起点是它的终点,身体到那里时怎么待着、最后一步做过哪些改动都照它算。 */
    public PlanQuery after(Route previous) {
        return new PlanQuery(goal, spec, candidates, budget, Objects.requireNonNull(previous, "previous"));
    }
}
