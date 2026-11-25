package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.agent.conversation.Mentions;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCustomChatCompletionsPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天栏切进会话:{@code /numen chat <会话>} 以后他在聊天栏打的字不广播到公屏,进这个会话(只有他自己看得到,带 {@code [会话名]}),
 * {@code /numen chat} 切回公屏,{@code /numen chat <会话> <话>} 只对会话说这一句。话怎么送到成员只有 {@link Hearing#say} 一处,
 * 这里只记"这位玩家切进了哪个会话的 id"和 {@code @} 的补全词(用原版的自定义聊天补全,词只从会话成员表来)。
 *
 * <p>线程:服务端主线程。
 */
public final class ChatBar {

    private static final String CONVERSATION = "conversation";

    /** 玩家 → 他聊天栏切进的会话 id;不在表里就是公屏。 */
    private static final Map<UUID, String> ROUTES = new ConcurrentHashMap<>();

    static {
        ServerLifecycle.onStopped(ROUTES::clear);
    }

    private ChatBar() {}

    /** {@code /numen chat …}。 */
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("chat")
                .executes(ctx -> {
                    leave(ctx.getSource().getPlayerOrException());
                    return 1;
                })
                .then(conversation().executes(ChatBar::enter)
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .suggests(ChatBar::mentions)
                                .executes(ChatBar::sayOnce)));
    }

    /**
     * 聊天栏里的一行。切进了会话的,这一行进会话、不再往公屏广播(返回 {@code true},各加载器的聊天事件据此取消广播);
     * 在公屏上的不碰(返回 {@code false})。会话没了的:这行话不能漏到公屏上去,所以吞下,并说清楚。
     */
    public static boolean take(ServerPlayer player, String message) {
        String id = ROUTES.get(player.getUUID());
        if (id == null) {
            return false;
        }
        Conversation conv = Conversations.of(player.server, player.getUUID()).find(id);
        if (conv == null) {
            ROUTES.remove(player.getUUID());
            player.sendSystemMessage(Component.translatable(Keys.COMMAND_CHAT_GONE_UNSENT));
            return true;
        }
        say(player, conv, message);
        return true;
    }

    /** 这一行以 {@code [会话名] <他> 话} 回给他自己看,再照会话的规则送出去。 */
    private static void say(ServerPlayer player, Conversation conv, String text) {
        player.sendSystemMessage(Component.literal("[" + Conversations.of(player.server, player.getUUID()).titleOf(conv)
                + "] <" + player.getGameProfile().getName() + "> " + text));
        String problem = Hearing.say(player.server, player, conv, text);
        if (problem != null) {
            player.sendSystemMessage(Component.translatable(Keys.COMMAND_CHAT_NOT_SENT, problem));
        }
    }

    /** 这位玩家的会话变了(拉人、移人、改名、解散):他切进的会话成员变了,补全词跟着换;会话没了就切回公屏。 */
    public static void refresh(MinecraftServer server, UUID owner) {
        String id = ROUTES.get(owner);
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (id == null || player == null) {
            return;
        }
        Conversation conv = Conversations.of(server, owner).find(id);
        if (conv == null) {
            ROUTES.remove(owner);
            send(player, List.of());
            player.sendSystemMessage(Component.translatable(Keys.COMMAND_CHAT_GONE));
            return;
        }
        complete(player, conv);
    }

    /** 这位玩家下线了。 */
    public static void forget(UUID player) {
        ROUTES.remove(player);
    }

    /** 这位玩家聊天栏切进的会话 id;在公屏上是 {@code null}。 */
    public static String routeOf(UUID player) {
        return ROUTES.get(player);
    }

    private static int enter(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Conversation conv = resolve(ctx, player);
        if (conv == null) {
            return 0;
        }
        ROUTES.put(player.getUUID(), conv.id());
        complete(player, conv);
        player.sendSystemMessage(Component.translatable(Keys.COMMAND_CHAT_ENTERED,
                Conversations.of(player.server, player.getUUID()).titleOf(conv)));
        return 1;
    }

    private static void leave(ServerPlayer player) {
        if (ROUTES.remove(player.getUUID()) != null) {
            send(player, List.of());
        }
        player.sendSystemMessage(Component.translatable(Keys.COMMAND_CHAT_LEFT));
    }

    private static int sayOnce(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Conversation conv = resolve(ctx, player);
        if (conv == null) {
            return 0;
        }
        say(player, conv, StringArgumentType.getString(ctx, "text"));
        return 1;
    }

    // ---- 参数与补全 ----

    private static RequiredArgumentBuilder<CommandSourceStack, String> conversation() {
        return Commands.argument(CONVERSATION, StringArgumentType.string()).suggests(ChatBar::conversations);
    }

    private static CompletableFuture<Suggestions> conversations(CommandContext<CommandSourceStack> ctx,
                                                                SuggestionsBuilder builder) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return builder.buildFuture();
        }
        Conversations rooms = Conversations.of(player.server, player.getUUID());
        return SharedSuggestionProvider.suggest(rooms.all().stream()
                .map(conv -> StringArgumentType.escapeIfRequired(rooms.titleOf(conv))), builder);
    }

    /** 话里正在打的那个词是 {@code @} 开头的,补成会话成员的名字:补全词只从会话的成员表来。 */
    private static CompletableFuture<Suggestions> mentions(CommandContext<CommandSourceStack> ctx,
                                                           SuggestionsBuilder builder) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return builder.buildFuture();
        }
        Conversations rooms = Conversations.of(player.server, player.getUUID());
        List<Conversation> found = rooms.matching(StringArgumentType.getString(ctx, CONVERSATION));
        if (found.size() != 1) {
            return builder.buildFuture();
        }
        SuggestionsBuilder word = builder.createOffset(builder.getStart() + builder.getRemaining().lastIndexOf(' ') + 1);
        return SharedSuggestionProvider.suggest(Mentions.words(rooms.named(found.get(0))), word);
    }

    /** 他指的会话;没有或指不准就说出来,返回 {@code null}。 */
    private static Conversation resolve(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        String given = StringArgumentType.getString(ctx, CONVERSATION);
        List<Conversation> found = Conversations.of(player.server, player.getUUID()).matching(given);
        if (found.size() == 1) {
            return found.get(0);
        }
        ctx.getSource().sendFailure(found.isEmpty()
                ? Component.translatable(Keys.COMMAND_GROUP_NO_CONVERSATION, given)
                : Component.translatable(Keys.COMMAND_GROUP_AMBIGUOUS, given,
                        String.join(", ", found.stream().map(Conversation::id).toList())));
        return null;
    }

    private static void complete(ServerPlayer player, Conversation conv) {
        send(player, Mentions.words(Conversations.of(player.server, player.getUUID()).named(conv)));
    }

    private static void send(ServerPlayer player, List<String> words) {
        player.connection.send(new ClientboundCustomChatCompletionsPacket(
                ClientboundCustomChatCompletionsPacket.Action.SET, words));
    }
}
