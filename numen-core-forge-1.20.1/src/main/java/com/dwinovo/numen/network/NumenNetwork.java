package com.dwinovo.numen.network;

import com.dwinovo.numen.network.payload.ClientUiActionPayload;
import com.dwinovo.numen.network.payload.CompanionListPayload;
import com.dwinovo.numen.network.payload.DismissRequestPayload;
import com.dwinovo.numen.network.payload.LocateNumenPayload;
import com.dwinovo.numen.network.payload.NumenDeathPayload;
import com.dwinovo.numen.network.payload.NumenEventPayload;
import com.dwinovo.numen.network.payload.NumenLocationsPayload;
import com.dwinovo.numen.network.payload.NumenRespawnPayload;
import com.dwinovo.numen.network.payload.PathVizPayload;
import com.dwinovo.numen.network.payload.SummonRequestPayload;
import com.dwinovo.numen.network.payload.TaskUiRequestPayload;
import com.dwinovo.numen.network.payload.TaskListPayload;
import com.dwinovo.numen.network.payload.CompanionSettingsRequestPayload;
import com.dwinovo.numen.network.payload.CompanionSettingsPayload;
import com.dwinovo.numen.network.payload.OpenCompanionInventoryPayload;
import com.dwinovo.numen.platform.Services;

/**
 * Central registration hub for every network packet the mod declares. Each
 * loader's mod-init code calls {@link #register} exactly once during startup;
 * the {@link Services#NETWORK} platform implementation handles the
 * loader-specific timing.
 *
 * <h2>Adding a new payload</h2>
 * <ol>
 *   <li>Define a record under {@code com.dwinovo.numen.network.payload}
 *       with a static {@code ResourceLocation ID}, instance
 *       {@code encode(FriendlyByteBuf)}, static {@code decode(FriendlyByteBuf)},
 *       and static {@code handle} method.</li>
 *   <li>Add one {@code registerClientToServer(...)} or
 *       {@code registerServerToClient(...)} call here.</li>
 * </ol>
 */
public final class NumenNetwork {

    private NumenNetwork() {}

    public static void register() {
        // S->C: an Numen body died; suspend the owner's agent loop (resolves the in-flight
        // tool call with the death cause). Recoverable -- see NumenRespawnPayload.
        Services.NETWORK.registerServerToClient(
                NumenDeathPayload.ID, NumenDeathPayload.class,
                NumenDeathPayload::encode, NumenDeathPayload::decode,
                NumenDeathPayload::handle);

        // S->C: the dead companion has respawned at its owner; resume the suspended loop.
        Services.NETWORK.registerServerToClient(
                NumenRespawnPayload.ID, NumenRespawnPayload.class,
                NumenRespawnPayload::encode, NumenRespawnPayload::decode,
                NumenRespawnPayload::handle);

        // S->C: a generic async world event (dimension change, hazard, ...) for a companion's brain.
        Services.NETWORK.registerServerToClient(
                NumenEventPayload.ID, NumenEventPayload.class,
                NumenEventPayload::encode, NumenEventPayload::decode,
                NumenEventPayload::handle);

        // S->C: the owner's companion roster (UUID + name), pushed on login + summon
        // so the client panel knows which fake players are its companions.
        Services.NETWORK.registerServerToClient(
                CompanionListPayload.ID, CompanionListPayload.class,
                CompanionListPayload::encode, CompanionListPayload::decode,
                CompanionListPayload::handle);

        // S->C: the companion's current pathfinding plan, for the in-world path
        // overlay (Baritone PathRenderer, ported to our server-authored path).
        Services.NETWORK.registerServerToClient(
                PathVizPayload.ID, PathVizPayload.class,
                PathVizPayload::encode, PathVizPayload::decode,
                PathVizPayload::handle);

        // S->C: server `/numen` verbs that must act on the caller's own client
        // (open settings GUI / reset conversations).
        Services.NETWORK.registerServerToClient(
                ClientUiActionPayload.ID, ClientUiActionPayload.class,
                ClientUiActionPayload::encode, ClientUiActionPayload::decode,
                ClientUiActionPayload::handle);

        // C->S: roster panel asks where its (possibly far / cross-dimension) pets are.
        Services.NETWORK.registerClientToServer(
                LocateNumenPayload.ID, LocateNumenPayload.class,
                LocateNumenPayload::encode, LocateNumenPayload::decode,
                LocateNumenPayload::handle);

        // S->C: locate answers -- position/dimension/HP snapshots per pet.
        Services.NETWORK.registerServerToClient(
                NumenLocationsPayload.ID, NumenLocationsPayload.class,
                NumenLocationsPayload::encode, NumenLocationsPayload::decode,
                NumenLocationsPayload::handle);

        // C->S: the Items tab asks for a companion's backpack (not client-synced).

        Services.NETWORK.registerClientToServer(TaskUiRequestPayload.ID, TaskUiRequestPayload.class,
                TaskUiRequestPayload::encode, TaskUiRequestPayload::decode, TaskUiRequestPayload::handle);
        Services.NETWORK.registerServerToClient(TaskListPayload.ID, TaskListPayload.class,
                TaskListPayload::encode, TaskListPayload::decode, TaskListPayload::handle);
        Services.NETWORK.registerClientToServer(CompanionSettingsRequestPayload.ID, CompanionSettingsRequestPayload.class,
                CompanionSettingsRequestPayload::encode, CompanionSettingsRequestPayload::decode, CompanionSettingsRequestPayload::handle);
        Services.NETWORK.registerServerToClient(CompanionSettingsPayload.ID, CompanionSettingsPayload.class,
                CompanionSettingsPayload::encode, CompanionSettingsPayload::decode, CompanionSettingsPayload::handle);
        Services.NETWORK.registerClientToServer(OpenCompanionInventoryPayload.ID, OpenCompanionInventoryPayload.class,
                OpenCompanionInventoryPayload::encode, OpenCompanionInventoryPayload::decode,
                OpenCompanionInventoryPayload::handle);

        // C->S: the panel's "+" button asks to summon a companion by name.
        Services.NETWORK.registerClientToServer(
                SummonRequestPayload.ID, SummonRequestPayload.class,
                SummonRequestPayload::encode, SummonRequestPayload::decode,
                SummonRequestPayload::handle);

        // C->S: the rail x -> confirm asks to permanently delete a companion (drops its inventory first).
        Services.NETWORK.registerClientToServer(
                DismissRequestPayload.ID, DismissRequestPayload.class,
                DismissRequestPayload::encode, DismissRequestPayload::decode,
                DismissRequestPayload::handle);
    }
}
