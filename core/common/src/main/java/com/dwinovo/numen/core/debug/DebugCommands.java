package com.dwinovo.numen.core.debug;

import com.dwinovo.numen.entity.NumenCommands;
import com.dwinovo.numen.network.payload.ClientUiActionPayload;
import com.dwinovo.numen.platform.Services;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 寻路调试开关,挂在 {@code /numen} 下,只给玩家(和别的管理指令一样经 {@link NumenCommands#graft}):
 * <pre>
 *   /numen debug      翻转调试模式(路径粒子渲染 + UI 文本不过滤直出)
 *   /numen profile    翻转寻路性能探针([nav-profile] 主线程 tick 耗时按窗口汇总)
 *   /numen pad        翻转同伴区块加载 pad(诊断 A/B 用)
 * </pre>
 * 替她做事的调试入口是 {@code /numen drive <同伴> <一行指令>},和她的 {@code command} 工具是同一个入口。
 */
public final class DebugCommands {

    private DebugCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        NumenCommands.graft(dispatcher, NumenCommands.FOR_PLAYERS,
                Commands.literal("debug").executes(DebugCommands::toggleDebug));
        NumenCommands.graft(dispatcher, NumenCommands.FOR_PLAYERS,
                Commands.literal("profile").executes(DebugCommands::toggleProfile));
        NumenCommands.graft(dispatcher, NumenCommands.FOR_PLAYERS,
                Commands.literal("pad").executes(DebugCommands::togglePad));
    }

    private static int toggleDebug(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer caller = ctx.getSource().getPlayerOrException();
        boolean on = PathDebug.toggle(caller.getUUID());
        Services.NETWORK.sendToPlayer(caller, new ClientUiActionPayload(on
                ? ClientUiActionPayload.Action.DEBUG_TEXT_ON
                : ClientUiActionPayload.Action.DEBUG_TEXT_OFF));
        ctx.getSource().sendSuccess(() -> Component.literal(
                on ? "调试模式已开:路径粒子渲染 + UI 文本不过滤直出"
                   : "调试模式已关"), false);
        return 1;
    }

    /** 翻转寻路性能探针;开启后 NavProfiler 按 ~5 秒窗口把 [nav-profile] 汇总打到服务端日志。 */
    private static int toggleProfile(CommandContext<CommandSourceStack> ctx) {
        var settings = com.dwinovo.numen.core.pathing.settings.NavSettings.get();
        settings.profile = !settings.profile;
        boolean on = settings.profile;
        // Start each profiling session from a clean window/baseline (no skewed first line across toggles).
        com.dwinovo.numen.core.pathing.util.NavProfiler.reset();
        ctx.getSource().sendSuccess(() -> Component.literal(on
                ? "寻路性能探针已开:日志看 [nav-profile](主线程 tick 耗时按窗口汇总)"
                : "寻路性能探针已关"), false);
        return 1;
    }

    /** 翻转同伴区块加载 pad(诊断 A/B 用):关掉后同伴不再自持加载票据,只能在别人(玩家)
     *  保持加载的区块里活动;已有票据 40 tick 内自然过期。 */
    private static int togglePad(CommandContext<CommandSourceStack> ctx) {
        boolean on = !com.dwinovo.numen.entity.CompanionChunkLoader.enabled;
        com.dwinovo.numen.entity.CompanionChunkLoader.enabled = on;
        ctx.getSource().sendSuccess(() -> Component.literal(on
                ? "同伴加载 pad 已开(默认状态)"
                : "同伴加载 pad 已关:同伴仅在玩家加载的区块内活动(诊断用)"), false);
        return 1;
    }
}
