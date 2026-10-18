package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.keys.ServerKeyQuota;
import com.dwinovo.numen.api.platform.Services;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.UUID;

/**
 * {@code /numen usage}:管理员查服主的 key 今天每个人用了多少 token(以及每人每天的限额,{@code server.json} 里设)。
 * 玩家自己的 key 不在这里——那是他自己的账。
 */
public final class UsageCommand {

    private UsageCommand() {}

    /** 挂在 {@code /numen} 下的一格,只有管理员用得了。 */
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("usage").requires(source -> Services.permissions().isAdmin(source))
                .executes(UsageCommand::run);
    }

    private static int run(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        ServerKeyQuota quota = ModelServices.of(server).quota();
        Map<UUID, Long> used = quota.usedToday();
        source.sendSuccess(() -> Component.translatable(Keys.USAGE_HEADER, quota.limit() == 0
                ? Component.translatable(Keys.USAGE_UNLIMITED) : Component.literal(String.valueOf(quota.limit()))), false);
        if (used.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(Keys.USAGE_NOBODY), false);
        }
        used.forEach((player, tokens) -> source.sendSuccess(() -> Component.translatable(Keys.USAGE_LINE,
                server.getProfileCache().get(player).map(profile -> profile.getName()).orElse(player.toString()),
                tokens), false));
        return used.size();
    }
}
