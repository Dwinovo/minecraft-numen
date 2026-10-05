package com.dwinovo.numen.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import com.dwinovo.numen.Constants;
import com.dwinovo.numen.network.payload.FragmentPayload;
import com.dwinovo.numen.network.payload.NumenDeathPayload;
import com.dwinovo.numen.network.payload.NumenLocationsPayload;
import com.dwinovo.numen.network.payload.LocateNumenPayload;
import com.dwinovo.numen.network.payload.ClientUiActionPayload;
import com.dwinovo.numen.network.payload.CompanionListPayload;
import com.dwinovo.numen.platform.Services;
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
 * {@link #sendToPlayer} and {@link #sendToServer} hand the payload to {@link Fragments#packets} with the
 * {@link Wire} direction before giving anything to the loader: what goes on the wire always fits one packet, so no
 * length can make netty drop the connection. A payload that grows with data and declares {@link Wire.Fragmentable}
 * goes as fragments when it does not fit one packet; the receiving side's {@link #fragmentFromClient} /
 * {@link #fragmentFromServer} put it back together and give it to the handler registered for it, which cannot tell.
 *
 * <h2>Adding a new payload</h2>
 * <ol>
 *   <li>Define a record under {@code com.dwinovo.numen.network.payload}
 *       implementing {@link CustomPacketPayload} with a public {@code ID}, a {@code write(FriendlyByteBuf)}
 *       and a static {@code read(FriendlyByteBuf)}; text whose length the payload does not control
 *       goes through {@link Wire#writeText} / {@link Wire#readText}, a payload whose content grows with data
 *       implements {@link Wire.Oversized} (shrinks to fit one packet) or {@link Wire.Fragmentable} (goes as
 *       fragments).</li>
 *   <li>Add one {@code toServer(...)} or {@code toClient(...)} call here.</li>
 * </ol>
 */
public final class NumenNetwork {

    /** 一种包的解码器与处理器。 */
    private record Route<H>(Function<FriendlyByteBuf, CustomPacketPayload> decoder, H handler) {}

    /** 每种下行包按它的通道 id 登记的路径;表里的解码器只拿去解登记时那一种包。 */
    private static final Map<ResourceLocation, Route<Consumer<CustomPacketPayload>>> TO_CLIENT = new HashMap<>();
    /** 每种上行包的路径,同上。 */
    private static final Map<ResourceLocation, Route<BiConsumer<CustomPacketPayload, ServerPlayer>>> TO_SERVER =
            new HashMap<>();

    /** 客户端从服务端收到的、拼装中的分片消息。 */
    private static final Fragments.Inbox FROM_SERVER = new Fragments.Inbox(Wire.TO_CLIENT);
    /** 服务端从各位玩家的客户端收到的、拼装中的分片消息,一个连接一份。 */
    private static final Map<UUID, Fragments.Inbox> FROM_CLIENTS = new ConcurrentHashMap<>();

    private NumenNetwork() {}

    /** 发给一位玩家的客户端:量过、装得下一个包的才交给加载器,超过的可分片的包分成片(见 {@link Fragments#packets})。 */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        route(TO_CLIENT, payload);
        for (CustomPacketPayload packet : Fragments.packets(Wire.TO_CLIENT, payload)) {
            Services.NETWORK.sendToPlayer(player, packet);
        }
    }

    /** 发给服务端:同 {@link #sendToPlayer}。 */
    public static void sendToServer(CustomPacketPayload payload) {
        route(TO_SERVER, payload);
        for (CustomPacketPayload packet : Fragments.packets(Wire.TO_SERVER, payload)) {
            Services.NETWORK.sendToServer(packet);
        }
    }

    private static <R> R route(Map<ResourceLocation, R> table, CustomPacketPayload payload) {
        R route = table.get(payload.id());
        if (route == null) {
            throw new IllegalArgumentException(payload.id() + " is not registered in this direction");
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
        Fragments.Outcome outcome = inbox.accept(fragment);
        if (outcome instanceof Fragments.Rejected rejected) {
            Constants.LOG.warn("[numen-net] dropped a fragmented message: {}", rejected.why());
            return null;
        }
        if (outcome instanceof Fragments.Complete whole) {
            Route<?> route = inbox.direction() == Wire.TO_SERVER ? TO_SERVER.get(whole.kind())
                    : TO_CLIENT.get(whole.kind());
            CustomPacketPayload payload = route == null ? null : Fragments.decode(route.decoder(), whole.bytes());
            if (!(payload instanceof Wire.Fragmentable)) {
                Constants.LOG.warn("[numen-net] dropped a fragmented {}: not a payload that goes as fragments",
                        whole.kind());
                return null;
            }
            return payload;
        }
        return null;   // 还没收齐
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

    /** 登记进表时抹掉包的具体类型:取出来时按 {@link CustomPacketPayload#id()} 对回同一种。 */
    @SuppressWarnings("unchecked")
    private static <C> C erased(Object value) {
        return (C) value;
    }

    private static <T extends CustomPacketPayload> void toClient(ResourceLocation id, Function<FriendlyByteBuf, T> decoder,
                                                          Consumer<T> handler) {
        TO_CLIENT.put(id, new Route<>(erased(decoder), erased(handler)));
        Services.NETWORK.registerServerToClient(id, decoder, handler);
    }

    private static <T extends CustomPacketPayload> void toServer(ResourceLocation id, Function<FriendlyByteBuf, T> decoder,
                                                          BiConsumer<T, ServerPlayer> handler) {
        TO_SERVER.put(id, new Route<>(erased(decoder), erased(handler)));
        Services.NETWORK.registerClientToServer(id, decoder, handler);
    }

    public static void register() {
        // 两个方向上一条超过单包上限的消息的片(见 Fragments):对端收齐拼回,交给原来的处理器。
        toServer(FragmentPayload.TO_SERVER_ID, FragmentPayload.decoderOf(Wire.TO_SERVER), NumenNetwork::fragmentFromClient);
        toClient(FragmentPayload.TO_CLIENT_ID, FragmentPayload.decoderOf(Wire.TO_CLIENT), NumenNetwork::fragmentFromServer);

        // C→S: run this whole program on my companion; S→C: its one receipt (or the module texts still missing).
        toServer(
                com.dwinovo.numen.network.payload.RunProgramPayload.ID,
                com.dwinovo.numen.network.payload.RunProgramPayload::read,
                com.dwinovo.numen.network.payload.RunProgramPayload::handle);
        toClient(
                com.dwinovo.numen.network.payload.ProgramResultPayload.ID,
                com.dwinovo.numen.network.payload.ProgramResultPayload::read,
                com.dwinovo.numen.network.payload.ProgramResultPayload::handle);

        // C→S: stop the program I sent (the owner spoke, the stop button, an external brain took over).
        toServer(
                com.dwinovo.numen.network.payload.StopProgramPayload.ID,
                com.dwinovo.numen.network.payload.StopProgramPayload::read,
                com.dwinovo.numen.network.payload.StopProgramPayload::handle);

        // S→C: a running program calls a function only the owner's client can answer; C→S: the answer.
        toClient(
                com.dwinovo.numen.network.payload.ClientCallPayload.ID,
                com.dwinovo.numen.network.payload.ClientCallPayload::read,
                com.dwinovo.numen.network.payload.ClientCallPayload::handle);
        toServer(
                com.dwinovo.numen.network.payload.ClientCallResultPayload.ID,
                com.dwinovo.numen.network.payload.ClientCallResultPayload::read,
                com.dwinovo.numen.network.payload.ClientCallResultPayload::handle);

        // S→C: 她此刻在做什么 —— 「她在做什么」的唯一真源。槽一变就推，
        // 派发/重放/顶替/干完走同一个出口（见 CurrentTaskPayload）。
        toClient(
                com.dwinovo.numen.network.payload.CurrentTaskPayload.ID,
                com.dwinovo.numen.network.payload.CurrentTaskPayload::read,
                com.dwinovo.numen.network.payload.CurrentTaskPayload::handle);

        // S→C: 同伴在等主人点头的那条征询(或撤回)——答复框与轮廓只照它画(见 ConsentDesk)。
        toClient(
                com.dwinovo.numen.network.payload.ConsentRequestPayload.ID,
                com.dwinovo.numen.network.payload.ConsentRequestPayload::read,
                com.dwinovo.numen.network.payload.ConsentRequestPayload::handle);

        // C→S: 主人在答复框上的答复(只认主人)。
        toServer(
                com.dwinovo.numen.network.payload.ConsentReplyPayload.ID,
                com.dwinovo.numen.network.payload.ConsentReplyPayload::read,
                com.dwinovo.numen.network.payload.ConsentReplyPayload::handle);

        // C→S: owner pressed Stop — cancel the companion's queued + running tasks.
        toServer(
                com.dwinovo.numen.network.payload.CancelTasksPayload.ID,
                com.dwinovo.numen.network.payload.CancelTasksPayload::read,
                com.dwinovo.numen.network.payload.CancelTasksPayload::handle);

        // C→S: 大脑开始/结束输出——身体据此在说话期间注视主人(纯姿态信号)。
        toServer(
                com.dwinovo.numen.network.payload.SpeakingStatePayload.ID,
                com.dwinovo.numen.network.payload.SpeakingStatePayload::read,
                com.dwinovo.numen.network.payload.SpeakingStatePayload::handle);

        // S→C: an Numen body died; suspend the owner's agent loop (records the cut-off turn
        // with the death cause). Recoverable — see NumenRespawnPayload.
        toClient(
                NumenDeathPayload.ID, NumenDeathPayload::read,
                NumenDeathPayload::handle);

        // S→C: the dead companion has respawned at its owner; resume the suspended loop.
        toClient(
                com.dwinovo.numen.network.payload.NumenRespawnPayload.ID,
                com.dwinovo.numen.network.payload.NumenRespawnPayload::read,
                com.dwinovo.numen.network.payload.NumenRespawnPayload::handle);

        // S→C: a generic async world event (dimension change, hazard, …) for a companion's brain.
        toClient(
                com.dwinovo.numen.network.payload.NumenEventPayload.ID,
                com.dwinovo.numen.network.payload.NumenEventPayload::read,
                com.dwinovo.numen.network.payload.NumenEventPayload::handle);

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

        // S→C: a companion's live pathing state for the debug overlay (lines/boxes).
        toClient(
                com.dwinovo.numen.network.payload.PathDebugPayload.ID,
                com.dwinovo.numen.network.payload.PathDebugPayload::read,
                com.dwinovo.numen.network.payload.PathDebugPayload::handle);

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
                com.dwinovo.numen.network.payload.RequestStatePayload.ID,
                com.dwinovo.numen.network.payload.RequestStatePayload::read,
                com.dwinovo.numen.network.payload.RequestStatePayload::handle);

        // S→C: the requested backpack contents.
        toClient(
                com.dwinovo.numen.network.payload.NumenStatePayload.ID,
                com.dwinovo.numen.network.payload.NumenStatePayload::read,
                com.dwinovo.numen.network.payload.NumenStatePayload::handle);

        // C→S: the panel's "+" button asks to summon a companion by name.
        toServer(
                com.dwinovo.numen.network.payload.SummonRequestPayload.ID,
                com.dwinovo.numen.network.payload.SummonRequestPayload::read,
                com.dwinovo.numen.network.payload.SummonRequestPayload::handle);

        // C→S: the edit card's dismiss → confirm asks to permanently delete a companion (drops its inventory, armor and accessories first).
        toServer(
                com.dwinovo.numen.network.payload.DismissRequestPayload.ID,
                com.dwinovo.numen.network.payload.DismissRequestPayload::read,
                com.dwinovo.numen.network.payload.DismissRequestPayload::handle);

        // C→S: the edit card flips an existing companion between survival/creative.
        toServer(
                com.dwinovo.numen.network.payload.SetGameModePayload.ID,
                com.dwinovo.numen.network.payload.SetGameModePayload::read,
                com.dwinovo.numen.network.payload.SetGameModePayload::handle);

        // C→S: the edit card reskins an existing companion (registry + body recycle).
        toServer(
                com.dwinovo.numen.network.payload.ChangeSkinPayload.ID,
                com.dwinovo.numen.network.payload.ChangeSkinPayload::read,
                com.dwinovo.numen.network.payload.ChangeSkinPayload::handle);
    }
}
