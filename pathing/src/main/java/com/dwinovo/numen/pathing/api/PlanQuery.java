package com.dwinovo.numen.pathing.api;

import java.util.Objects;

import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.RoutePlanner;
import com.dwinovo.numen.pathing.spec.RouteSpec;

/**
 * 一次只搜不走的规划:去哪、按什么规格、要几条候选、每次搜索最多展开几个节点。
 *
 * @param candidates 要几条(1 到 {@link RoutePlanner#MAX_CANDIDATES})
 */
public record PlanQuery(Goal goal, RouteSpec spec, int candidates, int budget) {

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

    public static PlanQuery of(Goal goal, RouteSpec spec, int candidates) {
        return new PlanQuery(goal, spec, candidates, NavRequest.DEFAULT_BUDGET);
    }
}
