package com.dwinovo.numen.client.chat;

import com.dwinovo.numen.client.agent.NumenRoster;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCustomChatCompletionsPacket;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把 {@code @名字} 注册进原生的自定义聊天补全:聊天框里敲 {@code @} 就有
 * 候选弹窗,Tab 采纳——和服务器下发玩家名/表情补全是同一套原生管线,
 * 零自绘 UI。花名册每次变动(登录快照/召唤/死亡)后调 {@link #sync()}
 * 做差量增删。客户端主线程专用。
 */
public final class CompanionCompletions {

    /** 已注册进补全器的词条(带 @ 前缀),差量同步的基准。 */
    private static final Set<String> REGISTERED = new HashSet<>();

    private CompanionCompletions() {}

    /** 以当前花名册为准,对补全器做差量增删。 */
    public static void sync() {
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        if (conn == null) {
            return;
        }
        Set<String> want = new HashSet<>();
        for (NumenRoster.Entry entry : NumenRoster.instance().entries()) {
            if (entry.name() != null && !entry.name().isBlank()) {
                want.add("@" + entry.name());
            }
        }
        List<String> stale = new ArrayList<>();
        for (String had : REGISTERED) {
            if (!want.contains(had)) stale.add(had);
        }
        List<String> fresh = new ArrayList<>();
        for (String need : want) {
            if (!REGISTERED.contains(need)) fresh.add(need);
        }
        if (!stale.isEmpty()) {
            conn.getSuggestionsProvider().modifyCustomCompletions(
                    ClientboundCustomChatCompletionsPacket.Action.REMOVE, stale);
        }
        if (!fresh.isEmpty()) {
            conn.getSuggestionsProvider().modifyCustomCompletions(
                    ClientboundCustomChatCompletionsPacket.Action.ADD, fresh);
        }
        REGISTERED.clear();
        REGISTERED.addAll(want);
    }

    /** 断线清基准(服务端那份补全器随连接一起没了)。 */
    public static void clear() {
        REGISTERED.clear();
    }
}
