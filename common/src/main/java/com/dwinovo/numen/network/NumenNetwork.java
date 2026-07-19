package com.dwinovo.numen.network;

import com.dwinovo.numen.network.payload.NumenDeathPayload;
import com.dwinovo.numen.network.payload.NumenLocationsPayload;
import com.dwinovo.numen.network.payload.LocateNumenPayload;
import com.dwinovo.numen.network.payload.ClientUiActionPayload;
import com.dwinovo.numen.network.payload.CompanionListPayload;
import com.dwinovo.numen.platform.Services;

/**
 * Central registration hub for every {@link
 * net.minecraft.network.protocol.common.custom.CustomPacketPayload} the mod
 * declares. Each loader's mod-init code calls {@link #register} exactly once
 * during startup; the {@link Services#NETWORK} platform implementation handles
 * the loader-specific timing.
 *
 * <h2>Adding a new payload</h2>
 * <ol>
 *   <li>Define a record under {@code com.dwinovo.numen.network.payload}
 *       implementing {@code CustomPacketPayload} with a public {@code Type}
 *       and {@code StreamCodec}.</li>
 *   <li>Add one {@code registerClientToServer(...)} or
 *       {@code registerServerToClient(...)} call here.</li>
 * </ol>
 */
public final class NumenNetwork {

    private NumenNetwork() {}

    public static void register() {
        // C→S: the client agent loop decided to run a body-bound tool on its companion.
        Services.NETWORK.registerClientToServer(
                com.dwinovo.numen.network.payload.ExecuteToolPayload.TYPE,
                com.dwinovo.numen.network.payload.ExecuteToolPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.ExecuteToolPayload::handle);

        // S→C: a body-bound tool's result (or an async dispatch receipt) coming home.
        Services.NETWORK.registerServerToClient(
                com.dwinovo.numen.network.payload.TaskResultPayload.TYPE,
                com.dwinovo.numen.network.payload.TaskResultPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.TaskResultPayload::handle);

        // C→S: owner pressed Stop — cancel the companion's queued + running tasks.
        Services.NETWORK.registerClientToServer(
                com.dwinovo.numen.network.payload.CancelTasksPayload.TYPE,
                com.dwinovo.numen.network.payload.CancelTasksPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.CancelTasksPayload::handle);

        // C→S: 大脑开始/结束输出——身体据此在说话期间注视主人(纯姿态信号)。
        Services.NETWORK.registerClientToServer(
                com.dwinovo.numen.network.payload.SpeakingStatePayload.TYPE,
                com.dwinovo.numen.network.payload.SpeakingStatePayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.SpeakingStatePayload::handle);

        // S→C: an Numen body died; suspend the owner's agent loop (resolves the in-flight
        // tool call with the death cause). Recoverable — see NumenRespawnPayload.
        Services.NETWORK.registerServerToClient(
                NumenDeathPayload.TYPE, NumenDeathPayload.STREAM_CODEC,
                NumenDeathPayload::handle);

        // S→C: the dead companion has respawned at its owner; resume the suspended loop.
        Services.NETWORK.registerServerToClient(
                com.dwinovo.numen.network.payload.NumenRespawnPayload.TYPE,
                com.dwinovo.numen.network.payload.NumenRespawnPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.NumenRespawnPayload::handle);

        // S→C: a generic async world event (dimension change, hazard, …) for a companion's brain.
        Services.NETWORK.registerServerToClient(
                com.dwinovo.numen.network.payload.NumenEventPayload.TYPE,
                com.dwinovo.numen.network.payload.NumenEventPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.NumenEventPayload::handle);

        // S→C: the owner's companion roster (UUID + name), pushed on login + summon
        // so the client panel knows which fake players are its companions.
        Services.NETWORK.registerServerToClient(
                CompanionListPayload.TYPE, CompanionListPayload.STREAM_CODEC,
                CompanionListPayload::handle);

        // S→C: server `/numen` verbs that must act on the caller's own client
        // (open settings GUI / reset conversations).
        Services.NETWORK.registerServerToClient(
                ClientUiActionPayload.TYPE, ClientUiActionPayload.STREAM_CODEC,
                ClientUiActionPayload::handle);

        // S→C: a companion's live pathing state for the debug overlay (lines/boxes).
        Services.NETWORK.registerServerToClient(
                com.dwinovo.numen.network.payload.PathDebugPayload.TYPE,
                com.dwinovo.numen.network.payload.PathDebugPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.PathDebugPayload::handle);

        // C→S: roster panel asks where its (possibly far / cross-dimension) pets are.
        Services.NETWORK.registerClientToServer(
                LocateNumenPayload.TYPE, LocateNumenPayload.STREAM_CODEC,
                LocateNumenPayload::handle);

        // S→C: locate answers — position/dimension/HP snapshots per pet.
        Services.NETWORK.registerServerToClient(
                NumenLocationsPayload.TYPE, NumenLocationsPayload.STREAM_CODEC,
                NumenLocationsPayload::handle);

        // C→S: the Items tab asks for a companion's backpack (not client-synced).
        Services.NETWORK.registerClientToServer(
                com.dwinovo.numen.network.payload.RequestInventoryPayload.TYPE,
                com.dwinovo.numen.network.payload.RequestInventoryPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.RequestInventoryPayload::handle);

        // S→C: the requested backpack contents.
        Services.NETWORK.registerServerToClient(
                com.dwinovo.numen.network.payload.NumenInventoryPayload.TYPE,
                com.dwinovo.numen.network.payload.NumenInventoryPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.NumenInventoryPayload::handle);

        // C→S: the panel's "+" button asks to summon a companion by name.
        Services.NETWORK.registerClientToServer(
                com.dwinovo.numen.network.payload.SummonRequestPayload.TYPE,
                com.dwinovo.numen.network.payload.SummonRequestPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.SummonRequestPayload::handle);

        // C→S: the rail ✕ → confirm asks to permanently delete a companion (drops its inventory first).
        Services.NETWORK.registerClientToServer(
                com.dwinovo.numen.network.payload.DismissRequestPayload.TYPE,
                com.dwinovo.numen.network.payload.DismissRequestPayload.STREAM_CODEC,
                com.dwinovo.numen.network.payload.DismissRequestPayload::handle);
    }
}
