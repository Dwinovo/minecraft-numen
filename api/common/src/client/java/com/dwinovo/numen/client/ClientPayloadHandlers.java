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
        // 旧布局(conversations/ + memory/ + 库里的 assignments 段)一次性搬进
        // companions/<uuid>/;幂等,搬过就跳过。
        com.dwinovo.numen.client.agent.CompanionHome.migrateLegacy();
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
        ClientPayloadSink.uiAction = ClientPayloadHandlers::handleUiAction;
    }

    private static void handleCompanionList(CompanionListPayload p) {
        // Which companions were already known — so we can spot the ones this snapshot just added.
        java.util.Set<UUID> before = new java.util.HashSet<>();
        for (NumenRoster.Entry e : NumenRoster.instance().entries()) before.add(e.uuid());

        java.util.List<NumenRoster.Entry> snapshot = new java.util.ArrayList<>();
        java.util.Set<UUID> now = new java.util.HashSet<>();
        for (CompanionListPayload.Entry e : p.companions()) {
            snapshot.add(new NumenRoster.Entry(e.uuid(), e.name()));
            now.add(e.uuid());
        }
        NumenRoster.instance().replaceAll(snapshot);

        // 花名册里没了的 = 被遣散了:连人带数据一起送走。整个家目录删掉,
        // 会话/记忆/token/绑定五样一起消失——不需要五处各记得清一次。
        for (UUID gone : before) {
            if (!now.contains(gone)) {
                AgentLoopRegistry.dispose(gone);
                com.dwinovo.numen.client.agent.CompanionHome.delete(gone);
            }
        }

        // A newly-arrived companion may have a persona the owner picked at summon (resolved by name here,
        // since the UUID wasn't known client-side until now). Apply it as the starting persona.
        for (CompanionListPayload.Entry e : p.companions()) {
            if (before.contains(e.uuid())) continue;   // not new
            String personaId = com.dwinovo.numen.persona.PersonaLibrary.takePendingSummon(e.name());
            if (personaId != null) {
                var persona = com.dwinovo.numen.persona.PersonaLibrary.instance().get(personaId);
                if (persona != null) {
                    AgentLoopRegistry.getOrCreate(e.uuid())
                            .setInitialPersona(persona.id(), persona.text(), persona.name());
                } else {
                    // 选过却没落地(文件被删/改名):默认人格照常能聊,但"我明明选了"
                    // 必须说清楚——降级提示进聊天框,留得住痕。
                    com.dwinovo.numen.client.chat.ChatLines.notice(e.name(),
                            net.minecraft.client.resources.language.I18n.get("numen.summon.persona_missing"));
                }
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
                if (VoiceLibrary.instance().get(voiceEntry) != null) {
                    com.dwinovo.numen.client.agent.CompanionHome.bind(e.uuid(),
                            com.dwinovo.numen.client.agent.CompanionHome.binding(e.uuid()).withVoice(voiceEntry));
                } else {
                    // 声线条目在召唤途中被删了:她会变哑巴,不说一声主人要找很久
                    com.dwinovo.numen.client.chat.ChatLines.notice(e.name(),
                            net.minecraft.client.resources.language.I18n.get("numen.summon.voice_missing"));
                }
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
            case OPEN_SETTINGS -> com.dwinovo.numen.client.screen.NumenScreen.openSettings();
            case RESET_LOOPS -> AgentLoopRegistry.clear();
            case DEBUG_TEXT_ON -> ChatDisplayFilters.set(new DebugChatDisplayFilter());
            case DEBUG_TEXT_OFF -> ChatDisplayFilters.set(null);
        }
    }
}
