package com.dwinovo.numen.api.owner;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.config.Authority;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.network.payload.ConsentRequestPayload;
import com.dwinovo.numen.api.permission.ConsentDesk;
import com.dwinovo.numen.api.network.payload.ConversationsPayload;
import com.dwinovo.numen.api.network.payload.HelloAckPayload;
import com.dwinovo.numen.api.network.payload.LibraryPayload;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import com.dwinovo.numen.api.talk.Conversations;

/**
 * 服务端对主人客户端的答复与推送:报到的回答和补课、会话列表、征询。包怎么编、往哪发只在这一处。
 * 线程:服务端主线程。
 */
public final class Replies {

    private Replies() {}

    /**
     * 客户端报到的完整一步:版本与它提供的联动一起(单测、不经网络的场景替客户端走完报到)。网络上这是先后两包,各由
     * {@link #reported}、{@link #ready} 答一半。
     */
    public static void hello(ServerPlayer player, int protocol, java.util.Set<String> provided) {
        reported(player, protocol);
        ready(player, provided);
    }

    /** 同 {@link #hello(ServerPlayer, int, java.util.Set)},客户端不提供任何目标模组只在客户端的联动。 */
    public static void hello(ServerPlayer player, int protocol) {
        hello(player, protocol, java.util.Set.of());
    }

    /**
     * 客户端报了协议版本({@code HelloPayload})。版本对不上就什么也不回:开场那包已经告诉过它服务端的版本,再发的它都读不懂。对得上的
     * 接着等它报能力({@link #ready}),到齐才回答。
     */
    public static void reported(ServerPlayer player, int protocol) {
        if (!Presence.reported(player.getUUID(), protocol)) {
            Constants.LOG.info("[numen-net] client of {} speaks protocol {}, this server {}: not served",
                    player.getUUID(), protocol, Presence.PROTOCOL);
        }
    }

    /**
     * 客户端报了它提供哪些联动({@code ClientCapsPayload}),报到齐了:回答是否管理员、是否房主,推名册,补给他的会话列表。
     * 没有先报过对得上的版本就什么也不做。
     */
    public static void ready(ServerPlayer player, java.util.Set<String> provided) {
        if (!Presence.ready(player.getUUID(), provided)) {
            return;
        }
        NumenNetwork.sendToPlayer(player, new HelloAckPayload());
        Companions.syncRosterToOwner(player.server, player);
        conversations(player.server, player.getUUID());
        ConsentDesk.resendTo(player);
    }

    /** 这位主人落过盘的会话(成员表与名字),整份。"就他俩"不用推:客户端按名册自己有。 */
    public static void conversations(MinecraftServer server, UUID owner) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player == null || !Presence.hasClient(owner)) {
            return;
        }
        JsonArray saved = new JsonArray();
        Conversations rooms = Conversations.of(server, owner);
        for (Conversation conv : rooms.list()) {
            saved.add(conv.toJson());
        }
        NumenNetwork.sendToPlayer(player, new ConversationsPayload(saved.toString()));
    }

    /**
     * 读写一种库:谁能做什么由 {@link Libraries} 判,答复是这位玩家此刻看得到的整表(办不成附原因)。插件的开关改成了,在线的其他玩家
     * 也各收到他们看得到的新表。
     */
    public static void library(ServerPlayer player, String kind, String op, String json, long request) {
        JsonObject body = new JsonObject();
        try {
            if (JsonParser.parseString(json) instanceof JsonObject parsed) {
                body = parsed;
            }
        } catch (RuntimeException unreadable) {
            // 读不懂的请求体当空:各库按"缺字段"拒绝
        }
        MinecraftServer server = player.server;
        Libraries.Result result = Libraries.of(server).handle(player.getUUID(), Authority.isAdmin(player), kind, op, body);
        NumenNetwork.sendToPlayer(player, new LibraryPayload(kind, result.entries(),
                result.problem() == null ? "" : result.problem(), request));
        if (result.shared()) {
            share(server, player, kind);
        }
    }

    /** 一种库变了:在线、装着客户端的玩家各收到他们此刻看得到的新表({@code except} 已经收到了答复)。 */
    public static void share(MinecraftServer server, ServerPlayer except, String kind) {
        Libraries libraries = Libraries.of(server);
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other != except && Presence.hasClient(other.getUUID())) {
                NumenNetwork.sendToPlayer(other, new LibraryPayload(kind,
                        libraries.view(other.getUUID(), Authority.isAdmin(other), kind), ""));
            }
        }
    }

    /** 一种库只在这位玩家看得到的那一份变了(皮肤的签名队、权限的规则表):推给他。 */
    public static void pushTo(MinecraftServer server, UUID owner, String kind) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player != null && Presence.hasClient(owner)) {
            NumenNetwork.sendToPlayer(player, new LibraryPayload(kind,
                    Libraries.of(server).view(owner, Authority.isAdmin(player), kind), ""));
        }
    }

    /** 这位主人的权限表变了:推给他的面板。 */
    public static void permission(MinecraftServer server, UUID owner) {
        pushTo(server, owner, Libraries.PERMISSION);
    }

    /** 她的征询交到主人面前,或者撤回了(撤回的 {@code request} 是 {@link ConsentRequestPayload#none}):推给他的客户端画答复框。他答复的唯一入口:{@code ConsentDesk.reply}。 */
    public static void consent(ServerPlayer owner, String companion, ConsentRequestPayload request) {
        NumenNetwork.sendToPlayer(owner, request);
    }
}
