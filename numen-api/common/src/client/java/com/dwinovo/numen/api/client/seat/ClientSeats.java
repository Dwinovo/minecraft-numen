package com.dwinovo.numen.api.client.seat;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.mind.Bound;
import com.dwinovo.numen.api.mind.Slot;
import com.dwinovo.numen.api.client.agent.NumenRoster;
import com.dwinovo.numen.api.client.skin.ClientSkins;
import com.dwinovo.numen.api.client.skin.SkinChoices;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.client.ClientMachines;
import com.dwinovo.numen.api.mind.ClientServices;
import com.dwinovo.numen.api.entity.SummonSetup;
import com.dwinovo.numen.api.mcp.server.ExternalHands;
import com.dwinovo.numen.api.mind.LoopHands;
import com.dwinovo.numen.api.mind.LoopHost;
import com.dwinovo.numen.api.network.payload.ConsentReplyPayload;
import com.dwinovo.numen.api.network.payload.DismissRequestPayload;
import com.dwinovo.numen.api.network.payload.SummonRequestPayload;
import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.network.Wire;
import com.dwinovo.numen.api.network.payload.SeatPayload;
import com.dwinovo.numen.api.seat.SeatClient;
import com.dwinovo.numen.mcp.rpc.RpcException;
import net.minecraft.client.Minecraft;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 这台客户端到服务器的座位({@link SeatClient})接在 MC 的连接上,和她的大脑要用的一切({@link ClientServices},一个连接一份):
 * 服务端收下报到之后({@link #open})建出来;名册里创建者是这位玩家的每只同伴,在名册到来时建大脑、坐上她的座位;服务端发来的座位消息交给它;
 * 每个客户端刻让大脑往前走一步;断线时放掉。
 *
 * <p>线程:都在客户端主线程上。
 */
public final class ClientSeats {

    /** 每只同伴上一次连接里收到的最后一个序号:重连时 {@code seat/open} 报上它,服务端从下一号补。 */
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    /** 召唤卡上改过的库绑定与选的皮肤,按她的名字等着她到达。 */
    private static final java.util.Map<String, Setup> SETUPS = new ConcurrentHashMap<>();

    /** 召唤卡上留在这台电脑上办的几项:库绑定(她的大脑里)与皮肤(经服务端穿上)。 */
    private record Setup(Map<Slot, String> bindings, SkinChoices.Pick skin) {}
    private static SeatClient client;
    private static ClientServices services;

    private ClientSeats() {}

    /** 服务端收下了报到:这条连接上她们的大脑要用的一切建起来。 */
    public static void open(UUID me) {
        if (services != null) {
            return;
        }
        client = new SeatClient(Minecraft.getInstance(), Executors.newVirtualThreadPerTaskExecutor(),
                message -> NumenNetwork.sendToServer(SeatPayload.frame(Wire.TO_SERVER, message)));
        Minecraft game = Minecraft.getInstance();
        services = new ClientServices(me, ClientMachines.get(), client, game, new ClientServices.Game(game::isSameThread,
                ClientSeats::liveCompanions, new LoopHands.Actions() {
                    @Override
                    public boolean summon(String name) {
                        if (game.getConnection() == null) {
                            return false;
                        }
                        NumenNetwork.sendToServer(new SummonRequestPayload(name, SummonSetup.NONE));
                        return true;
                    }

                    @Override
                    public boolean dismiss(UUID companion) {
                        if (game.getConnection() == null) {
                            return false;
                        }
                        NumenNetwork.sendToServer(new DismissRequestPayload(companion));
                        return true;
                    }
                }, (companion, id, decision, note) ->
                NumenNetwork.sendToServer(new ConsentReplyPayload(companion, id, decision, note))));
    }

    /** 主人名下此刻在世界里的同伴:名册上创建者是这位玩家、没有被留在外面的。 */
    private static List<ExternalHands.Companion> liveCompanions() {
        UUID me = Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.getUUID();
        List<ExternalHands.Companion> out = new ArrayList<>();
        for (NumenRoster.Entry entry : NumenRoster.instance().entries()) {
            if (entry.createdBy(me) && !entry.outside().isOutside()) {
                out.add(new ExternalHands.Companion(entry.uuid(), entry.name()));
            }
        }
        return out;
    }

    /** 这条连接上她们的大脑要用的一切;还没报到好是编程错误。 */
    public static ClientServices services() {
        if (services == null) {
            throw new IllegalStateException("the server has not taken this client yet");
        }
        return services;
    }

    /** 这只同伴的大脑;不在这台电脑上(不是她的创建者)是 {@code null}。 */
    public static LoopHost brain(UUID companion) {
        return services == null ? null : services.brains().get(companion);
    }

    /** 服务端发来座位上的一条消息。 */
    public static void received(SeatPayload payload) {
        if (client == null) {
            Constants.LOG.warn("[numen-seat] a seat message arrived before the server took this client; dropped");
            return;
        }
        try {
            client.receive(payload.message());
        } catch (RpcException unreadable) {
            Constants.LOG.warn("[numen-seat] dropped an unreadable message from the server: {}", unreadable.getMessage());
        }
    }

    /** 名册变了:创建者是 {@code me} 的同伴,大脑与座位还没有的建起来、坐上;已经不在名册上的放掉。 */
    public static void sit(UUID me, NumenRoster roster) {
        if (services == null) {
            return;
        }
        Set<UUID> mine = ConcurrentHashMap.newKeySet();
        for (NumenRoster.Entry entry : roster.entries()) {
            if (entry.createdBy(me)) {
                mine.add(entry.uuid());
            }
        }
        Set<UUID> arriving = ConcurrentHashMap.newKeySet();
        mine.stream().filter(companion -> services.brains().get(companion) == null).forEach(arriving::add);
        mine.forEach(companion -> services.brains().resume(companion, LAST.getOrDefault(companion, 0L)));
        services.brains().sit(mine);
        for (UUID companion : arriving) {
            Setup setup = SETUPS.remove(roster.name(companion));
            if (setup == null) {
                continue;
            }
            LoopHost brain = services.brains().get(companion);
            if (brain != null) {
                setup.bindings().forEach((slot, entry) -> brain.bind(slot, new Bound(me, EntryId.parse(entry))));
            }
            if (setup.skin() != null) {
                ClientSkins.wearOnArrival(companion, setup.skin());
            }
        }
    }

    /** 召唤卡上改过的库绑定与选的皮肤({@code null} 是没选):她到达、大脑建起来时落下。 */
    public static void expect(String name, Map<Slot, String> bindings, SkinChoices.Pick skin) {
        if (!bindings.isEmpty() || skin != null) {
            SETUPS.put(name, new Setup(bindings, skin));
        }
    }

    /** 每个客户端刻让各只的大脑往前走一步。 */
    public static void tick() {
        if (services != null) {
            services.tick();
        }
    }

    /** 断线:大脑收掉、座位放掉,收到的序号记着,重连时报给服务端。 */
    public static void close() {
        if (client != null) {
            services.brains().all().forEach(brain -> LAST.put(brain.companion(), client.lastSeq(brain.companion())));
            services.close();
            client = null;
            services = null;
        }
    }
}
