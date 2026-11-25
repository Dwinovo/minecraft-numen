package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.plugin.ClientMods;
import com.dwinovo.numen.api.program.ClientTransport;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.dwinovo.numen.mcp.rpc.RpcPeer;
import com.dwinovo.numen.script.ApiReply;
import com.dwinovo.numen.script.ErrorKind;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 反向请求走座位:请求作为 {@code client/call} 发给她的座位上坐着的客户端,客户端答的就是这次调用的 {@code ApiReply}。
 * 座位上没人坐、主人的客户端没装这个函数所在的联动模组、请求装不下一条消息、或客户端在等答复时断线,都是这次调用的一条失败,
 * 程序按失败往下走。
 *
 * <p>线程:{@link #request}、{@link #cancel} 任何线程;答复经 {@link Seats#world} 的主线程交回。
 */
final class SeatTransport implements ClientTransport {

    /** 一个等着答复的反向请求:发在哪条连接上、答复在哪。 */
    private record Waiting(SeatConnection on, RpcPeer.Outgoing call) {}

    private final Seats seats;
    private final Map<String, Waiting> waiting = new ConcurrentHashMap<>();

    SeatTransport(Seats seats) {
        this.seats = seats;
    }

    @Override
    public void request(Request request, Consumer<String> done) {
        Seat seat = seats.seated(request.companion());
        SeatConnection on = seat == null ? null : seat.connection();
        if (on == null) {
            done.accept(failure(request, "its owner's client has not opened her seat, so nothing there can answer"));
            return;
        }
        String namespace = request.function().substring(0, request.function().indexOf('.'));
        if (!ClientMods.offeredToOwner(seat.owner, namespace)) {
            done.accept(ApiReply.error(ErrorKind.NO_FUNCTION, request.function() + " needs the "
                    + ClientMods.target(namespace) + " mod on your owner's client, and it is not installed there", null,
                    null).toString());
            return;
        }
        Waiting mine = new Waiting(on, on.callClient(request));
        waiting.put(request.callId(), mine);
        mine.call().result().whenComplete((reply, failed) -> {
            if (waiting.remove(request.callId(), mine)) {   // 撤回了的不再交
                String answer = failed == null ? reply.toString() : failure(request, failed);
                seats.world().main().execute(() -> done.accept(answer));
            }
        });
    }

    @Override
    public void cancel(String callId) {
        Waiting gone = waiting.remove(callId);
        if (gone != null) {
            gone.on().cancelCall(gone.call(), "the program no longer waits for the answer");
        }
    }

    private static String failure(Request request, Throwable failed) {
        if (failed instanceof RpcException refused) {
            return failure(request, "it failed there: " + refused.error().message());
        }
        if (failed instanceof IOException unsent) {
            return failure(request, "the message to it failed: " + unsent.getMessage());
        }
        return failure(request, "it failed: " + failed);
    }

    private static String failure(Request request, String why) {
        return ApiReply.error(ErrorKind.FAILED, request.function() + " runs on your owner's client, and " + why, null,
                null).toString();
    }
}
