package com.dwinovo.numen.api;

import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.platform.NeoForgeNumenConfig;
import com.dwinovo.numen.api.platform.NeoForgeNetworkChannel;
import com.dwinovo.numen.api.platform.PlayerSessions;
import com.dwinovo.numen.api.platform.Services;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(Constants.MOD_ID)
public class NumenMod {

    public NumenMod(IEventBus eventBus, ModContainer container) {
        eventBus.addListener(NumenMod::registerPayloads);

        // Register the TOML config spec — NeoForge handles file creation +
        // hot-reload from this point on. SPEC is built lazily in the
        // NeoForgeNumenConfig static initialiser so referencing it here is
        // safe (no I/O happens until the world loads).
        container.registerConfig(ModConfig.Type.COMMON, NeoForgeNumenConfig.SPEC);

        // Queue payload registrations into NeoForgeNetworkChannel; the queue
        // flushes when RegisterPayloadHandlersEvent fires (see below).
        NumenNetwork.register();

        // Dev: /numen_summon — create a companion fake player at the caller.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent e) ->
                com.dwinovo.numen.api.entity.NumenCommands.register(e.getDispatcher()));
        // When an owner logs in, bring their dormant companions back.
        NeoForge.EVENT_BUS.addListener(NumenMod::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(NumenMod::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(NumenMod::onPlayerChangedDimension);
        // 聊天栏切进了会话的玩家,他打的字进会话、不广播到公屏(见 ChatRoutes)
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.ServerChatEvent e) -> {
            if (com.dwinovo.numen.api.brain.ChatRoutes.take(e.getPlayer(), e.getRawText())) {
                e.setCanceled(true);
            }
        });
        // 排程机器的心跳:每 tick 驱动全部同伴的竞价/任务/收尾。
        // 挂 Pre(实体更新之前):任务→导航→执行器落下的移动/按键输入由
        // 本 tick 的实体物理立即消费——"在位置 P 做的决策作用于从 P 出发
        // 的这一步",不产生一 tick 的输入滞后(潜行/松跳等边缘时机全靠它)。
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Pre e) ->
                com.dwinovo.numen.api.task.CompanionTickDispatcher.tick(e.getServer()));

        // 服务器停了：属于那个世界的进程内状态一起作废。单人「退出存档」不结束进程，
        // 静态表会原封不动活到下一个存档——谁持有谁在自己那边报到，见 ServerLifecycle。
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStartedEvent e) ->
                com.dwinovo.numen.api.platform.ServerLifecycle.fireStarted(e.getServer()));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e) ->
                com.dwinovo.numen.api.platform.ServerLifecycle.fireStopped());
        // /reload:磁盘上手改的库、钥匙串、设置重新读(服务器起好之前的那次装载 fireReloaded 不理)
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.AddReloadListenerEvent e) ->
                e.addListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager ->
                        com.dwinovo.numen.api.platform.ServerLifecycle.fireReloaded()));

        CommonClass.init();
        Constants.LOG.info("Numen mod initialised on NeoForge.");
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        if (Services.network() instanceof NeoForgeNetworkChannel ch) {
            ch.flushPending(event);
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerSessions.joined(player);
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerSessions.left(player);
        }
    }

    private static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerSessions.changedDimension(player);
        }
    }
}
