package com.dwinovo.numen.client;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.ClientDeaths;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.chat.ChatDisplayFilters;
import com.dwinovo.numen.client.chat.DebugChatDisplayFilter;
import com.dwinovo.numen.client.data.ClientNumenInventory;
import com.dwinovo.numen.client.data.ClientNumenLocations;
import com.dwinovo.numen.client.debug.PathDebugState;
import com.dwinovo.numen.client.hud.SpeechBubbles;
import com.dwinovo.numen.client.screen.SettingsScreen;
import com.dwinovo.numen.client.voice.VoiceLibrary;
import com.dwinovo.numen.network.ClientPayloadSink;
import com.dwinovo.numen.network.payload.ClientUiActionPayload;
import com.dwinovo.numen.network.payload.CompanionListPayload;
import com.dwinovo.numen.network.payload.NumenDeathPayload;
import com.dwinovo.numen.network.payload.NumenEventPayload;
import com.dwinovo.numen.network.payload.NumenInventoryPayload;
import com.dwinovo.numen.network.payload.NumenLocationsPayload;
import com.dwinovo.numen.network.payload.NumenRespawnPayload;
import com.dwinovo.numen.network.payload.PathDebugPayload;
import com.dwinovo.numen.network.payload.SpeechBubbleSyncPayload;

import java.util.UUID;

/**
 * 下行 payload 的客户端处理体——record 与编解码器留在主源码集,处理体
 * 住在这儿,客户端入口启动时 {@link #install()} 挂进
 * {@link ClientPayloadSink}。全部在客户端主线程被调用(网络层保证)。
 */
public final class ClientPayloadHandlers {

    private ClientPayloadHandlers() {}

    /** 客户端入口调用一次,把全部处理体挂进主源码集的挂点。 */
    public static void install() {
        ClientPayloadSink.companionList = ClientPayloadHandlers::handleCompanionList;
        ClientPayloadSink.death = ClientPayloadHandlers::handleDeath;
        ClientPayloadSink.event = p ->
                AgentLoopRegistry.get(p.entityUuid()).ifPresent(loop -> loop.pushEvent(p.xml(), p.principal()));
        ClientPayloadSink.inventory = p ->
                ClientNumenInventory.update(p.uuid(), new ClientNumenInventory.Snapshot(
                        p.loaded(), p.items(), p.craft(), p.foodLevel(), p.saturation(),
                        System.currentTimeMillis()));
        ClientPayloadSink.locations = ClientPayloadHandlers::handleLocations;
        ClientPayloadSink.respawn = ClientPayloadHandlers::handleRespawn;
        ClientPayloadSink.pathDebug = PathDebugState::accept;
        ClientPayloadSink.speechBubble = p -> SpeechBubbles.apply(p.entityUuid(), p.kind(), p.text());
        ClientPayloadSink.uiAction = ClientPayloadHandlers::handleUiAction;
    }

    private static void handleCompanionList(CompanionListPayload p) {
        // Which companions were already known — so we can spot the ones this snapshot just added.
        java.util.Set<UUID> before = new java.util.HashSet<>();
        for (NumenRoster.Entry e : NumenRoster.instance().entries()) before.add(e.uuid());

        java.util.List<NumenRoster.Entry> snapshot = new java.util.ArrayList<>();
        for (CompanionListPayload.Entry e : p.companions()) {
            snapshot.add(new NumenRoster.Entry(e.uuid(), e.name()));
        }
        NumenRoster.instance().replaceAll(snapshot);

        // A newly-arrived companion may have a persona the owner picked at summon (resolved by name here,
        // since the UUID wasn't known client-side until now). Apply it as the starting persona.
        for (CompanionListPayload.Entry e : p.companions()) {
            if (before.contains(e.uuid())) continue;   // not new
            var persona = com.dwinovo.numen.persona.PersonaLibrary.takePendingSummon(e.name());
            if (persona != null) {
                AgentLoopRegistry.getOrCreate(e.uuid())
                        .setInitialPersona(persona.id(), persona.text(), persona.name());
            }
            // Same resolution for the provider entry picked at summon (selection is
            // mandatory in the summon panel, so a new companion always carries one).
            String providerEntry = com.dwinovo.numen.agent.llm.ProviderLibrary.takePendingSummon(e.name());
            if (providerEntry != null) {
                AgentLoopRegistry.getOrCreate(e.uuid()).setProviderEntry(providerEntry);
            }
            // And for the voice entry picked at summon (optional — none pended = silent).
            String voiceEntry = VoiceLibrary.takePendingSummon(e.name());
            if (voiceEntry != null) {
                VoiceLibrary.instance().assign(e.uuid(), voiceEntry);
            }
        }
    }

    private static void handleDeath(NumenDeathPayload p) {
        Constants.LOG.info("[numen-net] numen_death entity={} ({}) — suspending loop", p.entityUuid(), p.cause());
        AgentLoopRegistry.get(p.entityUuid()).ifPresent(loop -> loop.onEntityDied(p.cause()));
        // Keep it in the roster (marked dead) so the HUD / rail can show the respawn countdown;
        // it goes live again on NumenRespawnPayload.
        ClientDeaths.markDead(p.entityUuid(), System.currentTimeMillis() + p.respawnDelayMs());
    }

    private static void handleLocations(NumenLocationsPayload p) {
        long now = System.currentTimeMillis();
        for (NumenLocationsPayload.Snapshot s : p.snapshots()) {
            ClientNumenLocations.update(s.uuid(), new ClientNumenLocations.Snapshot(
                    s.found(), s.loaded(), s.x(), s.y(), s.z(), s.dimension(), s.hp(), s.maxHp(), now));
        }
    }

    /** getOrCreate(not get):登出后 loop 可能还不存在——先造出来,死亡事件才有处落。 */
    private static void handleRespawn(NumenRespawnPayload p) {
        Constants.LOG.info("[numen-net] numen_respawn entity={} ({}) — resuming loop", p.entityUuid(), p.cause());
        ClientDeaths.clear(p.entityUuid());
        AgentLoopRegistry.getOrCreate(p.entityUuid()).onRespawned(p.cause());
    }

    private static void handleUiAction(ClientUiActionPayload p) {
        switch (p.action()) {
            case OPEN_SETTINGS -> SettingsScreen.open(null);
            case RESET_LOOPS -> AgentLoopRegistry.clear();
            case DEBUG_TEXT_ON -> ChatDisplayFilters.set(new DebugChatDisplayFilter());
            case DEBUG_TEXT_OFF -> ChatDisplayFilters.set(null);
        }
    }
}
