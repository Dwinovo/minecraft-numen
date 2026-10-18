package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.config.Authority;
import com.dwinovo.numen.api.agent.loop.LoopStatus;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.network.payload.ConsentRequestPayload;
import com.dwinovo.numen.api.permission.ConsentDesk;
import com.dwinovo.numen.api.network.payload.ConversationsPayload;
import com.dwinovo.numen.api.network.payload.FeedPayload;
import com.dwinovo.numen.api.network.payload.HelloAckPayload;
import com.dwinovo.numen.api.network.payload.HistoryPagePayload;
import com.dwinovo.numen.api.network.payload.LibraryPayload;
import com.dwinovo.numen.api.permission.ConsentChat;
import com.dwinovo.numen.api.text.ServerLang;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
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
        library(player, kind, op, json, 0);
    }

    /** 同上;{@code request} 是客户端起的请求号,原样带在答复里(0 = 他不等答复)。 */
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
        Libraries libraries = Libraries.of(server);
        Libraries.Result result = libraries.handle(player.getUUID(), Authority.isAdmin(player), kind, op, body);
        // 还要等一会儿才有结局的请求(连通性检测):现在送的只是"在试"的一张表,不是答复;答复是结局到了的那一张,带请求号
        long answerNow = result.later() == null ? request : 0;
        NumenNetwork.sendToPlayer(player, new LibraryPayload(kind, result.entries(),
                result.problem() == null ? "" : result.problem(), answerNow));
        if (result.later() != null) {
            result.later().thenAccept(entries -> server.execute(
                    () -> NumenNetwork.sendToPlayer(player, new LibraryPayload(kind, entries, "", request))));
        }
        if (result.shared()) {
            share(server, player, kind);
        }
    }

    /**
     * 一种库变了:在线、装着客户端的玩家各收到他们此刻看得到的新表({@code except} 已经收到了答复)。
     * 接进来的 MCP 服务器连上、断开、等授权都是自己变的,也经这里推。
     */
    public static void share(MinecraftServer server, ServerPlayer except, String kind) {
        Libraries libraries = Libraries.of(server);
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other != except && Presence.hasClient(other.getUUID())) {
                NumenNetwork.sendToPlayer(other, new LibraryPayload(kind,
                        libraries.view(other.getUUID(), Authority.isAdmin(other), kind), ""));
            }
        }
    }

    /** 一种库只在这位玩家看得到的那一份变了(他自己的 MCP 连接连上、断开):推给他。 */
    public static void pushTo(MinecraftServer server, UUID owner, String kind) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player != null && Presence.hasClient(owner)) {
            NumenNetwork.sendToPlayer(player, new LibraryPayload(kind,
                    Libraries.of(server).view(owner, Authority.isAdmin(player), kind), ""));
        }
    }

    /**
     * 她的征询交到主人面前,或者撤回了(撤回的 {@code request} 是 {@link ConsentRequestPayload#none})。装了客户端的,推给他的
     * 客户端画答复框;没装的,发给他一条带可点按钮的聊天消息,撤回时说一声为什么。他答复的唯一入口不变:{@code ConsentDesk.reply}。
     */
    public static void consent(ServerPlayer owner, String companion, ConsentRequestPayload request) {
        if (Presence.hasClient(owner.getUUID())) {
            NumenNetwork.sendToPlayer(owner, request);
        } else if (!request.withdrawn()) {
            owner.sendSystemMessage(ConsentChat.ask(companion, request));
        } else if (request.withdrawnBecause() != null) {
            owner.sendSystemMessage(ConsentChat.withdrawn(companion, request.withdrawnBecause()));
        }
    }

    /**
     * 她的记忆导出好了(或没成):装了客户端的,打成的 zip 按段下行({@link Exports})交给他存成文件;读不出来、他前一份还在路上、他没装客户端,
     * 都如实说给他听。
     */
    public static void exported(MinecraftServer server, UUID owner, UUID companion, byte[] zip, Throwable failure) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companion);
        if (player == null || entry == null) {
            return;
        }
        if (failure != null) {
            Constants.LOG.warn("[numen-entity#{}] 导出记忆失败: {}", companion, failure.toString());
            player.sendSystemMessage(Component.translatable(com.dwinovo.numen.api.data.ModLanguageData.Keys.EXPORT_FAILED,
                    entry.name()));
        } else if (!Presence.hasClient(owner)) {
            player.sendSystemMessage(Component.translatable(com.dwinovo.numen.api.data.ModLanguageData.Keys.EXPORT_NEEDS_CLIENT));
        } else if (!Exports.SERVER.start(owner, companion, entry.name(), zip)) {
            player.sendSystemMessage(Component.translatable(com.dwinovo.numen.api.data.ModLanguageData.Keys.EXPORT_BUSY));
        }
    }

    /** 这位主人那一头:他的客户端在不在、事件怎么推给他、她的话怎么交到他手里。 */
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
            public void said(UUID companion, String said, Venue venue, String conversation) {
                ServerPlayer player = server.getPlayerList().getPlayer(owner);
                CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companion);
                if (player == null || entry == null || venue == Venue.PANEL && hasClient()) {
                    return;
                }
                player.sendSystemMessage(Voice.line(venue, entry.name(), conversation, said));
            }

            @Override
            public void activities(Map<UUID, LoopStatus.Doing> doing) {
                ServerPlayer player = server.getPlayerList().getPlayer(owner);
                if (player == null) {
                    return;
                }
                ServerLang lang = ServerLang.of(player.clientInformation().language());
                MutableComponent bar = Component.empty();
                for (Map.Entry<UUID, LoopStatus.Doing> each : doing.entrySet()) {
                    CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(each.getKey());
                    if (entry != null) {
                        bar.append(bar.getSiblings().isEmpty() ? "" : " | ").append(entry.name() + ": ")
                                .append(DoingText.of(each.getValue(), lang::has));
                    }
                }
                if (!bar.getSiblings().isEmpty()) {
                    player.displayClientMessage(bar, true);
                }
            }
        };
    }
}
