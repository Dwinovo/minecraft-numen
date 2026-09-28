package com.dwinovo.numen.core.nav;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import com.dwinovo.numen.pathing.api.PlanResult;
import com.dwinovo.numen.pathing.api.Planning;

import net.minecraft.server.MinecraftServer;

/**
 * 只搜不走的规划在后台跑,出结论的那一刻把结论交给提问的一方——查询不占身体,不进任务槽,这里就是它等结论的地方。
 * 两个加载器每个服务端刻末尾调一次 {@link #serverTick};服务器停下时在飞的一并作废。
 */
public final class RouteQueries {

    private record Pending(Planning planning, Consumer<PlanResult> then) {}

    private static final List<Pending> PENDING = new ArrayList<>();

    static {
        com.dwinovo.numen.platform.ServerLifecycle.onStopped(RouteQueries::dropAll);
    }

    private RouteQueries() {}

    /** 结论出来的那一刻(在服务端线程上)交给 {@code then}。 */
    public static void deliver(Planning planning, Consumer<PlanResult> then) {
        PENDING.add(new Pending(planning, then));
    }

    public static void serverTick(MinecraftServer server) {
        Iterator<Pending> it = PENDING.iterator();
        List<Pending> ready = new ArrayList<>();
        List<PlanResult> results = new ArrayList<>();
        while (it.hasNext()) {
            Pending p = it.next();
            PlanResult result = p.planning().poll();
            if (result != null) {
                it.remove();
                ready.add(p);
                results.add(result);
            }
        }
        for (int i = 0; i < ready.size(); i++) {
            ready.get(i).then().accept(results.get(i));
        }
    }

    private static void dropAll() {
        for (Pending p : PENDING) {
            p.planning().cancel();
        }
        PENDING.clear();
    }
}
