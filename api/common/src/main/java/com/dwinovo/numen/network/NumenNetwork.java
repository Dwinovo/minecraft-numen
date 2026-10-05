package com.dwinovo.numen.network;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.network.payload.FragmentPayload;
import com.dwinovo.numen.network.payload.NumenDeathPayload;
import com.dwinovo.numen.network.payload.NumenLocationsPayload;
import com.dwinovo.numen.network.payload.LocateNumenPayload;
import com.dwinovo.numen.network.payload.ClientUiActionPayload;
import com.dwinovo.numen.network.payload.CompanionListPayload;
import com.dwinovo.numen.platform.Services;
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
 *   <li>Define a record under {@code com.dwinovo.numen.network.payload}
 *       implementing {@code CustomPacketPayload} with a public {@code Type}
 *       and {@code StreamCodec}; text whose length the payload does not control
 *       uses {@link Wire#text()}, a payload whose content grows with data
 *       implements {@link Wire.Oversized} (shrinks to fit one packet) or {@link Wire.Fragmentable} (goes as
 *       fragments).</li>
 *   <li>Add one {@code toServer(...)} or {@code toClient(...)} call here.</li>
 * </ol>
 */
public final class NumenNetwork {

    /** 一种包的编解码器与处理器。编解码器按"能写在注册表缓冲上的"记:上行的编解码器写在 {@code ByteBuf} 上,同样接得住。 */
    private record Route<H>(StreamCodec<? super RegistryFriendlyByteBuf, CustomPacketPayload> codec, H handler) {}

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

    private NumenNetwork() {}

    /** 发给一位玩家的客户端:量过、装得下一个包的才交给加载器,超过的可分片的包分成片(见 {@link Fragments#packets})。 */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        for (CustomPacketPayload packet : Fragments.packets(Wire.TO_CLIENT, route(TO_CLIENT, payload).codec(), payload,
                () -> new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess()))) {
            Services.NETWORK.sendToPlayer(player, packet);
        }
    }

    /** 发给服务端:同 {@link #sendToPlayer}。 */
    public static void sendToServer(CustomPacketPayload payload) {
        for (CustomPacketPayload packet : Fragments.packets(Wire.TO_SERVER, route(TO_SERVER, payload).codec(), payload,
                NumenNetwork::registryFreeBuffer)) {
            Services.NETWORK.sendToServer(packet);
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

    /** 服务端收到一片:这位玩家的收件箱收齐了,就把原包交给它登记的处理器。 */
    public static void fragmentFromClient(FragmentPayload fragment, ServerPlayer from) {
        CustomPacketPayload whole = assembled(FROM_CLIENTS.computeIfAbsent(from.getUUID(),
                id -> new Fragments.Inbox(Wire.TO_SERVER)), fragment);
        if (whole != null) {
            route(TO_SERVER, whole).handler().accept(whole, from);
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
        TO_CLIENT.put(type, new Route<>(erased(codec), erased(handler)));
        Services.NETWORK.registerServerToClient(type, codec, handler);
    }

    private static <T extends CustomPacketPayload> void toServer(CustomPacketPayload.Type<T> type,
                                                                 StreamCodec<ByteBuf, T> codec,
                                                                 BiConsumer<T, ServerPlayer> handler) {
        TO_SERVER.put(type, new Route<>(erased(codec), erased(handler)));
        Services.NETWORK.registerClientToServer(type, codec, handler);
    }

    public static void register() {
        // 两个方向上一条超过单包上限的消息的片(见 Fragments):对端收齐拼回,交给原来的处理器。
        toServer(FragmentPayload.TO_SERVER, FragmentPayload.TO_SERVER_CODEC, NumenNetwork::fragmentFromClient);
        toClient(FragmentPayload.TO_CLIENT, FragmentPayload.TO_CLIENT_CODEC, NumenNetwork::fragmentFromServer);

        // C→S: run this whole program on my companion; S→C: its one receipt (or the module texts still missing).
        toServer(
                com.dwinovo.numen.network.payload.RunProgramPayload.TYPE,
                com.dwinovo.numen.network.payload.RunProgramPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.RunProgramPayload::handle);
        toClient(
                com.dwinovo.numen.network.payload.ProgramResultPayload.TYPE,
                com.dwinovo.numen.network.payload.ProgramResultPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.ProgramResultPayload::handle);

        // C→S: stop the program I sent (the owner spoke, the stop button, an external brain took over).
        toServer(
                com.dwinovo.numen.network.payload.StopProgramPayload.TYPE,
                com.dwinovo.numen.network.payload.StopProgramPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.StopProgramPayload::handle);

        // S→C: a running program calls a function only the owner's client can answer; C→S: the answer.
        toClient(
                com.dwinovo.numen.network.payload.ClientCallPayload.TYPE,
                com.dwinovo.numen.network.payload.ClientCallPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.ClientCallPayload::handle);
        toServer(
                com.dwinovo.numen.network.payload.ClientCallResultPayload.TYPE,
                com.dwinovo.numen.network.payload.ClientCallResultPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.ClientCallResultPayload::handle);

        // S→C: 她此刻在做什么 —— 「她在做什么」的唯一真源。槽一变就推，
        // 派发/重放/顶替/干完走同一个出口（见 CurrentTaskPayload）。
        toClient(
                com.dwinovo.numen.network.payload.CurrentTaskPayload.TYPE,
                com.dwinovo.numen.network.payload.CurrentTaskPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.CurrentTaskPayload::handle);

        // S→C: 同伴在等主人点头的那条征询(或撤回)——答复框与轮廓只照它画(见 ConsentDesk)。
        toClient(
                com.dwinovo.numen.network.payload.ConsentRequestPayload.TYPE,
                com.dwinovo.numen.network.payload.ConsentRequestPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.ConsentRequestPayload::handle);

        // C→S: 主人在答复框上的答复(只认主人)。
        toServer(
                com.dwinovo.numen.network.payload.ConsentReplyPayload.TYPE,
                com.dwinovo.numen.network.payload.ConsentReplyPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.ConsentReplyPayload::handle);

        // C→S: owner pressed Stop — cancel the companion's queued + running tasks.
        toServer(
                com.dwinovo.numen.network.payload.CancelTasksPayload.TYPE,
                com.dwinovo.numen.network.payload.CancelTasksPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.CancelTasksPayload::handle);

        // C→S: 大脑开始/结束输出——身体据此在说话期间注视主人(纯姿态信号)。
        toServer(
                com.dwinovo.numen.network.payload.SpeakingStatePayload.TYPE,
                com.dwinovo.numen.network.payload.SpeakingStatePayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.SpeakingStatePayload::handle);

        // S→C: an Numen body died; suspend the owner's agent loop (records the cut-off turn
        // with the death cause). Recoverable — see NumenRespawnPayload.
        toClient(
                NumenDeathPayload.TYPE, NumenDeathPayload.STREAM_CODEC,
                NumenDeathPayload::handle);

        // S→C: the dead companion has respawned at its owner; resume the suspended loop.
        toClient(
                com.dwinovo.numen.network.payload.NumenRespawnPayload.TYPE,
                com.dwinovo.numen.network.payload.NumenRespawnPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.NumenRespawnPayload::handle);

        // S→C: a generic async world event (dimension change, hazard, …) for a companion's brain.
        toClient(
                com.dwinovo.numen.network.payload.NumenEventPayload.TYPE,
                com.dwinovo.numen.network.payload.NumenEventPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.NumenEventPayload::handle);

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

        // S→C: a companion's live pathing state for the debug overlay (lines/boxes).
        toClient(
                com.dwinovo.numen.network.payload.PathDebugPayload.TYPE,
                com.dwinovo.numen.network.payload.PathDebugPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.PathDebugPayload::handle);

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
                com.dwinovo.numen.network.payload.RequestStatePayload.TYPE,
                com.dwinovo.numen.network.payload.RequestStatePayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.RequestStatePayload::handle);

        // S→C: the requested backpack contents.
        toClient(
                com.dwinovo.numen.network.payload.NumenStatePayload.TYPE,
                com.dwinovo.numen.network.payload.NumenStatePayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.NumenStatePayload::handle);

        // C→S: the panel's "+" button asks to summon a companion by name.
        toServer(
                com.dwinovo.numen.network.payload.SummonRequestPayload.TYPE,
                com.dwinovo.numen.network.payload.SummonRequestPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.SummonRequestPayload::handle);

        // C→S: the edit card's dismiss → confirm asks to permanently delete a companion (drops its inventory, armor and accessories first).
        toServer(
                com.dwinovo.numen.network.payload.DismissRequestPayload.TYPE,
                com.dwinovo.numen.network.payload.DismissRequestPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.DismissRequestPayload::handle);

        // C→S: the edit card flips an existing companion between survival/creative.
        toServer(
                com.dwinovo.numen.network.payload.SetGameModePayload.TYPE,
                com.dwinovo.numen.network.payload.SetGameModePayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.SetGameModePayload::handle);

        // C→S: the edit card reskins an existing companion (registry + body recycle).
        toServer(
                com.dwinovo.numen.network.payload.ChangeSkinPayload.TYPE,
                com.dwinovo.numen.network.payload.ChangeSkinPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.ChangeSkinPayload::handle);
    }
}
