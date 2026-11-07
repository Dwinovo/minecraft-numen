package com.dwinovo.numen.api.network;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.network.payload.FragmentPayload;
import com.dwinovo.numen.api.network.payload.NumenLocationsPayload;
import com.dwinovo.numen.api.network.payload.LocateNumenPayload;
import com.dwinovo.numen.api.network.payload.ClientUiActionPayload;
import com.dwinovo.numen.api.network.payload.CompanionListPayload;
import com.dwinovo.numen.api.network.payload.ServerHelloPayload;
import com.dwinovo.numen.api.platform.Services;
import com.dwinovo.numen.api.platform.services.INetworkChannel.Execution;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Central registration hub for every {@link
 * net.minecraft.network.protocol.common.custom.CustomPacketPayload} the mod
 * declares, and the only way one is sent. Each loader's mod-init code calls
 * {@link #register} exactly once during startup; the {@link Services#NETWORK}
 * platform implementation handles the loader-specific timing.
 *
 * <h2>Sending</h2>
 * {@link #sendToPlayer} and {@link #sendToServer} hand the payload to {@link Fragments#packets} with its own
 * codec and {@link Wire} before giving anything to the loader: what goes on the wire always fits one packet, so no
 * length can make netty drop the connection. A payload that grows with data and declares {@link Wire.Fragmentable}
 * goes as fragments when it does not fit one packet; the receiving side's {@link #fragmentFromClient} /
 * {@link #fragmentFromServer} put it back together and give it to the handler registered for it, which cannot tell.
 *
 * <h2>Adding a new payload</h2>
 * <ol>
 *   <li>Define a record under {@code com.dwinovo.numen.api.network.payload}
 *       implementing {@code CustomPacketPayload} with a public {@code Type}
 *       and {@code StreamCodec}; text whose length the payload does not control
 *       uses {@link Wire#text()}, a payload whose content grows with data
 *       implements {@link Wire.Oversized} (shrinks to fit one packet) or {@link Wire.Fragmentable} (goes as
 *       fragments).</li>
 *   <li>Add one {@code toServer(...)} or {@code toClient(...)} call here.</li>
 * </ol>
 */
public final class NumenNetwork {

    /**
     * 一种包的编解码器、处理器,以及处理器在哪个线程上跑(下行恒为客户端主线程)。编解码器按"能写在注册表缓冲上的"记:
     * 上行的编解码器写在 {@code ByteBuf} 上,同样接得住。
     */
    private record Route<H>(StreamCodec<? super RegistryFriendlyByteBuf, CustomPacketPayload> codec, H handler,
                            Execution execution) {}

    /** 每种下行包按它的类型登记的路径;表里的编解码器只拿去编登记时那一种包。 */
    private static final Map<CustomPacketPayload.Type<?>, Route<Consumer<CustomPacketPayload>>> TO_CLIENT =
            new HashMap<>();
    /** 每种上行包的路径,同上。 */
    private static final Map<CustomPacketPayload.Type<?>, Route<BiConsumer<CustomPacketPayload, ServerPlayer>>>
            TO_SERVER = new HashMap<>();

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
        if (payload.type() != ServerHelloPayload.TYPE
                && !com.dwinovo.numen.api.brain.Presence.hasClient(player.getUUID())) {
            return;
        }
        if (!Services.network().canSend(player, payload.type())) {
            return;
        }
        for (CustomPacketPayload packet : Fragments.packets(Wire.TO_CLIENT, route(TO_CLIENT, payload).codec(), payload,
                () -> new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess()))) {
            Services.network().sendToPlayer(player, packet);
        }
    }

    /** 发给服务端:同 {@link #sendToPlayer}。 */
    public static void sendToServer(CustomPacketPayload payload) {
        for (CustomPacketPayload packet : Fragments.packets(Wire.TO_SERVER, route(TO_SERVER, payload).codec(), payload,
                NumenNetwork::registryFreeBuffer)) {
            Services.network().sendToServer(packet);
        }
    }

    /** 上行的包不带注册表里的东西,量它们用空的注册表。 */
    private static RegistryFriendlyByteBuf registryFreeBuffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static <R> R route(Map<CustomPacketPayload.Type<?>, R> table, CustomPacketPayload payload) {
        R route = table.get(payload.type());
        if (route == null) {
            throw new IllegalArgumentException(payload.type().id() + " is not registered in this direction");
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
        switch (inbox.accept(fragment)) {
            case Fragments.Pending pending -> {
                return null;
            }
            case Fragments.Rejected rejected -> {
                Constants.LOG.warn("[numen-net] dropped a fragmented message: {}", rejected.why());
                return null;
            }
            case Fragments.Complete whole -> {
                CustomPacketPayload.Type<?> kind = new CustomPacketPayload.Type<>(whole.kind());
                Route<?> route = inbox.direction() == Wire.TO_SERVER ? TO_SERVER.get(kind) : TO_CLIENT.get(kind);
                CustomPacketPayload payload = route == null ? null : Fragments.decode(route.codec(), whole.bytes());
                if (!(payload instanceof Wire.Fragmentable)) {
                    Constants.LOG.warn("[numen-net] dropped a fragmented {}: not a payload that goes as fragments",
                            whole.kind());
                    return null;
                }
                return payload;
            }
        }
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

    /** 登记进表时抹掉包的具体类型:取出来时按 {@link CustomPacketPayload#type()} 对回同一种。 */
    @SuppressWarnings("unchecked")
    private static <C> C erased(Object value) {
        return (C) value;
    }

    private static <T extends CustomPacketPayload> void toClient(CustomPacketPayload.Type<T> type,
                                                                 StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
                                                                 Consumer<T> handler) {
        TO_CLIENT.put(type, new Route<>(erased(codec), erased(handler), Execution.MAIN));
        Services.network().registerServerToClient(type, codec, handler);
    }

    private static <T extends CustomPacketPayload> void toServer(CustomPacketPayload.Type<T> type,
                                                                 StreamCodec<ByteBuf, T> codec,
                                                                 BiConsumer<T, ServerPlayer> handler) {
        toServer(type, codec, handler, Execution.MAIN);
    }

    private static <T extends CustomPacketPayload> void toServer(CustomPacketPayload.Type<T> type,
                                                                 StreamCodec<ByteBuf, T> codec,
                                                                 BiConsumer<T, ServerPlayer> handler,
                                                                 Execution execution) {
        TO_SERVER.put(type, new Route<>(erased(codec), erased(handler), execution));
        Services.network().registerClientToServer(type, codec, handler, execution);
    }

    public static void register() {
        // 两个方向上一条超过单包上限的消息的片(见 Fragments):对端收齐拼回,交给原来的处理器。
        toServer(FragmentPayload.TO_SERVER, FragmentPayload.TO_SERVER_CODEC, NumenNetwork::fragmentFromClient,
                Execution.NETWORK);
        toClient(FragmentPayload.TO_CLIENT, FragmentPayload.TO_CLIENT_CODEC, NumenNetwork::fragmentFromServer);

        // 服务端大脑与主人的客户端之间:握手、主人做的事、一刻的事件、历史分页、会话列表(见 brain 包)。
        toClient(com.dwinovo.numen.api.network.payload.ServerHelloPayload.TYPE,
                com.dwinovo.numen.api.network.payload.ServerHelloPayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.ServerHelloPayload::handle);
        toServer(com.dwinovo.numen.api.network.payload.HelloPayload.TYPE,
                com.dwinovo.numen.api.network.payload.HelloPayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.HelloPayload::handle);
        toServer(com.dwinovo.numen.api.network.payload.ClientCapsPayload.TYPE,
                com.dwinovo.numen.api.network.payload.ClientCapsPayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.ClientCapsPayload::handle);
        toClient(com.dwinovo.numen.api.network.payload.HelloAckPayload.TYPE,
                com.dwinovo.numen.api.network.payload.HelloAckPayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.HelloAckPayload::handle);
        // 主人做的事在网络线程上读懂、投进信箱,不等服务器刻
        toServer(com.dwinovo.numen.api.network.payload.OwnerInputPayload.TYPE,
                com.dwinovo.numen.api.network.payload.OwnerInputPayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.OwnerInputPayload::handle, Execution.NETWORK);
        toClient(com.dwinovo.numen.api.network.payload.FeedPayload.TYPE,
                com.dwinovo.numen.api.network.payload.FeedPayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.FeedPayload::handle);
        toServer(com.dwinovo.numen.api.network.payload.HistoryRequestPayload.TYPE,
                com.dwinovo.numen.api.network.payload.HistoryRequestPayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.HistoryRequestPayload::handle);
        toClient(com.dwinovo.numen.api.network.payload.HistoryPagePayload.TYPE,
                com.dwinovo.numen.api.network.payload.HistoryPagePayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.HistoryPagePayload::handle);
        toServer(com.dwinovo.numen.api.network.payload.LibraryRequestPayload.TYPE,
                com.dwinovo.numen.api.network.payload.LibraryRequestPayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.LibraryRequestPayload::handle);
        toClient(com.dwinovo.numen.api.network.payload.LibraryPayload.TYPE,
                com.dwinovo.numen.api.network.payload.LibraryPayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.LibraryPayload::handle);
        toClient(com.dwinovo.numen.api.network.payload.ExportChunkPayload.TYPE,
                com.dwinovo.numen.api.network.payload.ExportChunkPayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.ExportChunkPayload::handle);
        toClient(com.dwinovo.numen.api.network.payload.SkinHandoverPayload.TYPE,
                com.dwinovo.numen.api.network.payload.SkinHandoverPayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.SkinHandoverPayload::handle);
        toClient(com.dwinovo.numen.api.network.payload.ConversationsPayload.TYPE,
                com.dwinovo.numen.api.network.payload.ConversationsPayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.ConversationsPayload::handle);

        // S→C: a running program calls a function only the owner's client can answer; C→S: the answer.
        toClient(
                com.dwinovo.numen.api.network.payload.ClientCallPayload.TYPE,
                com.dwinovo.numen.api.network.payload.ClientCallPayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.ClientCallPayload::handle);
        toServer(
                com.dwinovo.numen.api.network.payload.ClientCallResultPayload.TYPE,
                com.dwinovo.numen.api.network.payload.ClientCallResultPayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.ClientCallResultPayload::handle);

        // S→C: 同伴在等主人点头的那条征询(或撤回)——答复框与轮廓只照它画(见 ConsentDesk)。
        toClient(
                com.dwinovo.numen.api.network.payload.ConsentRequestPayload.TYPE,
                com.dwinovo.numen.api.network.payload.ConsentRequestPayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.ConsentRequestPayload::handle);

        // C→S: 主人在答复框上的答复(只认主人)。
        toServer(
                com.dwinovo.numen.api.network.payload.ConsentReplyPayload.TYPE,
                com.dwinovo.numen.api.network.payload.ConsentReplyPayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.ConsentReplyPayload::handle);

        // C→S: 大脑开始/结束输出——身体据此在说话期间注视主人(纯姿态信号)。
        toServer(
                com.dwinovo.numen.api.network.payload.SpeakingStatePayload.TYPE,
                com.dwinovo.numen.api.network.payload.SpeakingStatePayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.SpeakingStatePayload::handle);

        // S→C: the owner's companion roster (UUID + name), pushed on login + summon
        // so the client panel knows which fake players are its companions.
        toClient(
                CompanionListPayload.TYPE, CompanionListPayload.STREAM_CODEC,
                CompanionListPayload::handle);

        // S→C: server `/numen` verbs that must act on the caller's own client
        // (open settings GUI / reset conversations).
        toClient(
                ClientUiActionPayload.TYPE, ClientUiActionPayload.STREAM_CODEC,
                ClientUiActionPayload::handle);

        // C→S: roster panel asks where its (possibly far / cross-dimension) pets are.
        toServer(
                LocateNumenPayload.TYPE, LocateNumenPayload.STREAM_CODEC,
                LocateNumenPayload::handle);

        // S→C: locate answers — position/dimension/HP snapshots per pet.
        toClient(
                NumenLocationsPayload.TYPE, NumenLocationsPayload.STREAM_CODEC,
                NumenLocationsPayload::handle);

        // C→S: the Items tab asks for a companion's backpack (not client-synced).
        toServer(
                com.dwinovo.numen.api.network.payload.RequestStatePayload.TYPE,
                com.dwinovo.numen.api.network.payload.RequestStatePayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.RequestStatePayload::handle);

        // S→C: the requested backpack contents.
        toClient(
                com.dwinovo.numen.api.network.payload.NumenStatePayload.TYPE,
                com.dwinovo.numen.api.network.payload.NumenStatePayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.NumenStatePayload::handle);

        // C→S: the panel's "+" button asks to summon a companion by name.
        toServer(
                com.dwinovo.numen.api.network.payload.SummonRequestPayload.TYPE,
                com.dwinovo.numen.api.network.payload.SummonRequestPayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.SummonRequestPayload::handle);

        // C→S: the edit card's dismiss → confirm asks to permanently delete a companion (drops its inventory, armor and accessories first).
        toServer(
                com.dwinovo.numen.api.network.payload.DismissRequestPayload.TYPE,
                com.dwinovo.numen.api.network.payload.DismissRequestPayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.DismissRequestPayload::handle);

        // C→S: the edit card flips an existing companion between survival/creative.
        toServer(
                com.dwinovo.numen.api.network.payload.SetGameModePayload.TYPE,
                com.dwinovo.numen.api.network.payload.SetGameModePayload.STREAM_CODEC,
                com.dwinovo.numen.api.network.payload.SetGameModePayload::handle);
    }
}
