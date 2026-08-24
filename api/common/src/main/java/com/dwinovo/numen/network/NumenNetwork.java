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
 *       implementing {@code CustomPacketPayload} (1.20.4 shape: {@code write} +
 *       {@code id}) with a public {@code ID} and a static {@code read}.</li>
 *   <li>Add one {@code registerClientToServer(...)} or
 *       {@code registerServerToClient(...)} call here.</li>
 * </ol>
 */
public final class NumenNetwork {

    private NumenNetwork() {}

    public static void register() {
        // C→S: the client agent loop decided to run a body-bound tool on its companion.
        Services.NETWORK.registerClientToServer(
                com.dwinovo.numen.network.payload.ExecuteToolPayload.ID,
                com.dwinovo.numen.network.payload.ExecuteToolPayload::read,
                com.dwinovo.numen.network.payload.ExecuteToolPayload::handle);

        // S→C: a body-bound tool's result (or an async dispatch receipt) coming home.
        Services.NETWORK.registerServerToClient(
                com.dwinovo.numen.network.payload.TaskResultPayload.ID,
                com.dwinovo.numen.network.payload.TaskResultPayload::read,
                com.dwinovo.numen.network.payload.TaskResultPayload::handle);

        // S→C: 她此刻在做什么 —— 「她在做什么」的唯一真源。槽一变就推，
        // 派发/重放/顶替/干完走同一个出口（见 CurrentTaskPayload）。
        Services.NETWORK.registerServerToClient(
                com.dwinovo.numen.network.payload.CurrentTaskPayload.ID,
                com.dwinovo.numen.network.payload.CurrentTaskPayload::read,
                com.dwinovo.numen.network.payload.CurrentTaskPayload::handle);

        // C→S: owner pressed Stop — cancel the companion's queued + running tasks.
        Services.NETWORK.registerClientToServer(
                com.dwinovo.numen.network.payload.CancelTasksPayload.ID,
                com.dwinovo.numen.network.payload.CancelTasksPayload::read,
                com.dwinovo.numen.network.payload.CancelTasksPayload::handle);

        // C→S: 大脑开始/结束输出——身体据此在说话期间注视主人(纯姿态信号)。
        Services.NETWORK.registerClientToServer(
                com.dwinovo.numen.network.payload.SpeakingStatePayload.ID,
                com.dwinovo.numen.network.payload.SpeakingStatePayload::read,
                com.dwinovo.numen.network.payload.SpeakingStatePayload::handle);

        // S→C: an Numen body died; suspend the owner's agent loop (resolves the in-flight
        // tool call with the death cause). Recoverable — see NumenRespawnPayload.
        Services.NETWORK.registerServerToClient(
                NumenDeathPayload.ID, NumenDeathPayload::read,
                NumenDeathPayload::handle);

        // S→C: the dead companion has respawned at its owner; resume the suspended loop.
        Services.NETWORK.registerServerToClient(
                com.dwinovo.numen.network.payload.NumenRespawnPayload.ID,
                com.dwinovo.numen.network.payload.NumenRespawnPayload::read,
                com.dwinovo.numen.network.payload.NumenRespawnPayload::handle);

        // S→C: a generic async world event (dimension change, hazard, …) for a companion's brain.
        Services.NETWORK.registerServerToClient(
                com.dwinovo.numen.network.payload.NumenEventPayload.ID,
                com.dwinovo.numen.network.payload.NumenEventPayload::read,
                com.dwinovo.numen.network.payload.NumenEventPayload::handle);

        // S→C: the owner's companion roster (UUID + name), pushed on login + summon
        // so the client panel knows which fake players are its companions.
        Services.NETWORK.registerServerToClient(
                CompanionListPayload.ID, CompanionListPayload::read,
                CompanionListPayload::handle);

        // S→C: server `/numen` verbs that must act on the caller's own client
        // (open settings GUI / reset conversations).
        Services.NETWORK.registerServerToClient(
                ClientUiActionPayload.ID, ClientUiActionPayload::read,
                ClientUiActionPayload::handle);

        // S→C: a companion's live pathing state for the debug overlay (lines/boxes).
        Services.NETWORK.registerServerToClient(
                com.dwinovo.numen.network.payload.PathDebugPayload.ID,
                com.dwinovo.numen.network.payload.PathDebugPayload::read,
                com.dwinovo.numen.network.payload.PathDebugPayload::handle);

        // C→S: roster panel asks where its (possibly far / cross-dimension) pets are.
        Services.NETWORK.registerClientToServer(
                LocateNumenPayload.ID, LocateNumenPayload::read,
                LocateNumenPayload::handle);

        // S→C: locate answers — position/dimension/HP snapshots per pet.
        Services.NETWORK.registerServerToClient(
                NumenLocationsPayload.ID, NumenLocationsPayload::read,
                NumenLocationsPayload::handle);

        // C→S: the Items tab asks for a companion's backpack (not client-synced).
        Services.NETWORK.registerClientToServer(
                com.dwinovo.numen.network.payload.RequestStatePayload.ID,
                com.dwinovo.numen.network.payload.RequestStatePayload::read,
                com.dwinovo.numen.network.payload.RequestStatePayload::handle);

        // S→C: the requested backpack contents.
        Services.NETWORK.registerServerToClient(
                com.dwinovo.numen.network.payload.NumenStatePayload.ID,
                com.dwinovo.numen.network.payload.NumenStatePayload::read,
                com.dwinovo.numen.network.payload.NumenStatePayload::handle);

        // C→S: the panel's "+" button asks to summon a companion by name.
        Services.NETWORK.registerClientToServer(
                com.dwinovo.numen.network.payload.SummonRequestPayload.ID,
                com.dwinovo.numen.network.payload.SummonRequestPayload::read,
                com.dwinovo.numen.network.payload.SummonRequestPayload::handle);

        // C→S: the edit card's dismiss → confirm asks to permanently delete a companion (drops its inventory first).
        Services.NETWORK.registerClientToServer(
                com.dwinovo.numen.network.payload.DismissRequestPayload.ID,
                com.dwinovo.numen.network.payload.DismissRequestPayload::read,
                com.dwinovo.numen.network.payload.DismissRequestPayload::handle);

        // C→S: the edit card flips an existing companion between survival/creative.
        Services.NETWORK.registerClientToServer(
                com.dwinovo.numen.network.payload.SetGameModePayload.ID,
                com.dwinovo.numen.network.payload.SetGameModePayload::read,
                com.dwinovo.numen.network.payload.SetGameModePayload::handle);

        // C→S: the edit card reskins an existing companion (registry + body recycle).
        Services.NETWORK.registerClientToServer(
                com.dwinovo.numen.network.payload.ChangeSkinPayload.ID,
                com.dwinovo.numen.network.payload.ChangeSkinPayload::read,
                com.dwinovo.numen.network.payload.ChangeSkinPayload::handle);
    }
}
