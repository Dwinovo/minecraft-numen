package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.program.ClientTransport;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 单测用的主人客户端:真的座位表({@link Seats})经进程内的管道({@link SeatPipe})连着一个真的客户端({@link SeatClient}),请求与答复各自照线上的样子
 * 过一遍。同伴的座位在第一次有请求时由这个客户端坐上(它是她的创建者)。服务端与客户端的主线程、处理线程都是调用方所在的线程,
 * 所以请求一发出,答复当场交回。
 */
public final class SeatedClient implements ClientTransport {

    private final UUID player = UUID.randomUUID();
    private final StubSeatWorld world = new StubSeatWorld();
    private final Seats seats = new Seats(world, Runnable::run);
    private final SeatPipe pipe = SeatPipe.connect(seats, player, Runnable::run, Runnable::run);
    private final Set<UUID> seated = ConcurrentHashMap.newKeySet();

    @Override
    public void request(Request request, Consumer<String> done) {
        if (seated.add(request.companion())) {
            world.createdBy(request.companion(), player);
            pipe.client().open(request.companion(), 0).join();
        }
        seats.transport().request(request, done);
    }

    @Override
    public void cancel(String callId) {
        seats.transport().cancel(callId);
    }

    /** 从此这个"客户端"的答复都到不了服务端:程序里的客户端函数只能等到时限。 */
    public void silence() {
        pipe.silence();
    }
}
