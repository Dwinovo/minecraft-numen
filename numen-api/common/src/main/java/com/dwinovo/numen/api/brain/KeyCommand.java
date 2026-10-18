package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.library.EntryId;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /numen key <服务商> <key>}:把 key 存进他自己的钥匙串(面板钥匙串页存的是同一本,同走
 * {@link Libraries#saveOwnKey})。服务商写模型档案里的服务商名(补全列他档案用到的与已存过的)。
 *
 * <p>key 不进日志、不进回执全文:回执只露前后几位,只回给他自己、不广播给其他管理员。多人服上第一次存,
 * 和面板一样提醒一次"服主看得到这把 key"。原版与两个加载器不记录玩家执行的命令;Paper 默认会记,服主要知道这一点。
 */
public final class KeyCommand {

    /** 回执里露出 key 的头几位与末几位;短于这两段之和加一的 key 一位也不露。 */
    private static final int SHOWN_HEAD = 3;
    private static final int SHOWN_TAIL = 2;

    private KeyCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("key").then(Commands.argument("service", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(services(ctx.getSource()), builder))
                .then(Commands.argument("key", StringArgumentType.greedyString()).executes(KeyCommand::save)));
    }

    private static java.util.Set<String> services(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player
                ? Libraries.of(player.server).keyServices(player.getUUID(), false) : java.util.Set.of();
    }

    private static int save(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        String service = StringArgumentType.getString(ctx, "service");
        String key = StringArgumentType.getString(ctx, "key").strip();
        if (!EntryId.segment(service) || key.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_KEY_USAGE));
            return 0;
        }
        boolean remind = Libraries.of(owner.server).saveOwnKey(owner.getUUID(), service, key);
        ctx.getSource().sendSuccess(() -> Component.translatable(Keys.COMMAND_KEY_SAVED, service, preview(key)), false);
        if (remind) {
            ctx.getSource().sendSuccess(() -> Component.translatable(Keys.KEY_OWNER_SEES), false);
        }
        return 1;
    }

    /** key 的头几位和末几位,中间换成省略号;太短的整个藏起来。 */
    static String preview(String key) {
        if (key.length() <= SHOWN_HEAD + SHOWN_TAIL + 1) {
            return "…";
        }
        return key.substring(0, SHOWN_HEAD) + "…" + key.substring(key.length() - SHOWN_TAIL);
    }
}
