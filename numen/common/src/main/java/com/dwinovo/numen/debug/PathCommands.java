package com.dwinovo.numen.debug;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 寻路调试开关,{@code /numen path}:翻转调用者自己的"画出同伴正在走的路"(世界里的线与框,见 {@link PathDebugRenderer})。
 * 挂在 {@code /numen} 下经 Numen API 的 {@code NumenApi.command},和别的管理指令一样只给玩家。
 */
public final class PathCommands {

    static final String ON = "numen.debug.path_on";
    static final String OFF = "numen.debug.path_off";

    private PathCommands() {}

    /** 要挂到 {@code /numen} 下的那一格。 */
    public static LiteralArgumentBuilder<CommandSourceStack> verb() {
        return Commands.literal("path").executes(PathCommands::toggle);
    }

    private static int toggle(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer caller = ctx.getSource().getPlayerOrException();
        boolean on = PathDebug.toggle(caller.getUUID());
        ctx.getSource().sendSuccess(() -> Component.translatable(on ? ON : OFF), false);
        return 1;
    }
}
