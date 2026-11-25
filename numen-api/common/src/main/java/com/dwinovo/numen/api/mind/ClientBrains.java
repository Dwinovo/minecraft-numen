package com.dwinovo.numen.api.mind;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.seat.Catalog;
import com.dwinovo.numen.api.seat.SeatClient;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * 这台电脑上跑着的大脑({@link LoopHost}):玩家创建的每只同伴一个,跟着他连上的那台服务器。连上服务器、名册到了,按名册里创建者是他的
 * 同伴各开一个座位、各建一个大脑;名册上没有了的(遣散)或断线,大脑收掉。哪一只的大脑在哪、谁来找它,只问这一处。
 *
 * <p>不碰客户端类:产品里由客户端那一头({@code ClientSeats})带着 MC 的连接、客户端主线程来调,单测与评测带着进程内的管道。
 *
 * <p>线程:{@link #sit}、{@link #leave}、{@link #tick} 在大脑的线程(客户端主线程)上;{@link #of} 任何线程。
 */
public final class ClientBrains {

    private static final Map<UUID, LoopHost> HOSTS = new ConcurrentHashMap<>();

    private final SeatClient seat;
    private final Executor main;
    private final Function<UUID, LoopHost> create;
    private final Set<UUID> sitting = ConcurrentHashMap.newKeySet();

    /**
     * @param seat   这条到服务器的座位连接
     * @param main   大脑的线程
     * @param create 给一只同伴建大脑(家、模型、工具……由产品接线)
     */
    public ClientBrains(SeatClient seat, Executor main, Function<UUID, LoopHost> create) {
        this.seat = seat;
        this.main = main;
        this.create = create;
        seat.onCatalogChanged((companion, version) -> main.execute(() -> refetch(companion)));
    }

    /** 这只同伴的大脑;这台电脑上没有(不是她的创建者、座位还没坐上)是编程错误。 */
    public static LoopHost of(UUID companion) {
        LoopHost host = HOSTS.get(companion);
        if (host == null) {
            throw new IllegalStateException("her brain does not run here: " + companion);
        }
        return host;
    }

    /** 这只同伴的大脑在不在这台电脑上跑。 */
    public static boolean present(UUID companion) {
        return HOSTS.containsKey(companion);
    }

    /** 这台电脑上跑着的所有大脑。 */
    public static Collection<LoopHost> all() {
        return HOSTS.values();
    }

    /**
     * 名册变了:{@code mine} 是创建者是这位玩家的那些同伴。还没坐的坐上(建大脑、开座位、取目录),已经不在名册上的放掉。
     */
    public void sit(Set<UUID> mine) {
        for (UUID gone : Set.copyOf(sitting)) {
            if (!mine.contains(gone)) {
                release(gone);
            }
        }
        for (UUID companion : mine) {
            if (sitting.add(companion)) {
                open(companion);
            }
        }
    }

    private void open(UUID companion) {
        LoopHost host = create.apply(companion);
        HOSTS.put(companion, host);
        seat.open(companion, seat.lastSeq(companion))
                .thenCompose(opened -> seat.catalog(companion).thenApply(Catalog::fromJson)
                        .thenAccept(catalog -> main.execute(() -> host.attach(opened, catalog))))
                .whenComplete((done, failed) -> {
                    if (failed != null) {
                        main.execute(() -> {
                            Constants.LOG.warn("[numen-mind] could not sit at the seat of {}: {}", companion,
                                    failed.toString());
                            release(companion);
                        });
                    }
                });
    }

    private void refetch(UUID companion) {
        LoopHost host = HOSTS.get(companion);
        if (host == null) {
            return;
        }
        seat.catalog(companion).whenComplete((json, failed) -> main.execute(() -> {
            if (failed != null) {
                Constants.LOG.warn("[numen-mind] could not read the new catalog of {}: {}", companion, failed.toString());
            } else {
                host.catalogChanged(Catalog.fromJson(json));
            }
        }));
    }

    private void release(UUID companion) {
        sitting.remove(companion);
        LoopHost host = HOSTS.remove(companion);
        if (host != null) {
            host.dispose();
            host.detach();
        }
    }

    /** 每个客户端刻一次:各只的大脑往前走一步。 */
    public void tick() {
        for (UUID companion : sitting) {
            LoopHost host = HOSTS.get(companion);
            if (host != null) {
                host.tick();
            }
        }
    }

    /** 断线:所有大脑收掉,收到的序号记着,重连时报给服务端。 */
    public void leave() {
        for (UUID companion : Set.copyOf(sitting)) {
            release(companion);
        }
        seat.close();
    }
}
