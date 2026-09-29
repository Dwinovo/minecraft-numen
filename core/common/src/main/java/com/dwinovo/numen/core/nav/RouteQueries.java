package com.dwinovo.numen.core.nav;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.server.MinecraftServer;

/**
 * 只搜不走的规划({@link Survey})在后台跑,出结论的那一刻把结论交给提问的一方——查询不占身体,不进任务槽,这里就是它等结论
 * 的地方。两个加载器每个服务端刻末尾调一次 {@link #serverTick};服务器停下时在飞的一并作废。
 */
public final class RouteQueries {

    private record Pending(Survey survey, Consumer<List<Survey.Found>> then) {}

    private static final List<Pending> PENDING = new ArrayList<>();

    static {
        com.dwinovo.numen.platform.ServerLifecycle.onStopped(RouteQueries::dropAll);
    }

    private RouteQueries() {}

    /** 结论出来的那一刻(在服务端线程上)交给 {@code then}。 */
    public static void deliver(Survey survey, Consumer<List<Survey.Found>> then) {
        PENDING.add(new Pending(survey, then));
    }

    public static void serverTick(MinecraftServer server) {
        Iterator<Pending> it = PENDING.iterator();
        List<Pending> ready = new ArrayList<>();
        List<List<Survey.Found>> results = new ArrayList<>();
        while (it.hasNext()) {
            Pending p = it.next();
            List<Survey.Found> found = p.survey().poll();
            if (found != null) {
                it.remove();
                ready.add(p);
                results.add(found);
            }
        }
        for (int i = 0; i < ready.size(); i++) {
            ready.get(i).then().accept(results.get(i));
        }
    }

    private static void dropAll() {
        for (Pending p : PENDING) {
            p.survey().cancel();
        }
        PENDING.clear();
    }
}
