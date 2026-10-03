package com.dwinovo.numen.core.nav;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.server.MinecraftServer;

/**
 * 只搜不走的规划在后台跑,出结论的那一刻把结论交给提问的一方——查询不占身体,不进任务槽,这里就是它等结论的地方。两个加载器每个
 * 服务端刻末尾调一次 {@link #serverTick};服务器停下时在飞的一并作废。
 */
public final class RouteQueries {

    /**
     * @param poll   每刻问一次,有结论交出,没有为 null
     * @param cancel 不要了:在飞的搜索作废
     */
    private record Pending<T>(Supplier<T> poll, Runnable cancel, Consumer<T> then) {

        /** 有结论了就交出去,交了返回 true。 */
        boolean deliver() {
            T found = poll.get();
            if (found == null) {
                return false;
            }
            then.accept(found);
            return true;
        }
    }

    private static final List<Pending<?>> PENDING = new ArrayList<>();

    static {
        com.dwinovo.numen.platform.ServerLifecycle.onStopped(RouteQueries::dropAll);
    }

    private RouteQueries() {}

    /** 结论出来的那一刻(在服务端线程上)交给 {@code then}。 */
    public static <T> void deliver(Supplier<T> poll, Runnable cancel, Consumer<T> then) {
        PENDING.add(new Pending<>(poll, cancel, then));
    }

    public static void serverTick(MinecraftServer server) {
        List<Pending<?>> now = new ArrayList<>(PENDING);
        Iterator<Pending<?>> it = now.iterator();
        while (it.hasNext()) {
            Pending<?> p = it.next();
            if (p.deliver()) {
                PENDING.remove(p);
            }
        }
    }

    private static void dropAll() {
        for (Pending<?> p : PENDING) {
            p.cancel().run();
        }
        PENDING.clear();
    }
}
