package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * {@code /numen token}:主人给自己发、列、作废远程端点的令牌。令牌只认发它的人名下的同伴,
 * 命令没有"替别人发"的写法,所以不需要另一套权限判断。
 *
 * <pre>
 *   /numen token create &lt;brain|channel&gt; [all|&lt;同伴&gt;] [&lt;天数&gt;]   发一张;明文只显示这一次
 *   /numen token list                                         列出我的令牌
 *   /numen token revoke &lt;编号&gt;                                作废一张,用它的会话随即断开
 * </pre>
 */
public final class TokenCommands {

    private static final String ALL = "all";
    /** 聊天栏上显示令牌明文的前几个字符。 */
    private static final int SHOWN = McpTokens.PREFIX.length() + 6;

    private TokenCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        var create = Commands.literal("create");
        for (McpToken.Role role : McpToken.Role.values()) {
            create.then(Commands.literal(role.name().toLowerCase(Locale.ROOT))
                    .executes(ctx -> create(ctx, role, ALL, 0))
                    .then(Commands.argument("scope", StringArgumentType.string())
                            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                    scopeNames(ctx.getSource()), builder))
                            .executes(ctx -> create(ctx, role, StringArgumentType.getString(ctx, "scope"), 0))
                            .then(Commands.argument("days", IntegerArgumentType.integer(1))
                                    .executes(ctx -> create(ctx, role, StringArgumentType.getString(ctx, "scope"),
                                            IntegerArgumentType.getInteger(ctx, "days"))))));
        }
        return Commands.literal("token")
                .then(create)
                .then(Commands.literal("list").executes(TokenCommands::list))
                .then(Commands.literal("revoke")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        mine(ctx.getSource()).stream().map(McpToken::id), builder))
                                .executes(TokenCommands::revoke)));
    }

    private static java.util.List<String> scopeNames(CommandSourceStack source) {
        java.util.List<String> names = new java.util.ArrayList<>();
        names.add(ALL);
        if (source.getEntity() instanceof ServerPlayer player) {
            for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(source.getServer())
                    .ownedBy(player.getUUID())) {
                names.add(StringArgumentType.escapeIfRequired(e.getValue().name()));
            }
        }
        return names;
    }

    private static java.util.List<McpToken> mine(CommandSourceStack source) {
        McpTokens tokens = McpMode.instance().tokens();
        return source.getEntity() instanceof ServerPlayer player && tokens != null
                ? tokens.ownedBy(player.getUUID()) : java.util.List.of();
    }

    /** 令牌库;这一局读不了就如实告诉他文件在哪、为什么,返回 null。 */
    private static McpTokens usable(CommandContext<CommandSourceStack> ctx) {
        McpTokens tokens = McpMode.instance().tokens();
        var broken = tokens.unavailable();
        if (broken.isPresent()) {
            ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_TOKEN_UNAVAILABLE,
                    broken.get().file().toString(), broken.get().reason()));
            return null;
        }
        return tokens;
    }

    private static int create(CommandContext<CommandSourceStack> ctx, McpToken.Role role, String scopeName, int days)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        McpTokens tokens = usable(ctx);
        if (tokens == null) {
            return 0;
        }
        McpToken.Scope scope = McpToken.Scope.ALL;
        if (!ALL.equals(scopeName)) {
            Set<UUID> only = new HashSet<>();
            for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(ctx.getSource().getServer())
                    .ownedBy(owner.getUUID())) {
                if (e.getValue().name().equals(scopeName)) {
                    only.add(e.getKey());
                }
            }
            if (only.isEmpty()) {
                ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_TOKEN_NO_COMPANION, scopeName));
                return 0;
            }
            scope = McpToken.Scope.of(only);
        }
        McpTokens.Issued issued = tokens.issue(owner.getUUID(), role, scope, "",
                days == 0 ? 0 : TimeUnit.DAYS.toMillis(days));
        ctx.getSource().sendSuccess(() -> Component.translatable(Keys.COMMAND_TOKEN_ISSUED, issued.token().id(),
                role.name().toLowerCase(Locale.ROOT), describe(issued.token())), false);
        // 明文令牌点一下复制;只发给发令牌的人,不广播给其他管理员。聊天栏上显示的文字会被客户端写进 latest.log,
        // 所以只显示开头,完整明文只在点击事件里
        ctx.getSource().sendSuccess(() -> Component.literal(issued.secret().substring(0, SHOWN) + "…").withStyle(Style.EMPTY
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, issued.secret()))
                .withUnderlined(true)), false);
        if (!McpMode.instance().enabled()) {
            ctx.getSource().sendSuccess(() -> Component.translatable(Keys.COMMAND_TOKEN_ENDPOINT_OFF), false);
        }
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ctx.getSource().getPlayerOrException();
        if (usable(ctx) == null) {
            return 0;
        }
        var tokens = mine(ctx.getSource());
        if (tokens.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable(Keys.COMMAND_TOKEN_LIST_NONE), false);
            return 0;
        }
        long now = System.currentTimeMillis();
        for (McpToken t : tokens) {
            ctx.getSource().sendSuccess(() -> Component.translatable(Keys.COMMAND_TOKEN_ROW, t.id(),
                    t.role().name().toLowerCase(Locale.ROOT), describe(t),
                    t.expiresAt() == 0 ? "never expires" : t.expired(now) ? "expired"
                            : "expires in " + TimeUnit.MILLISECONDS.toHours(t.expiresAt() - now) + " h"), false);
        }
        return tokens.size();
    }

    private static int revoke(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        McpTokens tokens = usable(ctx);
        if (tokens == null) {
            return 0;
        }
        String id = StringArgumentType.getString(ctx, "id");
        if (!tokens.revoke(owner.getUUID(), id)) {
            ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_TOKEN_NOT_FOUND, id));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(Keys.COMMAND_TOKEN_REVOKED, id), false);
        return 1;
    }

    private static String describe(McpToken token) {
        return token.scope().all() ? "all companions" : token.scope().only().size() + " companion(s)";
    }
}
