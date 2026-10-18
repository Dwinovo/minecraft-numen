package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
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
 * 指令里"他名下的一只同伴"这个参数:补全列他名下各只的名字,读出来要么是唯一的一只、要么说清为什么不行。
 * {@code /numen group}、{@code /numen companion} 共用,名字带空格或不是英文字母的要加引号。
 */
final class CompanionArgument {

    private CompanionArgument() {}

    static RequiredArgumentBuilder<CommandSourceStack, String> of(String name) {
        return Commands.argument(name, StringArgumentType.string()).suggests(CompanionArgument::suggest);
    }

    private static CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> ctx,
                                                          SuggestionsBuilder builder) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return builder.buildFuture();
        }
        return SharedSuggestionProvider.suggest(CompanionRegistry.get(player.server).ownedBy(player.getUUID()).stream()
                .map(entry -> StringArgumentType.escapeIfRequired(entry.getValue().name())), builder);
    }

    /** 他指的同伴;没有或重名就说出来,返回 {@code null}。 */
    static Map.Entry<UUID, CompanionRegistry.Entry> resolve(CommandContext<CommandSourceStack> ctx, String name,
                                                           ServerPlayer owner) {
        String given = StringArgumentType.getString(ctx, name);
        List<Map.Entry<UUID, CompanionRegistry.Entry>> named = CompanionRegistry.get(owner.server)
                .ownedBy(owner.getUUID()).stream().filter(entry -> entry.getValue().name().equalsIgnoreCase(given))
                .toList();
        if (named.size() == 1) {
            return named.get(0);
        }
        ctx.getSource().sendFailure(named.isEmpty() ? Component.translatable(Keys.COMMAND_NO_SUCH_COMPANION, given)
                : Component.translatable(Keys.COMMAND_SEVERAL_NAMED, named.size(), given));
        return null;
    }
}
