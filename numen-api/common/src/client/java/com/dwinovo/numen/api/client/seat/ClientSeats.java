package com.dwinovo.numen.api.client.seat;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.client.agent.NumenRoster;
import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.network.Wire;
import com.dwinovo.numen.api.network.payload.SeatPayload;
import com.dwinovo.numen.api.seat.SeatClient;
import com.dwinovo.numen.api.seat.SeatEvent;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.dwinovo.numen.mcp.rpc.RpcMessage;
import net.minecraft.client.Minecraft;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 这台客户端到服务器的座位({@link SeatClient})接在 MC 的连接上:名册里创建者是这位玩家的每只同伴,在名册到来时坐上她的座位;
 * 服务端发来的座位消息交给它;断线时放掉。
 *
 * <p>座位上的事件由接收方确认:这里还没有谁要处理它们,收到就确认,服务端不必为一个不会来取的人留着。
 *
 * <p>线程:{@link #received}、{@link #sit}、{@link #close} 在客户端主线程上。
 */
public final class ClientSeats {

    /** 每只同伴上一次连接里收到的最后一个序号:重连时 {@code seat/open} 报上它,服务端从下一号补。 */
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    private static final Set<UUID> SITTING = ConcurrentHashMap.newKeySet();
    private static SeatClient client;

    private ClientSeats() {}

    /** 服务端发来座位上的一条消息。 */
    public static void received(SeatPayload payload) {
        try {
            client().receive(payload.message());
        } catch (RpcException unreadable) {
            Constants.LOG.warn("[numen-seat] dropped an unreadable message from the server: {}", unreadable.getMessage());
        }
    }

    /** 名册变了:创建者是 {@code me} 的同伴,座位还没坐的坐上;已经不在名册上的放掉。 */
    public static void sit(UUID me, NumenRoster roster) {
        Set<UUID> mine = ConcurrentHashMap.newKeySet();
        for (NumenRoster.Entry entry : roster.entries()) {
            if (entry.createdBy(me)) {
                mine.add(entry.uuid());
            }
        }
        SITTING.retainAll(mine);
        for (UUID companion : mine) {
            if (SITTING.add(companion)) {
                client().open(companion, LAST.getOrDefault(companion, 0L)).whenComplete((opened, failed) -> {
                    if (failed != null) {
                        SITTING.remove(companion);
                        Constants.LOG.warn("[numen-seat] could not sit at the seat of {}: {}", companion,
                                failed.toString());
                    } else {
                        Constants.LOG.debug("[numen-seat] sat at the seat of {}: {}", companion, opened);
                    }
                });
            }
        }
    }

    /** 断线:座位放掉,收到的序号记着,重连时报给服务端。 */
    public static void close() {
        if (client != null) {
            SITTING.forEach(companion -> LAST.put(companion, client.lastSeq(companion)));
            client.close();
            client = null;
        }
        SITTING.clear();
    }

    private static SeatClient client() {
        if (client == null) {
            client = new SeatClient(Minecraft.getInstance(), Executors.newVirtualThreadPerTaskExecutor(),
                    message -> NumenNetwork.sendToServer(SeatPayload.frame(Wire.TO_SERVER, message)));
            client.onEvent(ClientSeats::acknowledge);
        }
        return client;
    }

    private static void acknowledge(SeatEvent event) {
        client.ack(event.companion(), event.seq()).whenComplete((done, failed) -> {
            if (failed != null) {
                Constants.LOG.debug("[numen-seat] could not acknowledge event {} of {}: {}", event.seq(),
                        event.companion(), failed.toString());
            }
        });
    }
}
