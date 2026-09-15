package com.dwinovo.numen.core.pathing.plan;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

import com.dwinovo.numen.core.pathing.astar.NavPath;
import com.dwinovo.numen.core.pathing.execute.TerrainBill;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;

/**
 * 一个同伴的路线簿:最近规划出来的候选路线,每条一个短 id(r1、r2……),供 {@code goto route:<id>}
 * 取用。只存数据——路线还能不能走、要不要重算,由取用它的导航判。
 *
 * <p>挂在身体上({@link NumenPlayer#state}):身体没了簿子跟着没,休眠回来是空簿子。id 的数字取自
 * 身体上落盘的编号({@link NumenPlayer#nextIdNumber}),休眠、重启之后接着往上数,模型手里的旧 id 不会
 * 指到一条新路上。上限 {@link NavSettings#routeBookCapacity},超出淘汰最早的;取走一条就划掉——路径
 * 的移动原语走过一次就带着执行状态,不能再走第二遍。
 */
public final class RouteBook {

    /**
     * 一条候选路线。
     *
     * @param id              短 id,rN
     * @param goal            它去哪儿(目标契约,sacred 在内)
     * @param spec            走它该用的规格
     * @param path            规划出来的路径
     * @param bill            预算账
     * @param createdGameTime 记入时的游戏刻
     */
    public record Route(String id, GoalCompiler.Compiled goal, RouteSpec spec, NavPath path,
                        TerrainBill bill, long createdGameTime) {

        /** 路径的起点(A* 展开起点)。 */
        public BlockPos start() {
            return path.getSrc();
        }
    }

    /** 这具身体的路线簿(首次取时建)。 */
    public static RouteBook of(NumenPlayer companion) {
        return companion.state(RouteBook.class,
                () -> new RouteBook(NavSettings.get().routeBookCapacity, companion::nextIdNumber));
    }

    private final int capacity;
    private final Deque<Route> routes = new ArrayDeque<>();
    /** id 的数字从这里取。 */
    private final LongSupplier numbers;

    public RouteBook(int capacity, LongSupplier numbers) {
        this.capacity = Math.max(1, capacity);
        this.numbers = numbers;
    }

    /** 记一条,返回带 id 的记录;满了先淘汰最早的。 */
    public Route add(GoalCompiler.Compiled goal, RouteSpec spec, NavPath path, TerrainBill bill,
                     long gameTime) {
        Route route = new Route("r" + numbers.getAsLong(), goal, spec, path, bill, gameTime);
        while (routes.size() >= capacity) {
            routes.pollFirst();
        }
        routes.addLast(route);
        return route;
    }

    /** 按 id 查;没有(从没记过、已淘汰、已取走)返回 null。 */
    public Route get(String id) {
        for (Route r : routes) {
            if (r.id().equals(id)) {
                return r;
            }
        }
        return null;
    }

    /** 取走一条:返回并划掉;没有返回 null。 */
    public Route take(String id) {
        Route route = get(id);
        if (route != null) {
            routes.remove(route);
        }
        return route;
    }

    public int size() {
        return routes.size();
    }
}
