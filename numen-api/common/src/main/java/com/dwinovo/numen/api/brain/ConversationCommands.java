package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.agent.conversation.Mentions;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.entity.CompanionRegistry;
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
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 会话的命令:面板左栏操作的另一种按法,给没装客户端的主人用(装了的也能用)。成员表还是 {@link Conversations} 那一张,
 * 改它的动作同走 {@link OwnerInputs}(和面板上的操作是同一个入口,归属检查、落盘、推给面板都在那里),这里只把参数
 * 读成会话和同伴、把结果说给他听。
 *
 * <pre>
 *   /numen group invite &lt;会话&gt; &lt;她&gt;    把她拉进会话(拉进第二只就成了群)
 *   /numen group remove &lt;会话&gt; &lt;她&gt;    把她移出会话
 *   /numen group rename &lt;会话&gt; &lt;名字&gt;  给会话起名
 *   /numen group dissolve &lt;会话&gt;         解散
 *   /numen chat &lt;会话&gt;                  聊天栏切进会话({@link ChatRoutes})
 *   /numen chat                         切回公屏
 *   /numen chat &lt;会话&gt; &lt;话&gt;            只对会话说这一句
 * </pre>
 *
 * <p>会话写它的名字(单聊就是她的名字)或 id。名字带空格或不是英文字母的要加引号。
 */
public final class ConversationCommands {

    private static final String CONVERSATION = "conversation";
    private static final String COMPANION = "companion";

    private ConversationCommands() {}

    /** {@code /numen group …}。 */
    public static LiteralArgumentBuilder<CommandSourceStack> group() {
        return Commands.literal("group")
                .then(Commands.literal("invite").then(conversation().then(CompanionArgument.of(COMPANION)
                        .executes(ctx -> pull(ctx, true)))))
                .then(Commands.literal("remove").then(conversation().then(CompanionArgument.of(COMPANION)
                        .executes(ctx -> pull(ctx, false)))))
                .then(Commands.literal("rename").then(conversation().then(Commands.argument("name",
                        StringArgumentType.greedyString()).executes(ConversationCommands::rename))))
                .then(Commands.literal("dissolve").then(conversation().executes(ConversationCommands::dissolve)));
    }

    /** {@code /numen chat …}。 */
    public static LiteralArgumentBuilder<CommandSourceStack> chat() {
        return Commands.literal("chat")
                .executes(ConversationCommands::leave)
                .then(conversation().executes(ConversationCommands::enter)
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .suggests(ConversationCommands::mentions)
                                .executes(ConversationCommands::sayOnce)));
    }

    // ---- 参数 ----

    private static RequiredArgumentBuilder<CommandSourceStack, String> conversation() {
        return Commands.argument(CONVERSATION, StringArgumentType.string())
                .suggests(ConversationCommands::conversations);
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
        List<Conversation> found = rooms.matching(conversationArgument(ctx));
        if (found.size() != 1) {
            return builder.buildFuture();
        }
        SuggestionsBuilder word = builder.createOffset(builder.getStart() + builder.getRemaining().lastIndexOf(' ') + 1);
        return SharedSuggestionProvider.suggest(Mentions.words(rooms.named(found.get(0))), word);
    }

    // ---- 会话本身 ----

    private static int pull(CommandContext<CommandSourceStack> ctx, boolean invite) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        Conversation conv = resolve(ctx, owner);
        Map.Entry<UUID, CompanionRegistry.Entry> her = conv == null ? null : CompanionArgument.resolve(ctx, COMPANION, owner);
        if (her == null) {
            return 0;
        }
        String title = Conversations.of(owner.server, owner.getUUID()).titleOf(conv);
        OwnerInput input = invite ? new OwnerInput.Pull(conv.id(), her.getKey(), null)
                : new OwnerInput.Drop(conv.id(), her.getKey());
        return done(ctx, owner, input, Component.translatable(invite ? Keys.COMMAND_GROUP_INVITED
                : Keys.COMMAND_GROUP_REMOVED, her.getValue().name(), title));
    }

    private static int rename(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        Conversation conv = resolve(ctx, owner);
        if (conv == null) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name").strip();
        return done(ctx, owner, new OwnerInput.Rename(conv.id(), name),
                Component.translatable(Keys.COMMAND_GROUP_RENAMED, Conversations.of(owner.server, owner.getUUID())
                        .titleOf(conv), name));
    }

    private static int dissolve(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        Conversation conv = resolve(ctx, owner);
        if (conv == null) {
            return 0;
        }
        return done(ctx, owner, new OwnerInput.Dissolve(conv.id()), Component.translatable(Keys.COMMAND_GROUP_DISSOLVED,
                Conversations.of(owner.server, owner.getUUID()).titleOf(conv)));
    }

    /** 办这一件事:办成了说 {@code success},没办成把原因说出来。 */
    private static int done(CommandContext<CommandSourceStack> ctx, ServerPlayer owner, OwnerInput input,
                            Component success) {
        OwnerInputs.Applied applied = OwnerInputs.handle(owner.server, owner.getUUID(), input, Venue.PANEL);
        if (applied.problem() != null) {
            ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_GROUP_FAILED, applied.problem()));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> success, false);
        return 1;
    }

    // ---- 聊天栏 ----

    private static int enter(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        Conversation conv = resolve(ctx, owner);
        if (conv == null) {
            return 0;
        }
        ChatRoutes.enter(owner, conv);
        return 1;
    }

    private static int leave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ChatRoutes.leaveAndTell(ctx.getSource().getPlayerOrException());
        return 1;
    }

    private static int sayOnce(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        Conversation conv = resolve(ctx, owner);
        if (conv == null) {
            return 0;
        }
        ChatRoutes.say(owner, conv, StringArgumentType.getString(ctx, "text"));
        return 1;
    }

    // ---- 把参数读成会话和同伴 ----

    /**
     * 他写的会话。补全拿到的是根上的上下文:指令树经重定向挂上来的(Paper 把插件指令登记成重定向到真身的别名),
     * 重定向之后解出的参数在子上下文里,根上读不到;执行时拿到的已经是最里头那一层。所以从最里头一层读,两种来路同一个答案。
     */
    static String conversationArgument(CommandContext<CommandSourceStack> ctx) {
        CommandContext<CommandSourceStack> innermost = ctx;
        while (innermost.getChild() != null) {
            innermost = innermost.getChild();
        }
        return StringArgumentType.getString(innermost, CONVERSATION);
    }

    /** 他指的会话;没有或指不准就说出来,返回 {@code null}。 */
    private static Conversation resolve(CommandContext<CommandSourceStack> ctx, ServerPlayer owner) {
        String given = conversationArgument(ctx);
        List<Conversation> found = Conversations.of(owner.server, owner.getUUID()).matching(given);
        if (found.size() == 1) {
            return found.get(0);
        }
        ctx.getSource().sendFailure(found.isEmpty()
                ? Component.translatable(Keys.COMMAND_GROUP_NO_CONVERSATION, given)
                : Component.translatable(Keys.COMMAND_GROUP_AMBIGUOUS, given,
                        String.join("、", found.stream().map(Conversation::id).toList())));
        return null;
    }
}
