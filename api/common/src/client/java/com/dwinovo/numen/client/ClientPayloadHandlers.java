package com.dwinovo.numen.client;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.chat.ChatDisplayModes;
import com.dwinovo.numen.client.chat.RawMessageMode;
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
        // getOrCreate:跟死亡/事件同理 —— 这是状态推送,只在槽变化的那一刻发一次,
        // loop 还没造出来就丢掉的话,客户端永远不会再听说这件活。
        ClientPayloadSink.currentTask = p ->
                com.dwinovo.numen.client.agent.AgentLoopRegistry.getOrCreate(p.entityUuid())
                        .onCurrentTask(p);
        ClientPayloadSink.death = ClientPayloadHandlers::handleDeath;
        // getOrCreate:主人登录时补发的离线事件可能先于任何交互到达,
        // 那时 loop 还没造出来——用 get 会把补发的事件整批丢掉。
        ClientPayloadSink.event = p ->
                AgentLoopRegistry.getOrCreate(p.entityUuid())
                        .pushEvent(p.entryType(), p.text(), p.ts(), p.urgent());
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
        java.util.Set<UUID> before = new java.util.HashSet<>();
        for (NumenRoster.Entry e : NumenRoster.instance().entries()) before.add(e.uuid());

        java.util.List<NumenRoster.Entry> snapshot = new java.util.ArrayList<>();
        java.util.Set<UUID> onRoster = new java.util.LinkedHashSet<>();
        for (CompanionListPayload.Entry e : p.companions()) {
            snapshot.add(NumenRoster.toEntry(e.uuid(), e.name(), e.respawnInMs()));
            onRoster.add(e.uuid());
        }
        NumenRoster.instance().replaceAll(p.worldId(), snapshot);

        // 对账:本世界不在名册上的 = 已被永久遣散,家目录整个删掉。
        // 名册来自服务端的持久注册表(死亡/休眠的同伴都在册),所以"不在册"只有这一个意思。
        // 这是状态同步不是事件通知——掉线时遣散的、信号丢了的,下次收到名册照样对得平。
        int swept = com.dwinovo.numen.client.agent.CompanionHome.reconcile(p.worldId(), onRoster);
        if (swept > 0) {
            Constants.LOG.info("[numen-net] 对账清理了 {} 只已遣散同伴的数据", swept);
        }
        for (UUID gone : before) {
            if (!onRoster.contains(gone)) {
                AgentLoopRegistry.dispose(gone);   // 大脑先停,免得在飞的回合写回已删的家
            }
        }
        // 反过来的一半:名册说她存在,她就该有大脑。
        //
        // 「她有没有大脑」必须取决于<b>她存不存在</b>,不能取决于<b>谁先碰过她</b>。
        // 懒创建就是后者:每冒出一种新的入站消息(快捷键、桥接消息、离线补发的事件、
        // 复活包、死亡包……)都得记得补一次 getOrCreate,漏掉哪个,那种包就整条丢掉。
        // 丢掉死亡包的后果是她死了队列没上锁,紧接着到达的 task_finished 急件照常
        // 开一轮,模型派出感知工具,服务端找不到身体就把她提前复活一具,5 秒后定时
        // 复活再来一具。
        //
        // 名册是"谁存在"的权威真源,每次变化都推一份(召唤、遣散、死亡、登录都走它),
        // 所以接在这里就一劳永逸:此后任何包以任何顺序到达都有地方落,各个 sink 也
        // 不必再逐个纠结 get 还是 getOrCreate。
        //
        // 开销就是每只同伴读一次会话日志。一个存档里的同伴是个位数,而且孤儿目录
        // 不在名册上、不会被造 —— 拿它换掉一整类"包先到、loop 还没有"的 bug,值。
        for (UUID uuid : onRoster) {
            AgentLoopRegistry.getOrCreate(uuid);
        }

        // A newly-arrived companion may have a persona the owner picked at summon (resolved by name here,
        // since the UUID wasn't known client-side until now). Apply it as the starting persona.
        for (CompanionListPayload.Entry e : p.companions()) {
            if (before.contains(e.uuid())) continue;   // not new
            String personaId = com.dwinovo.numen.persona.PersonaLibrary.takePendingSummon(e.name());
            if (personaId != null) {
                var persona = com.dwinovo.numen.persona.PersonaLibrary.instance().get(personaId);
                if (persona != null) {
                    AgentLoopRegistry.getOrCreate(e.uuid()).setInitialPersona(persona.id());
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

    /**
     * getOrCreate(not get):跟 {@link #handleRespawn} 对称。
     *
     * <p>这一条<b>丢不得</b>:它是给队列上锁的那一下。丢了的话她死着但队列没锁,
     * 紧接着到达的 task_finished 急件照常开一轮,模型派出感知工具,服务端找不到身体
     * 就把她提前复活一具,5 秒后定时复活再来一具。
     *
     * <p>也别指望 {@code restoreFromDisk} 里"按名册重新上锁"那道兜底接住:名册与
     * 死亡包是两条独立推送、没有顺序保证,loop 建起来的那一刻名册可能还没说她死了。
     */
    private static void handleDeath(NumenDeathPayload p) {
        Constants.LOG.info("[numen-net] numen_death entity={} ({}) — suspending loop", p.entityUuid(), p.cause());
        // 只管大脑:死亡的展示状态(倒计时)跟着名册走,服务端在标记死亡后就推了一份。
        // 这条 payload 存在的理由是"她为什么死的"——那是叙事,必须恰好送达一次。
        AgentLoopRegistry.getOrCreate(p.entityUuid()).onEntityDied(p.cause());
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
        AgentLoopRegistry.getOrCreate(p.entityUuid()).onRespawned(p.cause());
    }

    private static void handleUiAction(ClientUiActionPayload p) {
        switch (p.action()) {
            case OPEN_SETTINGS -> com.dwinovo.numen.client.screen.NumenScreen.openSettings();
            case RESET_LOOPS -> AgentLoopRegistry.clear();
            case DEBUG_TEXT_ON -> ChatDisplayModes.set(new RawMessageMode());
            case DEBUG_TEXT_OFF -> ChatDisplayModes.set(null);
        }
    }
}
