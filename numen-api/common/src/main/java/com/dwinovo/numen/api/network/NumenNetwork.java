package com.dwinovo.numen.api.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.network.payload.FragmentPayload;
import com.dwinovo.numen.api.network.payload.NumenLocationsPayload;
import com.dwinovo.numen.api.network.payload.LocateNumenPayload;
import com.dwinovo.numen.api.network.payload.ClientUiActionPayload;
import com.dwinovo.numen.api.network.payload.CompanionListPayload;
import com.dwinovo.numen.api.network.payload.ServerHelloPayload;
import com.dwinovo.numen.api.platform.Services;
import com.dwinovo.numen.api.platform.services.INetworkChannel.Execution;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Central registration hub for every {@link CustomPacketPayload} the mod
 * declares, and the only way one is sent. Each loader's mod-init code calls
 * {@link #register} exactly once during startup; the {@link Services#NETWORK}
 * platform implementation handles the loader-specific timing.
 *
 * <h2>Sending</h2>
 * {@link #sendToPlayer} and {@link #sendToServer} hand the payload to {@link Fragments#packets} with its
 * {@link Wire} before giving anything to the loader: what goes on the wire always fits one packet, so no
 * length can make netty drop the connection. A payload that grows with data and declares {@link Wire.Fragmentable}
 * goes as fragments when it does not fit one packet; the receiving side's {@link #fragmentFromClient} /
 * {@link #fragmentFromServer} put it back together and give it to the handler registered for it, which cannot tell.
 *
 * <h2>Adding a new payload</h2>
 * <ol>
 *   <li>Define a record under {@code com.dwinovo.numen.api.network.payload}
 *       implementing {@link CustomPacketPayload} with a public {@code ID}, {@code write}
 *       and a static {@code read}; text whose length the payload does not control
 *       uses {@link Wire#writeText}/{@link Wire#readText}, a payload whose content grows with data
 *       implements {@link Wire.Oversized} (shrinks to fit one packet) or {@link Wire.Fragmentable} (goes as
 *       fragments).</li>
 *   <li>Add one {@code toServer(...)} or {@code toClient(...)} call here.</li>
 * </ol>
 */
public final class NumenNetwork {

    /** 一种包的解码器、处理器,以及处理器在哪个线程上跑(下行恒为客户端主线程)。 */
    private record Route<H>(Function<FriendlyByteBuf, CustomPacketPayload> decoder, H handler, Execution execution) {}

    /** 每种下行包按它的通道 id 登记的路径;表里的解码器只拿去解登记时那一种包。 */
    private static final Map<ResourceLocation, Route<Consumer<CustomPacketPayload>>> TO_CLIENT = new HashMap<>();
    /** 每种上行包的路径,同上。 */
    private static final Map<ResourceLocation, Route<BiConsumer<CustomPacketPayload, ServerPlayer>>> TO_SERVER =
            new HashMap<>();

    /** 客户端从服务端收到的、拼装中的分片消息。 */
    private static final Fragments.Inbox FROM_SERVER = new Fragments.Inbox(Wire.TO_CLIENT);
    /** 服务端从各位玩家的客户端收到的、拼装中的分片消息,一个连接一份。 */
    private static final Map<UUID, Fragments.Inbox> FROM_CLIENTS = new ConcurrentHashMap<>();
    /** 每位玩家上行分片的带宽预算。 */
    private static final UplinkBudget UPLINK = new UplinkBudget(Wire.MESSAGE_BYTES, Wire.UPLINK_BYTES_PER_SECOND,
            System::nanoTime);

    private NumenNetwork() {}

    /**
     * 发给一位玩家的客户端:量过、装得下一个包的才交给加载器,超过的可分片的包分成片(见 {@link Fragments#packets})。
     * 我们的频道是可选的(原版客户端不装我们也进得来),收不收得到只看一件事:他的客户端报到过、协议对得上
     * ({@link com.dwinovo.numen.api.brain.Presence})。频道同名不说明读得懂——不同版本的客户端登记着同名的频道,格式却不一样,
     * 发过去它解不开就断线。唯一的例外是开场的 {@link ServerHelloPayload}:它的格式永远不变,正是用来对版本的。
     * 连接上没有这个频道(平台的 {@code canSend})只是传输层的事,照样不发。
     */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (!payload.id().equals(ServerHelloPayload.ID)
                && !com.dwinovo.numen.api.brain.Presence.hasClient(player.getUUID())) {
            return;
        }
        if (!Services.network().canSend(player, payload.id())) {
            return;
        }
        route(TO_CLIENT, payload);
        for (CustomPacketPayload packet : Fragments.packets(Wire.TO_CLIENT, payload)) {
            Services.network().sendToPlayer(player, packet);
        }
    }

    /** 发给服务端:同 {@link #sendToPlayer}。 */
    public static void sendToServer(CustomPacketPayload payload) {
        route(TO_SERVER, payload);
        for (CustomPacketPayload packet : Fragments.packets(Wire.TO_SERVER, payload)) {
            Services.network().sendToServer(packet);
        }
    }

    private static <R> R route(Map<ResourceLocation, R> table, CustomPacketPayload payload) {
        R route = table.get(payload.id());
        if (route == null) {
            throw new IllegalArgumentException(payload.id() + " is not registered in this direction");
        }
        return route;
    }

    /**
     * 服务端收到一片:这位玩家的收件箱收齐了,就把原包交给它登记的处理器。本方法在网络线程上跑(见 {@link Execution#NETWORK}):
     * 拼装和解码都在这里做;处理器登记的是主线程的,收齐之后才切回主线程。超出这位玩家上行预算({@link UplinkBudget})的片丢弃,
     * 它所在的整条消息作废。
     */
    public static void fragmentFromClient(FragmentPayload fragment, ServerPlayer from) {
        Fragments.Inbox inbox = FROM_CLIENTS.computeIfAbsent(from.getUUID(), id -> new Fragments.Inbox(Wire.TO_SERVER));
        if (!UPLINK.admit(from.getUUID(), fragment.chunk().length)) {
            Constants.LOG.warn("[numen-net] dropped a fragment of a message from {}: over the uplink budget",
                    from.getUUID());
            inbox.clear();
            return;
        }
        CustomPacketPayload whole = assembled(inbox, fragment);
        if (whole == null) {
            return;
        }
        Route<BiConsumer<CustomPacketPayload, ServerPlayer>> route = route(TO_SERVER, whole);
        if (route.execution() == Execution.MAIN && !from.server.isSameThread()) {
            from.server.execute(() -> route.handler().accept(whole, from));
        } else {
            route.handler().accept(whole, from);
        }
    }

    /** 客户端收到一片:同 {@link #fragmentFromClient}。 */
    public static void fragmentFromServer(FragmentPayload fragment) {
        CustomPacketPayload whole = assembled(FROM_SERVER, fragment);
        if (whole != null) {
            route(TO_CLIENT, whole).handler().accept(whole);
        }
    }

    /**
     * 这一片放进收件箱;这条消息因此收齐了就解出原包,没收齐是 null。不合规矩的片(见 {@link Fragments})、原包不是登记过的
     * 可分片的包,拒收并丢弃,日志里写明——对端不是正当的 Numen,没有谁可答复。
     */
    public static CustomPacketPayload assembled(Fragments.Inbox inbox, FragmentPayload fragment) {
        Fragments.Outcome outcome = inbox.accept(fragment);
        if (outcome instanceof Fragments.Rejected rejected) {
            Constants.LOG.warn("[numen-net] dropped a fragmented message: {}", rejected.why());
            return null;
        }
        if (!(outcome instanceof Fragments.Complete whole)) {
            return null;   // 还没收齐
        }
        Route<?> route = inbox.direction() == Wire.TO_SERVER ? TO_SERVER.get(whole.kind()) : TO_CLIENT.get(whole.kind());
        CustomPacketPayload payload = route == null ? null : Fragments.decode(route.decoder(), whole.bytes());
        if (!(payload instanceof Wire.Fragmentable)) {
            Constants.LOG.warn("[numen-net] dropped a fragmented {}: not a payload that goes as fragments",
                    whole.kind());
            return null;
        }
        return payload;
    }

    /** 这位玩家断线了:他连接上没收完的残片丢掉。 */
    public static void disconnected(UUID player) {
        UPLINK.forget(player);
        Fragments.Inbox inbox = FROM_CLIENTS.remove(player);
        if (inbox != null) {
            inbox.clear();
        }
    }

    /** 这个客户端从服务端断线了:没收完的残片丢掉。 */
    public static void disconnectedFromServer() {
        FROM_SERVER.clear();
    }

    /** 登记进表时抹掉包的具体类型:取出来时按 {@link CustomPacketPayload#id()} 对回同一种。 */
    @SuppressWarnings("unchecked")
    private static <C> C erased(Object value) {
        return (C) value;
    }

    private static <T extends CustomPacketPayload> void toClient(ResourceLocation id, Function<FriendlyByteBuf, T> decoder,
                                                          Consumer<T> handler) {
        TO_CLIENT.put(id, new Route<>(erased(decoder), erased(handler), Execution.MAIN));
        Services.network().registerServerToClient(id, decoder, handler);
    }

    private static <T extends CustomPacketPayload> void toServer(ResourceLocation id, Function<FriendlyByteBuf, T> decoder,
                                                          BiConsumer<T, ServerPlayer> handler) {
        toServer(id, decoder, handler, Execution.MAIN);
    }

    private static <T extends CustomPacketPayload> void toServer(ResourceLocation id, Function<FriendlyByteBuf, T> decoder,
                                                          BiConsumer<T, ServerPlayer> handler, Execution execution) {
        TO_SERVER.put(id, new Route<>(erased(decoder), erased(handler), execution));
        Services.network().registerClientToServer(id, decoder, handler, execution);
    }

    public static void register() {
        // 两个方向上一条超过单包上限的消息的片(见 Fragments):对端收齐拼回,交给原来的处理器。
        toServer(FragmentPayload.TO_SERVER_ID, FragmentPayload.decoderOf(Wire.TO_SERVER),
                NumenNetwork::fragmentFromClient, Execution.NETWORK);
        toClient(FragmentPayload.TO_CLIENT_ID, FragmentPayload.decoderOf(Wire.TO_CLIENT),
                NumenNetwork::fragmentFromServer);

        // 服务端大脑与主人的客户端之间:握手、主人做的事、一刻的事件、历史分页、会话列表(见 brain 包)。
        toClient(com.dwinovo.numen.api.network.payload.ServerHelloPayload.ID,
                com.dwinovo.numen.api.network.payload.ServerHelloPayload::read,
                com.dwinovo.numen.api.network.payload.ServerHelloPayload::handle);
        toServer(com.dwinovo.numen.api.network.payload.HelloPayload.ID,
                com.dwinovo.numen.api.network.payload.HelloPayload::read,
                com.dwinovo.numen.api.network.payload.HelloPayload::handle);
        toServer(com.dwinovo.numen.api.network.payload.ClientCapsPayload.ID,
                com.dwinovo.numen.api.network.payload.ClientCapsPayload::read,
                com.dwinovo.numen.api.network.payload.ClientCapsPayload::handle);
        toClient(com.dwinovo.numen.api.network.payload.HelloAckPayload.ID,
                com.dwinovo.numen.api.network.payload.HelloAckPayload::read,
                com.dwinovo.numen.api.network.payload.HelloAckPayload::handle);
        // 主人做的事在网络线程上读懂、投进信箱,不等服务器刻
        toServer(com.dwinovo.numen.api.network.payload.OwnerInputPayload.ID,
                com.dwinovo.numen.api.network.payload.OwnerInputPayload::read,
                com.dwinovo.numen.api.network.payload.OwnerInputPayload::handle, Execution.NETWORK);
        toClient(com.dwinovo.numen.api.network.payload.FeedPayload.ID,
                com.dwinovo.numen.api.network.payload.FeedPayload::read,
                com.dwinovo.numen.api.network.payload.FeedPayload::handle);
        toServer(com.dwinovo.numen.api.network.payload.HistoryRequestPayload.ID,
                com.dwinovo.numen.api.network.payload.HistoryRequestPayload::read,
                com.dwinovo.numen.api.network.payload.HistoryRequestPayload::handle);
        toClient(com.dwinovo.numen.api.network.payload.HistoryPagePayload.ID,
                com.dwinovo.numen.api.network.payload.HistoryPagePayload::read,
                com.dwinovo.numen.api.network.payload.HistoryPagePayload::handle);
        toServer(com.dwinovo.numen.api.network.payload.LibraryRequestPayload.ID,
                com.dwinovo.numen.api.network.payload.LibraryRequestPayload::read,
                com.dwinovo.numen.api.network.payload.LibraryRequestPayload::handle);
        toClient(com.dwinovo.numen.api.network.payload.LibraryPayload.ID,
                com.dwinovo.numen.api.network.payload.LibraryPayload::read,
                com.dwinovo.numen.api.network.payload.LibraryPayload::handle);
        toClient(com.dwinovo.numen.api.network.payload.ExportChunkPayload.ID,
                com.dwinovo.numen.api.network.payload.ExportChunkPayload::read,
                com.dwinovo.numen.api.network.payload.ExportChunkPayload::handle);
        toClient(com.dwinovo.numen.api.network.payload.SkinHandoverPayload.ID,
                com.dwinovo.numen.api.network.payload.SkinHandoverPayload::read,
                com.dwinovo.numen.api.network.payload.SkinHandoverPayload::handle);
        toClient(com.dwinovo.numen.api.network.payload.ConversationsPayload.ID,
                com.dwinovo.numen.api.network.payload.ConversationsPayload::read,
                com.dwinovo.numen.api.network.payload.ConversationsPayload::handle);

        // S→C: a running program calls a function only the owner's client can answer; C→S: the answer.
        toClient(
                com.dwinovo.numen.api.network.payload.ClientCallPayload.ID,
                com.dwinovo.numen.api.network.payload.ClientCallPayload::read,
                com.dwinovo.numen.api.network.payload.ClientCallPayload::handle);
        toServer(
                com.dwinovo.numen.api.network.payload.ClientCallResultPayload.ID,
                com.dwinovo.numen.api.network.payload.ClientCallResultPayload::read,
                com.dwinovo.numen.api.network.payload.ClientCallResultPayload::handle);

        // S→C: 同伴在等主人点头的那条征询(或撤回)——答复框与轮廓只照它画(见 ConsentDesk)。
        toClient(
                com.dwinovo.numen.api.network.payload.ConsentRequestPayload.ID,
                com.dwinovo.numen.api.network.payload.ConsentRequestPayload::read,
                com.dwinovo.numen.api.network.payload.ConsentRequestPayload::handle);

        // C→S: 主人在答复框上的答复(只认主人)。
        toServer(
                com.dwinovo.numen.api.network.payload.ConsentReplyPayload.ID,
                com.dwinovo.numen.api.network.payload.ConsentReplyPayload::read,
                com.dwinovo.numen.api.network.payload.ConsentReplyPayload::handle);

        // C→S: 大脑开始/结束输出——身体据此在说话期间注视主人(纯姿态信号)。
        toServer(
                com.dwinovo.numen.api.network.payload.SpeakingStatePayload.ID,
                com.dwinovo.numen.api.network.payload.SpeakingStatePayload::read,
                com.dwinovo.numen.api.network.payload.SpeakingStatePayload::handle);

        // S→C: the owner's companion roster (UUID + name), pushed on login + summon
        // so the client panel knows which fake players are its companions.
        toClient(
                CompanionListPayload.ID, CompanionListPayload::read,
                CompanionListPayload::handle);

        // S→C: server `/numen` verbs that must act on the caller's own client
        // (open settings GUI / reset conversations).
        toClient(
                ClientUiActionPayload.ID, ClientUiActionPayload::read,
                ClientUiActionPayload::handle);

        // C→S: roster panel asks where its (possibly far / cross-dimension) pets are.
        toServer(
                LocateNumenPayload.ID, LocateNumenPayload::read,
                LocateNumenPayload::handle);

        // S→C: locate answers — position/dimension/HP snapshots per pet.
        toClient(
                NumenLocationsPayload.ID, NumenLocationsPayload::read,
                NumenLocationsPayload::handle);

        // C→S: the Items tab asks for a companion's backpack (not client-synced).
        toServer(
                com.dwinovo.numen.api.network.payload.RequestStatePayload.ID,
                com.dwinovo.numen.api.network.payload.RequestStatePayload::read,
                com.dwinovo.numen.api.network.payload.RequestStatePayload::handle);

        // S→C: the requested backpack contents.
        toClient(
                com.dwinovo.numen.api.network.payload.NumenStatePayload.ID,
                com.dwinovo.numen.api.network.payload.NumenStatePayload::read,
                com.dwinovo.numen.api.network.payload.NumenStatePayload::handle);

        // C→S: the panel's "+" button asks to summon a companion by name.
        toServer(
                com.dwinovo.numen.api.network.payload.SummonRequestPayload.ID,
                com.dwinovo.numen.api.network.payload.SummonRequestPayload::read,
                com.dwinovo.numen.api.network.payload.SummonRequestPayload::handle);

        // C→S: the edit card's dismiss → confirm asks to permanently delete a companion (drops its inventory, armor and accessories first).
        toServer(
                com.dwinovo.numen.api.network.payload.DismissRequestPayload.ID,
                com.dwinovo.numen.api.network.payload.DismissRequestPayload::read,
                com.dwinovo.numen.api.network.payload.DismissRequestPayload::handle);

        // C→S: the edit card flips an existing companion between survival/creative.
        toServer(
                com.dwinovo.numen.api.network.payload.SetGameModePayload.ID,
                com.dwinovo.numen.api.network.payload.SetGameModePayload::read,
                com.dwinovo.numen.api.network.payload.SetGameModePayload::handle);
    }
}
