package com.dwinovo.numen.debug;

import com.dwinovo.numen.api.platform.Services;
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
    static final String NEEDS_CLIENT = "numen.debug.path_needs_client";

    private PathCommands() {}

    /** 要挂到 {@code /numen} 下的那一格。 */
    public static LiteralArgumentBuilder<CommandSourceStack> verb() {
        return Commands.literal("path").executes(PathCommands::toggle);
    }

    private static int toggle(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer caller = ctx.getSource().getPlayerOrException();
        // 线与框是客户端画的:连接上没有这个频道(原版客户端)就开了也看不到,说一声
        if (!Services.network().canSend(caller, PathDebugPayload.TYPE)) {
            ctx.getSource().sendFailure(Component.translatable(NEEDS_CLIENT));
            return 0;
        }
        boolean on = PathDebug.toggle(caller.getUUID());
        ctx.getSource().sendSuccess(() -> Component.translatable(on ? ON : OFF), false);
        return 1;
    }
}
