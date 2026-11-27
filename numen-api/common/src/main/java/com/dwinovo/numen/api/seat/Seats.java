package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.program.ClientTransport;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.dwinovo.numen.mcp.rpc.RpcPeer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一个服务端的全部座位:每只同伴至多一个({@link Seat}),每位玩家至多一条连接({@link SeatConnection}),她的身体的代数,
 * 反向请求的传输({@link #transport})。产品里一个服务器一份({@code ServerSeats}),单测与没有服务器的进程里各造各的。
 *
 * <h2>代数</h2>
 * 她的身体每进一次世界({@link #bodyEntered}:召唤、重生、从休眠回来)加一。事件带着发生时的代数,{@code program/run} 带着脑子
 * 以为的代数,对不上就拒——不去猜两个包谁先谁后。代数只在内存里:服务器重启之后身体都重新进世界,座位也都重新坐。
 *
 * <p>线程:任何线程。
 */
public final class Seats {

    private final SeatWorld world;
    /** 对方请求的处理在这里跑,可以阻塞(等程序的结局、等主线程)。 */
    private final Executor handlers;
    private final Map<UUID, Seat> seats = new ConcurrentHashMap<>();
    private final Map<UUID, SeatConnection> connections = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> generations = new ConcurrentHashMap<>();
    /** 订阅了她的对话记录的连接。 */
    private final Map<UUID, java.util.Set<SeatConnection>> watchers = new ConcurrentHashMap<>();
    private final SeatTransport transport = new SeatTransport(this);

    public Seats(SeatWorld world, Executor handlers) {
        this.world = world;
        this.handlers = handlers;
    }

    SeatWorld world() {
        return world;
    }

    Executor handlers() {
        return handlers;
    }

    // ---- 连接 ----

    /**
     * 这位玩家的连接;他的客户端第一次来消息时现建,直到 {@link #left}。
     *
     * @param wire 把一条消息送到他的客户端;只在现建时用
     */
    SeatConnection connectionOf(UUID player, RpcPeer.Outbound wire) {
        return connections.computeIfAbsent(player, id -> new SeatConnection(this, id, wire));
    }

    /** 这位玩家断线了:他的座位上没人,经座位起的程序切断,在等他答复的反向请求失败,订阅也都作废。 */
    public void left(UUID player) {
        SeatConnection connection = connections.remove(player);
        if (connection != null) {
            watchers.values().forEach(watching -> watching.remove(connection));
            connection.close();
        }
    }

    /** 服务器停了:一切收掉。 */
    public void clear() {
        connections.values().forEach(SeatConnection::close);
        connections.clear();
        seats.clear();
        generations.clear();
        watchers.clear();
    }

    // ---- 座位 ----

    /**
     * 这条连接要坐这只同伴的座位:只有她的创建者的连接坐得上;座位没有就开一个。
     *
     * @throws RpcException 她不存在,或不是这位玩家创建的
     */
    Seat seatFor(SeatConnection connection, UUID companion) throws Exception {
        if (!onMain(() -> world.mayOpen(connection.player, companion))) {
            throw SeatErrors.notYours(companion);
        }
        return seats.computeIfAbsent(companion, id -> new Seat(this, id, connection.player));
    }

    /** 这只同伴的座位,此刻有连接坐着的;没有是 null。 */
    /** 她的座位上有人坐没人坐变了,告诉世界(在服务端主线程上)。 */
    void occupancyChanged(UUID companion) {
        world.main().execute(() -> world.occupancyChanged(companion));
    }

    Seat seated(UUID companion) {
        Seat seat = seats.get(companion);
        return seat != null && seat.connection() != null ? seat : null;
    }

    /** 她被遣散了:座位收掉,发件箱不再留。 */
    public void dismissed(UUID companion) {
        Seat seat = seats.remove(companion);
        if (seat != null && seat.connection() != null) {
            seat.connection().release(companion);
            seat.detach(seat.connection());
        }
        generations.remove(companion);
        watchers.remove(companion);
    }

    // ---- 对话记录的订阅 ----

    /** 这条连接订阅她的对话记录:之后落盘的行(含换了新会话)推给它。 */
    void watch(UUID companion, SeatConnection connection) {
        watchers.computeIfAbsent(companion, id -> ConcurrentHashMap.newKeySet()).add(connection);
    }

    /** 她的对话记录里新落盘的行({@code rows} 空是换了新会话):推给订阅的人。 */
    void rowsAppended(UUID companion, UUID conversation, java.util.List<com.google.gson.JsonObject> rows) {
        java.util.Set<SeatConnection> watching = watchers.get(companion);
        if (watching != null) {
            watching.forEach(connection -> connection.rows(companion, conversation, rows));
        }
    }

    // ---- 代数、事件、目录 ----

    /** 她的身体进了世界(主线程):代数加一。 */
    public void bodyEntered(UUID companion) {
        generations.computeIfAbsent(companion, id -> new AtomicLong()).incrementAndGet();
    }

    /** 她的身体此刻的代数;从没进过世界是 0。 */
    public long generation(UUID companion) {
        AtomicLong generation = generations.get(companion);
        return generation == null ? 0 : generation.get();
    }

    /** 服务端发出了一条给她的事件(主线程):她的座位在,它就进发件箱。 */
    public void publish(UUID companion, EventQueue.Entry entry) {
        Seat seat = seats.get(companion);
        if (seat != null) {
            seat.publish(entry);
        }
    }

    /** 别人对她说的话到了(主线程):她的座位在,它就进发件箱,带着谁说的、在哪儿说的。 */
    public void publish(UUID companion, EventQueue.Entry entry, com.dwinovo.numen.api.talk.Spoken spoken) {
        Seat seat = seats.get(companion);
        if (seat != null) {
            seat.publish(entry, spoken);
        }
    }

    /** 注册表变了(插件、{@code /reload}):各座位按需通知客户端再取目录。 */
    public void catalogChanged() {
        handlers.execute(() -> seats.values().forEach(Seat::catalogMayHaveChanged));
    }

    // ---- 反向请求 ----

    /** 程序里的客户端函数向主人客户端发反向请求走这里:经座位上的 {@code client/call}。 */
    public ClientTransport transport() {
        return transport;
    }

    // ---- 主线程 ----

    /** 在服务端主线程上做一件事,等它的结果。处理线程用,不在主线程上调。 */
    <T> T onMain(Callable<T> work) throws Exception {
        CompletableFuture<T> done = new CompletableFuture<>();
        world.main().execute(() -> {
            try {
                done.complete(work.call());
            } catch (Exception | Error failed) {
                done.completeExceptionally(failed);
            }
        });
        try {
            return done.get();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof Exception e) {
                throw e;
            }
            throw failed;
        }
    }
}
