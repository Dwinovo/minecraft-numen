package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.network.Fragments;
import com.dwinovo.numen.api.network.Wire;
import com.dwinovo.numen.api.network.payload.SeatPayload;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.dwinovo.numen.mcp.rpc.RpcMessage;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 进程内的"网线":一端是服务端的座位表({@link Seats}),一端是一个客户端({@link SeatClient})。每条消息照线上的样子过一遍
 * (装成 {@link SeatPayload}、超过单包上限的切片、对端拼回、解码)再交给对端,所以收发两头走的就是产品里的那些部件,只是不出进程。
 * 没有真网络的地方(GameTest、单测、评测)用它。
 *
 * <p>线程:消息在发送它的线程上交给对端。
 */
public final class SeatPipe {

    private final Seats seats;
    private final UUID player;
    private final SeatClient client;
    private volatile boolean silent;

    private SeatPipe(Seats seats, UUID player, Executor clientMain, Executor clientHandlers) {
        this.seats = seats;
        this.player = player;
        SeatConnection[] server = new SeatConnection[1];
        this.client = new SeatClient(clientMain, clientHandlers, message -> {
            if (!silent) {
                server[0].receive(crossed(Wire.TO_SERVER, message));
            }
        });
        server[0] = seats.connectionOf(player, message -> client.receive(crossed(Wire.TO_CLIENT, message)));
    }

    /**
     * 把一个新的客户端接到 {@code seats} 上,作为玩家 {@code player} 的连接。
     *
     * @param clientMain     客户端函数在哪里执行
     * @param clientHandlers 客户端处理服务端请求的执行体
     */
    public static SeatPipe connect(Seats seats, UUID player, Executor clientMain, Executor clientHandlers) {
        return new SeatPipe(seats, player, clientMain, clientHandlers);
    }

    /** 管子另一端的客户端。 */
    public SeatClient client() {
        return client;
    }

    /** 从此客户端的消息到不了服务端(扮一个卡住的客户端):服务端的请求照常到达、客户端照常执行,答复发不回去。 */
    public void silence() {
        silent = true;
    }

    /** 断线:服务端当这位玩家离线,客户端在等的请求失败。 */
    public void disconnect() {
        seats.left(player);
        client.close();
    }

    private static RpcMessage crossed(Wire direction, RpcMessage message) throws IOException {
        SeatPayload sent = SeatPayload.frame(direction, message);
        try {
            return Fragments.crossed(direction, SeatPayload.codecOf(direction), sent).message();
        } catch (RpcException unreadable) {
            throw new IOException("the message did not survive the wire: " + unreadable.getMessage(), unreadable);
        }
    }
}
