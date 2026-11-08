package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.agent.conversation.Mentions;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCustomChatCompletionsPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一位玩家的聊天栏此刻通向哪里:公屏,或者他的一个会话({@code /numen chat <会话>} 切进去,{@code /numen chat} 切回来)。
 * 切进会话以后聊天栏打的字不广播,进这个会话——只有他自己看得到,带 {@code [会话名]};会话里 {@code @} 谁谁醒、
 * 不 {@code @} 全体醒,由服务端照面板同一套规则解析({@link Conversations#say})。这是面板左栏点中一个会话的另一种按法,
 * 成员表还是 {@link Conversations} 那一张,这里只记"这位玩家切进了哪个会话的 id"。
 *
 * <p>{@code @} 要有补全:切进会话时,服务端用原版的自定义聊天补全({@link ClientboundCustomChatCompletionsPacket})把
 * {@code @<成员名>} 设成他的补全词,切回或成员变了跟着换。补全词只从会话的成员表来({@link Conversations#named})。
 *
 * <p>线程:服务端主线程。
 */
public final class ChatRoutes {

    /** 玩家 → 他聊天栏切进的会话 id;不在表里就是公屏。 */
    private static final Map<UUID, String> ROUTES = new ConcurrentHashMap<>();

    static {
        ServerLifecycle.onStopped(ROUTES::clear);
    }

    private ChatRoutes() {}

    /**
     * 这位玩家此刻在聊天栏里打的字归不归 Numen(他切进了会话)。只读判断,任何线程都能调:聊天事件在异步线程上的平台
     * (Paper)要当场回答"取消不取消",接下来的处理交回主线程({@link #deliver})。
     */
    public static boolean routed(UUID player) {
        return ROUTES.containsKey(player);
    }

    /**
     * 聊天栏里的一行。切进了会话的,这一行进会话、不再往公屏广播(返回 {@code true},各加载器的聊天事件据此取消广播);
     * 在公屏上的不碰(返回 {@code false})。主线程。
     */
    public static boolean take(ServerPlayer player, String message) {
        if (!routed(player.getUUID())) {
            return false;
        }
        deliver(player, message);
        return true;
    }

    /**
     * 把一行送进他切进的会话——对 {@link #routed} 答过"是"的那一行。主线程。判定与送达之间他可能已经切回公屏或会话没了:
     * 这行话不能漏到公屏上去,所以吞下,并说清楚。
     */
    public static void deliver(ServerPlayer player, String message) {
        String id = ROUTES.get(player.getUUID());
        Conversation conv = id == null ? null : Conversations.of(player.server, player.getUUID()).find(id);
        if (conv == null) {
            leave(player);
            player.sendSystemMessage(Component.translatable(Keys.COMMAND_CHAT_GONE_UNSENT));
            return;
        }
        say(player, conv, message);
    }

    /** 在这个会话里说一句:先把这一行以 {@code [会话名] <他> 话} 回给他自己看,再照会话的规则送出去。 */
    public static void say(ServerPlayer player, Conversation conv, String text) {
        Conversations rooms = Conversations.of(player.server, player.getUUID());
        player.sendSystemMessage(Component.literal("[" + rooms.titleOf(conv) + "] <"
                + player.getGameProfile().getName() + "> " + text));
        OwnerInputs.Applied applied = OwnerInputs.handle(player.server, player.getUUID(),
                new OwnerInput.Say(conv.id(), text), Venue.CHAT);
        if (applied.problem() != null) {
            player.sendSystemMessage(Component.translatable(Keys.COMMAND_CHAT_NOT_SENT, applied.problem()));
        }
    }

    /** 聊天栏切进这个会话,补全跟着设上。 */
    public static void enter(ServerPlayer player, Conversation conv) {
        ROUTES.put(player.getUUID(), conv.id());
        complete(player, conv);
        player.sendSystemMessage(Component.translatable(Keys.COMMAND_CHAT_ENTERED,
                Conversations.of(player.server, player.getUUID()).titleOf(conv)));
    }

    /** 聊天栏切回公屏,会话的补全词收掉。 */
    public static void leave(ServerPlayer player) {
        if (ROUTES.remove(player.getUUID()) != null) {
            send(player, List.of());
        }
    }

    /** 切回公屏,并告诉他一声。 */
    public static void leaveAndTell(ServerPlayer player) {
        leave(player);
        player.sendSystemMessage(Component.translatable(Keys.COMMAND_CHAT_LEFT));
    }

    /**
     * 这位主人的会话变了(拉人、移人、改名、解散、有同伴被遣散):他切进的会话成员变了,补全词跟着换;会话没了就切回公屏。
     */
    public static void refresh(MinecraftServer server, UUID owner) {
        String id = ROUTES.get(owner);
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (id == null || player == null) {
            return;
        }
        Conversation conv = Conversations.of(server, owner).find(id);
        if (conv == null) {
            leave(player);
            player.sendSystemMessage(Component.translatable(Keys.COMMAND_CHAT_GONE));
            return;
        }
        complete(player, conv);
    }

    /** 这位玩家下线了。 */
    static void forget(UUID player) {
        ROUTES.remove(player);
    }

    /** 这位玩家聊天栏切进的会话 id;在公屏上是 {@code null}。 */
    public static String routeOf(UUID player) {
        return ROUTES.get(player);
    }

    private static void complete(ServerPlayer player, Conversation conv) {
        send(player, Mentions.words(
                Conversations.of(player.server, player.getUUID()).named(conv)));
    }

    private static void send(ServerPlayer player, List<String> words) {
        player.connection.send(new ClientboundCustomChatCompletionsPacket(
                ClientboundCustomChatCompletionsPacket.Action.SET, words));
    }
}
