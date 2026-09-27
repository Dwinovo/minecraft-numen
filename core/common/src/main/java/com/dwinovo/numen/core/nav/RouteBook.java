package com.dwinovo.numen.core.nav;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;

/**
 * 一个同伴的路线簿:最近规划出来的候选路线,每条一个短 id(r1、r2……),供 {@code move goto --route <id>} 取用。只存数据——
 * 路线是推导出来的东西,能交接的是它的目标加规格;照它走时它只是第一段,走不下去照样按目标与规格重搜。
 *
 * <p>挂在身体上({@link NumenPlayer#state}):身体没了簿子跟着没,休眠回来是空簿子。id 的数字取自身体上落盘的编号
 * ({@link NumenPlayer#nextIdNumber}),休眠、重启之后接着往上数,模型手里的旧 id 不会指到一条新路上。最多存
 * {@link #CAPACITY} 条,超出淘汰最早的;取走一条就划掉。
 */
public final class RouteBook {

    /** 每个同伴的路线簿最多存几条候选。 */
    static final int CAPACITY = 6;

    /**
     * 一条候选路线。
     *
     * @param id              短 id,rN
     * @param goal            它去哪儿
     * @param toward          给人说"朝哪儿"的那一格
     * @param spec            走它该用的规格
     * @param route           规划出来的路线
     * @param createdGameTime 记入时的游戏刻
     */
    public record Entry(String id, Goal goal, BlockPos toward, RouteSpec spec, Route route, long createdGameTime) {}

    /** 这具身体的路线簿(首次取时建)。 */
    public static RouteBook of(NumenPlayer companion) {
        return companion.state(RouteBook.class, () -> new RouteBook(CAPACITY, companion::nextIdNumber));
    }

    private final int capacity;
    private final Deque<Entry> routes = new ArrayDeque<>();
    /** id 的数字从这里取。 */
    private final LongSupplier numbers;

    public RouteBook(int capacity, LongSupplier numbers) {
        this.capacity = Math.max(1, capacity);
        this.numbers = numbers;
    }

    /** 记一条,返回带 id 的记录;满了先淘汰最早的。 */
    public Entry add(Goal goal, BlockPos toward, RouteSpec spec, Route route, long gameTime) {
        Entry entry = new Entry("r" + numbers.getAsLong(), goal, toward.immutable(), spec, route, gameTime);
        while (routes.size() >= capacity) {
            routes.pollFirst();
        }
        routes.addLast(entry);
        return entry;
    }

    /** 按 id 查;没有(从没记过、已淘汰、已取走)返回 null。 */
    public Entry get(String id) {
        for (Entry r : routes) {
            if (r.id().equals(id)) {
                return r;
            }
        }
        return null;
    }

    /** 取走一条:返回并划掉;没有返回 null。 */
    public Entry take(String id) {
        Entry route = get(id);
        if (route != null) {
            routes.remove(route);
        }
        return route;
    }

    public int size() {
        return routes.size();
    }
}
