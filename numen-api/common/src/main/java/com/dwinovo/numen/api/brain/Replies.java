package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.config.Authority;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.network.payload.ConversationsPayload;
import com.dwinovo.numen.api.network.payload.FeedPayload;
import com.dwinovo.numen.api.network.payload.HelloAckPayload;
import com.dwinovo.numen.api.network.payload.HistoryPagePayload;
import com.dwinovo.numen.api.network.payload.LibraryPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * 服务端对主人客户端的答复与推送:报到的回答和补课、会话列表、一页历史、一刻的事件。包怎么编、往哪发只在这一处。
 * 线程:服务端主线程(历史页是在她们的写车道上算好之后发的,发包本身线程安全)。
 */
public final class Replies {

    private Replies() {}

    /** 客户端报到:回答协议版本与是否管理员;版本对得上的,补给他名下各大脑此刻的样子和会话列表。 */
    public static void hello(ServerPlayer player, int protocol) {
        boolean usable = Presence.hello(player.getUUID(), protocol);
        NumenNetwork.sendToPlayer(player, new HelloAckPayload(Presence.PROTOCOL, Authority.isAdmin(player)));
        if (!usable) {
            Constants.LOG.info("[numen-net] client of {} speaks protocol {}, this server {}: not served",
                    player.getUUID(), protocol, Presence.PROTOCOL);
            return;
        }
        CompanionLoops.sendSnapshots(player.getUUID(), link(player.server, player.getUUID()));
        conversations(player.server, player.getUUID());
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

    /** 一页历史:算好之后(在她们的写车道上,排在已交的写后面)发给他。 */
    public static void history(ServerPlayer player, long requestId, String conversation, String cursor, int limit) {
        MinecraftServer server = player.server;
        Conversations rooms = Conversations.of(server, player.getUUID());
        Conversation conv = rooms.find(conversation);
        if (conv == null) {
            NumenNetwork.sendToPlayer(player, new HistoryPagePayload(requestId, conversation, "[]", "", false));
            return;
        }
        History.page(rooms, conv, cursor, Math.max(1, Math.min(History.PAGE, limit))).thenAccept(page -> {
            NumenNetwork.sendToPlayer(player, HistoryPagePayload.of(requestId, conversation, page));
        });
    }

    /**
     * 读写一种库:谁能做什么由 {@link Libraries} 判,答复是这位玩家此刻看得到的整表(办不成附原因)。模型档案、服务器配置
     * 改成了,在线的其他玩家也各收到他们看得到的新表——档案选择器里的名单不能是旧的。
     */
    public static void library(ServerPlayer player, String kind, String op, String json) {
        JsonObject body = new JsonObject();
        try {
            if (JsonParser.parseString(json) instanceof JsonObject parsed) {
                body = parsed;
            }
        } catch (RuntimeException unreadable) {
            // 读不懂的请求体当空:各库按"缺字段"拒绝
        }
        MinecraftServer server = player.server;
        Libraries libraries = Libraries.of(server);
        Libraries.Result result = libraries.handle(player.getUUID(), Authority.isAdmin(player), kind, op, body);
        NumenNetwork.sendToPlayer(player, new LibraryPayload(kind, result.entries(),
                result.problem() == null ? "" : result.problem()));
        if (result.problem() == null && !Libraries.LIST.equals(op) && !Libraries.PERSONA.equals(kind)) {
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                if (other != player && Presence.hasClient(other.getUUID())) {
                    NumenNetwork.sendToPlayer(other, new LibraryPayload(kind,
                            libraries.view(other.getUUID(), Authority.isAdmin(other), kind), ""));
                }
            }
        }
    }

    /** 这位主人那一头:他的客户端在不在、事件怎么推给他、没有客户端时她的话怎么告诉他。 */
    public static CompanionLoops.Link link(MinecraftServer server, UUID owner) {
        return new CompanionLoops.Link() {
            @Override
            public boolean hasClient() {
                return Presence.hasClient(owner) && server.getPlayerList().getPlayer(owner) != null;
            }

            @Override
            public void send(UUID companion, List<Feed> events) {
                ServerPlayer player = server.getPlayerList().getPlayer(owner);
                if (player != null) {
                    NumenNetwork.sendToPlayer(player, FeedPayload.of(companion, events));
                }
            }

            @Override
            public void tell(UUID companion, String said) {
                ServerPlayer player = server.getPlayerList().getPlayer(owner);
                CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companion);
                if (player != null && entry != null) {
                    player.sendSystemMessage(Component.literal("<" + entry.name() + "> " + said));
                }
            }
        };
    }
}
