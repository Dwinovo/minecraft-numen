package com.dwinovo.numen.pathing.api;

import java.util.Objects;

import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.RouteSpec;

/**
 * 一次导航:去哪、按什么规格、每次搜索最多展开几个节点,以及(可选)先照哪条候选路线走。能交接的是目标加规格;
 * 候选路线只是第一段,走不下去照样按目标与规格重搜。
 *
 * @param budget 每次搜索最多展开几个节点
 * @param route  先照这条走;没有为 null
 */
public record NavRequest(Goal goal, RouteSpec spec, int budget, Route route) {

    /** 默认每次搜索最多展开的节点数。 */
    public static final int DEFAULT_BUDGET = 40_000;

    public NavRequest {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(spec, "spec");
        if (budget <= 0) {
            throw new IllegalArgumentException("展开预算要是正数:" + budget);
        }
    }

    public static NavRequest to(Goal goal, RouteSpec spec) {
        return new NavRequest(goal, spec, DEFAULT_BUDGET, null);
    }

    /** 同一个请求,先照 {@code route} 走。 */
    public NavRequest following(Route route) {
        return new NavRequest(goal, spec, budget, route);
    }

    /** 同一个请求,每次搜索最多展开 {@code budget} 个节点。 */
    public NavRequest withBudget(int budget) {
        return new NavRequest(goal, spec, budget, route);
    }
}
