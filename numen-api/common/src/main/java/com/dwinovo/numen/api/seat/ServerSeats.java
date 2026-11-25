package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.entity.CompanionEvents;
import com.dwinovo.numen.api.event.NumenEvents;
import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.network.Wire;
import com.dwinovo.numen.api.network.payload.SeatPayload;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.dwinovo.numen.api.plugin.Registrations;
import com.dwinovo.numen.api.program.ClientTransport;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.dwinovo.numen.mcp.rpc.RpcMessage;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * 这个服务器上的座位({@link Seats})接到服务端的各处:网络来的包、玩家断线、身体进世界、发给她的事件、注册表的变化、服务器的起停。
 * 座位本身不认识这些,接线只在这里。
 */
public final class ServerSeats {

    private static final ServerSeatWorld WORLD = new ServerSeatWorld();
    /** 客户端的请求在虚拟线程上处理:停在等程序结局、等主线程上不占平台线程。 */
    private static final Seats SEATS = new Seats(WORLD, Executors.newVirtualThreadPerTaskExecutor());

    private ServerSeats() {}

    /** 模组初始化时调一次。 */
    public static void register() {
        ServerLifecycle.onStarted(server -> WORLD.start(server, SEATS.transport()));
        ServerLifecycle.onStopped(() -> {
            SEATS.clear();
            WORLD.stop();
        });
        ServerLifecycle.onReloaded(server -> SEATS.catalogChanged());
        Registrations.onChanged(SEATS::catalogChanged);
        CompanionEvents.subscribe(CompanionEvent.SPAWN, body -> SEATS.bodyEntered(body.getUUID()));
        NumenEvents.onDelivered(SEATS::publish);
    }

    /** 程序里的客户端函数向主人客户端发反向请求走这里。 */
    public static ClientTransport transport() {
        return SEATS.transport();
    }

    /**
     * 一位玩家的客户端发来座位上的一条消息。在网络线程上调(见 {@code NumenNetwork} 里这个包的登记):解码在这里,请求的处理交给
     * 座位的处理线程,不占服务端主线程。读不成的消息丢弃——没有编号可答复。
     */
    public static void received(SeatPayload payload, ServerPlayer player) {
        RpcMessage message;
        try {
            message = payload.message();
        } catch (RpcException unreadable) {
            Constants.LOG.warn("[numen-seat] dropped an unreadable message from {}: {}", player.getUUID(),
                    unreadable.getMessage());
            return;
        }
        SEATS.connectionOf(player.getUUID(),
                reply -> NumenNetwork.sendToPlayer(player, SeatPayload.frame(Wire.TO_CLIENT, reply)))
                .receive(message);
    }

    /** 这位玩家断线了。 */
    public static void left(UUID player) {
        SEATS.left(player);
    }

    /** 这只同伴被遣散了。 */
    public static void dismissed(UUID companion) {
        SEATS.dismissed(companion);
    }
}
