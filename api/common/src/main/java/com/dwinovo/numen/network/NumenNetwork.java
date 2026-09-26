package com.dwinovo.numen.network;

import com.dwinovo.numen.network.payload.NumenDeathPayload;
import com.dwinovo.numen.network.payload.NumenLocationsPayload;
import com.dwinovo.numen.network.payload.LocateNumenPayload;
import com.dwinovo.numen.network.payload.ClientUiActionPayload;
import com.dwinovo.numen.network.payload.CompanionListPayload;
import com.dwinovo.numen.platform.Services;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
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
 * {@link #sendToPlayer} and {@link #sendToServer} measure the payload with its own
 * codec against {@link Wire} before handing it to the loader: what goes on the wire
 * always fits, so no length can make netty drop the connection. Payloads going to
 * the server carry nothing from the registries, so their codecs are written over a
 * plain {@link ByteBuf} and measure without one.
 *
 * <h2>Adding a new payload</h2>
 * <ol>
 *   <li>Define a record under {@code com.dwinovo.numen.network.payload}
 *       implementing {@code CustomPacketPayload} with a public {@code Type}
 *       and {@code StreamCodec}; text whose length the payload does not control
 *       uses {@link Wire#text()}, and a payload whose content grows with data
 *       implements {@link Wire.Oversized}.</li>
 *   <li>Add one {@code toServer(...)} or {@code toClient(...)} call here.</li>
 * </ol>
 */
public final class NumenNetwork {

    /** 每种下行包按它的类型登记的编解码器;表里的编解码器只拿去编登记时那一种包。 */
    private static final Map<CustomPacketPayload.Type<?>, StreamCodec<? super RegistryFriendlyByteBuf,
            CustomPacketPayload>> TO_CLIENT = new HashMap<>();
    /** 每种上行包的编解码器,同上。 */
    private static final Map<CustomPacketPayload.Type<?>, StreamCodec<ByteBuf, CustomPacketPayload>> TO_SERVER =
            new HashMap<>();

    private NumenNetwork() {}

    /** 发给一位玩家的客户端:量过、装得下的那个才交给加载器(见 {@link Wire#fit})。 */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        Services.NETWORK.sendToPlayer(player, Wire.TO_CLIENT.fit(codec(TO_CLIENT, payload), payload,
                () -> new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess())));
    }

    /** 发给服务端:量过、装得下的那个才交给加载器(见 {@link Wire#fit})。 */
    public static void sendToServer(CustomPacketPayload payload) {
        Services.NETWORK.sendToServer(Wire.TO_SERVER.fit(codec(TO_SERVER, payload), payload, Unpooled::buffer));
    }

    private static <C> C codec(Map<CustomPacketPayload.Type<?>, C> table, CustomPacketPayload payload) {
        C codec = table.get(payload.type());
        if (codec == null) {
            throw new IllegalArgumentException(payload.type().id() + " is not registered in this direction");
        }
        return codec;
    }

    /** 登记进表时抹掉包的具体类型:取出来时按 {@link CustomPacketPayload#type()} 对回同一种。 */
    @SuppressWarnings("unchecked")
    private static <C> C erased(StreamCodec<?, ?> codec) {
        return (C) codec;
    }

    private static <T extends CustomPacketPayload> void toClient(CustomPacketPayload.Type<T> type,
                                                                 StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
                                                                 Consumer<T> handler) {
        TO_CLIENT.put(type, erased(codec));
        Services.NETWORK.registerServerToClient(type, codec, handler);
    }

    private static <T extends CustomPacketPayload> void toServer(CustomPacketPayload.Type<T> type,
                                                                 StreamCodec<ByteBuf, T> codec,
                                                                 BiConsumer<T, ServerPlayer> handler) {
        TO_SERVER.put(type, erased(codec));
        Services.NETWORK.registerClientToServer(type, codec, handler);
    }

    public static void register() {
        // C→S: the client agent loop decided to run a body-bound tool on its companion.
        toServer(
                com.dwinovo.numen.network.payload.ExecuteToolPayload.TYPE,
                com.dwinovo.numen.network.payload.ExecuteToolPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.ExecuteToolPayload::handle);

        // S→C: a body-bound tool's result (or an async dispatch receipt) coming home.
        toClient(
                com.dwinovo.numen.network.payload.TaskResultPayload.TYPE,
                com.dwinovo.numen.network.payload.TaskResultPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.TaskResultPayload::handle);

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
