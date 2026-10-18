package com.dwinovo.numen.api.client;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.client.agent.NumenRoster;
import com.dwinovo.numen.api.client.chat.ChatDisplayModes;
import com.dwinovo.numen.api.client.chat.RawMessageMode;
import com.dwinovo.numen.api.client.data.ClientNumenState;
import com.dwinovo.numen.api.client.data.ClientNumenLocations;
import com.dwinovo.numen.api.client.hud.SpeechBubbles;
import com.dwinovo.numen.api.network.ClientPayloadSink;
import com.dwinovo.numen.api.network.payload.ClientUiActionPayload;
import com.dwinovo.numen.api.network.payload.CompanionListPayload;
import com.dwinovo.numen.api.network.payload.NumenStatePayload;
import com.dwinovo.numen.api.network.payload.NumenLocationsPayload;

import java.util.UUID;

/**
 * 下行 payload 的客户端处理体——record 与编解码器留在主源码集,处理体
 * 住在这儿,客户端入口启动时 {@link #install()} 挂进
 * {@link ClientPayloadSink}。全部在客户端主线程被调用(网络层保证)。
 */
public final class ClientPayloadHandlers {

    private ClientPayloadHandlers() {}

    /**
     * 客户端入口调用一次,把全部处理体挂进主源码集的挂点。
     *
     * <p><b>这里只挂处理体</b>——需要客户端真的起来的活不属于这儿:模组构造在 datagen
     * 里同样会跑,那时 {@code Minecraft.getInstance()} 还是 null。那类事挂到各 loader 的
     * 客户端启动钩子上。
     */
    public static void install() {
        ClientPayloadSink.companionList = ClientPayloadHandlers::handleCompanionList;
        ClientPayloadSink.consent = com.dwinovo.numen.api.client.consent.ConsentCards::accept;
        // 身体状态只给物品页画(ClientNumenState);大脑在服务端,直接读身体,不吃这一包
        ClientPayloadSink.state = p ->
                ClientNumenState.update(p.uuid(), com.dwinovo.numen.api.agent.request.BodySnapshot.of(p,
                        System.currentTimeMillis()));
        ClientPayloadSink.locations = ClientPayloadHandlers::handleLocations;
        ClientPayloadSink.uiAction = ClientPayloadHandlers::handleUiAction;
        com.dwinovo.numen.api.client.agent.RemoteCompanions.install();
    }

    private static void handleCompanionList(CompanionListPayload p) {
        com.dwinovo.numen.api.client.agent.RemoteCompanions.hello();
        java.util.Map<UUID, String> before = new java.util.HashMap<>();
        for (NumenRoster.Entry e : NumenRoster.instance().entries()) before.put(e.uuid(), e.name());

        java.util.List<NumenRoster.Entry> snapshot = new java.util.ArrayList<>();
        java.util.Set<UUID> onRoster = new java.util.LinkedHashSet<>();
        for (CompanionListPayload.Entry e : p.companions()) {
            snapshot.add(NumenRoster.toEntry(e.uuid(), e.name(), e.respawnInMs(), e.creative(), e.removedFor()));
            onRoster.add(e.uuid());
        }
        NumenRoster.instance().replaceAll(p.worldId(), snapshot);

        // 遣散的那只在服务端的家、会话里的位置都由服务端收;这里只放掉我们这头她的视图
        for (UUID gone : before.keySet()) {
            if (!onRoster.contains(gone)) {
                com.dwinovo.numen.api.client.agent.RemoteCompanions.forget(gone);
            }
        }
    }

    private static void handleLocations(NumenLocationsPayload p) {
        long now = System.currentTimeMillis();
        for (NumenLocationsPayload.Snapshot s : p.snapshots()) {
            ClientNumenLocations.update(s.uuid(), new ClientNumenLocations.Snapshot(
                    s.found(), s.loaded(), s.x(), s.y(), s.z(), s.dimension(), s.hp(), s.maxHp(), now));
        }
    }

    private static void handleUiAction(ClientUiActionPayload p) {
        switch (p.action()) {
            case OPEN_SETTINGS -> com.dwinovo.numen.api.client.screen.NumenScreen.openSettings();
            case DEBUG_TEXT_ON -> ChatDisplayModes.set(new RawMessageMode());
            case DEBUG_TEXT_OFF -> ChatDisplayModes.set(null);
        }
    }
}
