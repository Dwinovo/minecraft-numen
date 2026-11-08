package com.dwinovo.numen.api;

import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.platform.ForgeNumenConfig;
import com.dwinovo.numen.api.platform.PlayerSessions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge mod entry for 1.20.1. Forge keeps separate mod and game event buses: registration-type events go on the mod bus
 * (from the constructor here), the per-tick / world lifecycle events go on {@link MinecraftForge#EVENT_BUS}.
 *
 * <p>Networking is registered eagerly via {@code NumenNetwork.register()} — the Forge {@code SimpleChannel} accepts message
 * registration during construction, so nothing waits for a later registration event.
 */
@Mod(Constants.MOD_ID)
public class NumenMod {

    public NumenMod() {
        // Register the TOML config spec — Forge handles file creation + hot-reload from here on. SPEC is built in the
        // ForgeNumenConfig static initialiser (just data, no I/O), so referencing it now is safe.
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ForgeNumenConfig.SPEC);

        // Build the SimpleChannel and register every payload eagerly.
        NumenNetwork.register();

        // Dev: /numen_summon — create a companion fake player at the caller.
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.RegisterCommandsEvent e) ->
                com.dwinovo.numen.api.entity.NumenCommands.register(e.getDispatcher()));
        // When an owner logs in, bring their dormant companions back.
        MinecraftForge.EVENT_BUS.addListener(NumenMod::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(NumenMod::onPlayerLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(NumenMod::onPlayerChangedDimension);
        // 聊天栏切进了会话的玩家,他打的字进会话、不广播到公屏(见 ChatRoutes)
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.ServerChatEvent e) -> {
            if (com.dwinovo.numen.api.brain.ChatRoutes.take(e.getPlayer(), e.getRawText())) {
                e.setCanceled(true);
            }
        });
        // 排程机器的心跳:每 tick 驱动全部同伴的竞价/任务/收尾。
        // 挂 START(实体更新之前):任务→导航→执行器落下的移动/按键输入由
        // 本 tick 的实体物理立即消费——"在位置 P 做的决策作用于从 P 出发
        // 的这一步",不产生一 tick 的输入滞后(潜行/松跳等边缘时机全靠它)。
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.TickEvent.ServerTickEvent e) -> {
            if (e.phase == net.minecraftforge.event.TickEvent.Phase.START) {
                com.dwinovo.numen.api.task.CompanionTickDispatcher.tick(e.getServer());
            }
        });

        // 服务器停了：属于那个世界的进程内状态一起作废。单人「退出存档」不结束进程，
        // 静态表会原封不动活到下一个存档——谁持有谁在自己那边报到，见 ServerLifecycle。
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStartedEvent e) ->
                com.dwinovo.numen.api.platform.ServerLifecycle.fireStarted(e.getServer()));
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStoppedEvent e) ->
                com.dwinovo.numen.api.platform.ServerLifecycle.fireStopped());
        // /reload:磁盘上手改的库、钥匙串、设置重新读(服务器起好之前的那次装载 fireReloaded 不理)
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.AddReloadListenerEvent e) ->
                e.addListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager ->
                        com.dwinovo.numen.api.platform.ServerLifecycle.fireReloaded()));

        // Client init (key mappings / HUD / world-render overlay) is wired from the client class, only on the physical client.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            NumenForgeClient.init(FMLJavaModLoadingContext.get().getModEventBus());
        }

        CommonClass.init();
        Constants.LOG.info("Numen mod initialised on Forge.");
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
