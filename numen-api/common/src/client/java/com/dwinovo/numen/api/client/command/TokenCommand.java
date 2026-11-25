package com.dwinovo.numen.api.client.command;

import com.dwinovo.numen.api.client.agent.CompanionView;
import com.dwinovo.numen.api.client.agent.NumenRoster;
import com.dwinovo.numen.api.client.seat.ClientSeats;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.mcp.server.McpToken;
import com.dwinovo.numen.mcp.server.McpTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * {@code /token}:主人给自己发、列、作废外部大脑端点的令牌。令牌库在这台电脑上,只认发它的人名下的同伴,
 * 命令没有"替别人发"的写法,所以不需要另一套权限判断。
 *
 * <pre>
 *   /token create &lt;brain|channel&gt; [all|&lt;同伴&gt;] [&lt;天数&gt;]   发一张;明文只显示这一次,同时复制到剪贴板
 *   /token list                                         列出我的令牌
 *   /token revoke &lt;编号&gt;                                作废一张,用它的会话随即断开
 * </pre>
 */
final class TokenCommand implements ChatCommand {

    private static final String ALL = "all";
    /** 聊天里显示令牌明文的前几个字符。 */
    private static final int SHOWN = McpTokens.PREFIX.length() + 6;

    @Override
    public String name() {
        return "token";
    }

    @Override
    public String description() {
        return I18n.get(Keys.CMD_TOKEN);
    }

    @Override
    public String argHint() {
        return I18n.get(Keys.CMD_TOKEN_ARGS);
    }

    @Override
    public String run(CompanionView loop, String args) {
        var external = ClientSeats.services().external();
        McpTokens tokens = external.tokens();
        var broken = tokens.unavailable();
        if (broken.isPresent()) {
            return I18n.get(Keys.COMMAND_TOKEN_UNAVAILABLE, broken.get().file().toString(), broken.get().reason());
        }
        UUID me = Minecraft.getInstance().player.getUUID();
        String[] words = args.isBlank() ? new String[0] : args.trim().split("\s+");
        if (words.length == 0) {
            return I18n.get(Keys.COMMAND_TOKEN_USAGE);
        }
        return switch (words[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(external, tokens, me, words);
            case "list" -> list(tokens, me);
            case "revoke" -> words.length < 2 ? I18n.get(Keys.COMMAND_TOKEN_USAGE) : revoke(tokens, me, words[1]);
            default -> I18n.get(Keys.COMMAND_TOKEN_USAGE);
        };
    }

    private static String create(com.dwinovo.numen.api.mcp.server.McpMode external, McpTokens tokens, UUID me, String[] words) {
        McpToken.Role role = null;
        for (McpToken.Role candidate : McpToken.Role.values()) {
            if (words.length > 1 && candidate.name().equalsIgnoreCase(words[1])) {
                role = candidate;
            }
        }
        if (role == null) {
            return I18n.get(Keys.COMMAND_TOKEN_USAGE);
        }
        String scopeName = words.length > 2 ? words[2] : ALL;
        int days = 0;
        if (words.length > 3) {
            try {
                days = Math.max(1, Integer.parseInt(words[3]));
            } catch (NumberFormatException notADay) {
                return I18n.get(Keys.COMMAND_TOKEN_USAGE);
            }
        }
        McpToken.Scope scope = McpToken.Scope.ALL;
        if (!ALL.equals(scopeName)) {
            Set<UUID> only = new HashSet<>();
            for (NumenRoster.Entry e : NumenRoster.instance().entries()) {
                if (e.createdBy(me) && e.name().equals(scopeName)) {
                    only.add(e.uuid());
                }
            }
            if (only.isEmpty()) {
                return I18n.get(Keys.COMMAND_TOKEN_NO_COMPANION, scopeName);
            }
            scope = McpToken.Scope.of(only);
        }
        McpTokens.Issued issued = tokens.issue(me, role, scope, "", days == 0 ? 0 : TimeUnit.DAYS.toMillis(days));
        Minecraft.getInstance().keyboardHandler.setClipboard(issued.secret());
        StringBuilder out = new StringBuilder(I18n.get(Keys.COMMAND_TOKEN_ISSUED, issued.token().id(),
                role.name().toLowerCase(Locale.ROOT), describe(issued.token())));
        out.append('\n').append(I18n.get(Keys.COMMAND_TOKEN_COPIED, issued.secret().substring(0, SHOWN)));
        if (!external.enabled()) {
            out.append('\n').append(I18n.get(Keys.COMMAND_TOKEN_ENDPOINT_OFF));
        }
        return out.toString();
    }

    private static String list(McpTokens tokens, UUID me) {
        List<McpToken> mine = tokens.ownedBy(me);
        if (mine.isEmpty()) {
            return I18n.get(Keys.COMMAND_TOKEN_LIST_NONE);
        }
        long now = System.currentTimeMillis();
        StringBuilder out = new StringBuilder();
        for (McpToken t : mine) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(I18n.get(Keys.COMMAND_TOKEN_ROW, t.id(), t.role().name().toLowerCase(Locale.ROOT), describe(t),
                    t.expiresAt() == 0 ? "never expires" : t.expired(now) ? "expired"
                            : "expires in " + TimeUnit.MILLISECONDS.toHours(t.expiresAt() - now) + " h"));
        }
        return out.toString();
    }

    private static String revoke(McpTokens tokens, UUID me, String id) {
        return tokens.revoke(me, id) ? I18n.get(Keys.COMMAND_TOKEN_REVOKED, id) : I18n.get(Keys.COMMAND_TOKEN_NOT_FOUND, id);
    }

    private static String describe(McpToken token) {
        return token.scope().all() ? "all companions" : token.scope().only().size() + " companion(s)";
    }
}
