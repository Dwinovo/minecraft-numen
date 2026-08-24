package com.dwinovo.numen;

import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.platform.ForgeNumenConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge mod entry for 1.20.1. Forge keeps separate mod and game event buses,
 * just like the NeoForge reference this was ported from — registration-type
 * events go on the mod bus (from the constructor here), while the per-tick /
 * world lifecycle events go on {@link MinecraftForge#EVENT_BUS}.
 *
 * <p>Networking is registered eagerly via {@code NumenNetwork.register()} — the
 * Forge {@code SimpleChannel} accepts message
 * registration during construction, so there is no deferred
 * "flush on RegisterPayloadHandlersEvent" dance like NeoForge required.
 */
@Mod(Constants.MOD_ID)
public class NumenMod {

    public NumenMod() {
        // Register the TOML config spec — Forge handles file creation +
        // hot-reload from here on. SPEC is built in the ForgeNumenConfig static
        // initialiser (just data, no I/O), so referencing it now is safe.
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ForgeNumenConfig.SPEC);

        // Build the SimpleChannel and register every payload eagerly.
        NumenNetwork.register();

        // Dev: /numen_summon — create a companion fake player at the caller.
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) ->
                com.dwinovo.numen.entity.NumenCommands.register(e.getDispatcher()));
        // When an owner logs in, bring their dormant companions back.
        MinecraftForge.EVENT_BUS.addListener(NumenMod::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(NumenMod::onPlayerChangedDimension);
        // 排程机器的心跳:每 tick 驱动全部同伴的竞价/任务/收尾。
        // 挂 START(实体更新之前):任务→导航→执行器落下的移动/按键输入由
        // 本 tick 的实体物理立即消费——"在位置 P 做的决策作用于从 P 出发
        // 的这一步",不产生一 tick 的输入滞后(潜行/松跳等边缘时机全靠它)。
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.TickEvent.ServerTickEvent e) -> {
            if (e.phase == net.minecraftforge.event.TickEvent.Phase.START) {
                com.dwinovo.numen.task.CompanionTickDispatcher.tick(e.getServer());
            }
        });

        // 服务器停了：属于那个世界的进程内状态一起作废。单人「退出存档」不结束进程，
        // 静态表会原封不动活到下一个存档——谁持有谁在自己那边报到，见 ServerLifecycle。
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStoppedEvent e) ->
                com.dwinovo.numen.platform.ServerLifecycle.fireStopped());

        // Client init (key mappings / HUD / world-render path overlay) is wired
        // from the client class, only on the physical client.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            NumenForgeClient.init(
                    net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        }

        CommonClass.init();
        Constants.LOG.info("Numen mod initialised on Forge.");
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player instanceof com.dwinovo.numen.entity.NumenPlayer) return;  // not the companion itself
        MinecraftServer server = player.level().getServer();
        if (server != null) {
            // 只排队,不在这儿恢复:本方法跑在原版 placeNewPlayer 内部,在这里出的任何异常
            // 都会打断主人的入场,客户端只看到"无效的玩家数据"。
            com.dwinovo.numen.entity.Companions.scheduleRestoreFor(player.getUUID());
            com.dwinovo.numen.entity.Companions.syncRosterToOwner(server, player);
        }
    }

    /** The companion crossed a portal on its own — tell its brain (ambient world event). */
    private static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof com.dwinovo.numen.entity.NumenPlayer ap) {
            com.dwinovo.numen.entity.Companions.onDimensionChanged(ap);
        }
    }
}
