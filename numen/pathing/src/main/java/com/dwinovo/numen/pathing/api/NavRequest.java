package com.dwinovo.numen.pathing.api;

import java.util.Objects;

import com.dwinovo.numen.pathing.drive.Admission;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.RouteSpec;

/**
 * 一次导航:去哪、按什么规格、这一趟总共最多展开几个节点,(可选)先照哪条路线走,以及是停在目标里还是从它里面路过。
 * 能交接的是目标加规格;先照走的路线只是第一段,走不下去照样按目标与规格重搜。
 *
 * @param budget  这一趟总共最多展开几个节点,用完还没到就收场;{@link #AUTO_BUDGET} 按起点到终点的估价定(远的路预算多)
 * @param route   先照这条走;没有为 null
 * @param through 路过:身体走进目标就算到了,不停稳、不复核视线,宿主接着开下一次导航,身体的冲劲不断
 * @param travel  走还是飞({@link Travel}):默认走
 * @param admission 飞的一趟受理时问过的结论({@link Navigator#admit});没问过为 null,开出去时问一次
 */
public record NavRequest(Goal goal, RouteSpec spec, int budget, Route route, boolean through, Travel travel, Admission admission) {

    /** 一次只搜不走的规划默认最多展开的节点数。 */
    public static final int DEFAULT_BUDGET = 40_000;
    /** 这一趟的总预算按起点到终点的估价定。 */
    public static final int AUTO_BUDGET = 0;

    public NavRequest {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(travel, "travel");
        if (budget < 0) {
            throw new IllegalArgumentException("展开预算不能为负:" + budget);
        }
    }

    public static NavRequest to(Goal goal, RouteSpec spec) {
        return new NavRequest(goal, spec, AUTO_BUDGET, null, false, Travel.WALK, null);
    }

    /** 同一个请求,先照 {@code route} 走。 */
    public NavRequest following(Route route) {
        return new NavRequest(goal, spec, budget, route, through, travel, admission);
    }

    /** 同一个请求,从目标里路过而不停下。 */
    public NavRequest passing() {
        return new NavRequest(goal, spec, budget, route, true, travel, admission);
    }

    /** 同一个请求,只飞({@link Travel#FLY});{@code admitted} 是受理时问过的结论,没问过传 null。 */
    public NavRequest flying(Admission admitted) {
        return new NavRequest(goal, spec, budget, route, through, Travel.FLY, admitted);
    }
}
