package com.dwinovo.numen.core.debug;

import com.dwinovo.numen.entity.NumenCommands;
import com.dwinovo.numen.network.payload.ClientUiActionPayload;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.dwinovo.numen.network.NumenNetwork;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import com.dwinovo.numen.data.ModLanguageData.Keys;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 寻路调试开关,挂在 {@code /numen} 下,只给玩家(和别的管理指令一样经 {@link NumenCommands#graft}):
 * <pre>
 *   /numen debug      翻转调试模式(路径粒子渲染 + UI 文本不过滤直出)
 *   /numen pad        翻转同伴区块加载 pad(诊断 A/B 用)
 * </pre>
 * 替她做事的调试入口是 {@code /numen drive <同伴> <一行指令>},和她的 {@code command} 工具是同一个入口。
 */
public final class DebugCommands {

    private DebugCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        NumenCommands.graft(dispatcher, Commands.literal("debug").executes(DebugCommands::toggleDebug));
        NumenCommands.graft(dispatcher, Commands.literal("pad").executes(DebugCommands::togglePad));
    }

    private static int toggleDebug(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer caller = ctx.getSource().getPlayerOrException();
        boolean on = PathDebug.toggle(caller.getUUID());
        NumenNetwork.sendToPlayer(caller, new ClientUiActionPayload(on
                ? ClientUiActionPayload.Action.DEBUG_TEXT_ON
                : ClientUiActionPayload.Action.DEBUG_TEXT_OFF));
        ctx.getSource().sendSuccess(() -> Component.translatable(on ? Keys.DEBUG_ON : Keys.DEBUG_OFF), false);
        return 1;
    }

    /** 翻转同伴区块加载 pad(诊断 A/B 用):关掉后同伴不再自持加载票据,只能在别人(玩家)
     *  保持加载的区块里活动;已有票据 40 tick 内自然过期。 */
    private static int togglePad(CommandContext<CommandSourceStack> ctx) {
        boolean on = !com.dwinovo.numen.entity.CompanionChunkLoader.enabled;
        com.dwinovo.numen.entity.CompanionChunkLoader.enabled = on;
        ctx.getSource().sendSuccess(() -> Component.translatable(on ? Keys.DEBUG_PAD_ON : Keys.DEBUG_PAD_OFF), false);
        return 1;
    }
}
