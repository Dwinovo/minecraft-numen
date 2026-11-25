package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.program.ClientTransport;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 没有真客户端的进程里(GameTest、单测、评测)反向请求的传输:一个服务端的座位表经进程内的管道({@link SeatPipe})连着一个客户端,
 * 请求与答复各自照线上的样子过一遍,和网络上走的是同一条路,只是不出进程。同伴的座位在第一次有请求时由这个客户端坐上。
 *
 * <p>座位表的世界({@link SeatWorld})由使用方给:只经座位传客户端函数的({@link #LoopbackTransport()})用不到世界的别的部分。
 * 服务端与客户端的主线程、处理线程都是调用方所在的线程,所以请求一发出,答复当场交回。
 */
public final class LoopbackTransport implements ClientTransport {

    private final UUID player = UUID.randomUUID();
    private final Seats seats;
    private final SeatPipe pipe;
    private final Set<UUID> seated = ConcurrentHashMap.newKeySet();

    /** 只经座位传客户端函数:谁都坐得上座位,世界的别的部分没有。 */
    public LoopbackTransport() {
        this(new ClientCallsOnly());
    }

    /** 座位表的世界由调用方给。 */
    public LoopbackTransport(SeatWorld world) {
        this.seats = new Seats(world, Runnable::run);
        this.pipe = SeatPipe.connect(seats, player, Runnable::run, Runnable::run);
    }

    @Override
    public void request(Request request, Consumer<String> done) {
        if (seated.add(request.companion())) {
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
