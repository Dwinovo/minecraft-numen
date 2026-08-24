package com.dwinovo.numen;

import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.platform.ForgeNumenConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge mod entry for 1.20.4. Forge keeps separate mod and game event buses,
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
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

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
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.TickEvent.ServerTickEvent e) -> {
            if (e.phase == net.minecraftforge.event.TickEvent.Phase.END) {
                com.dwinovo.numen.task.CompanionTickDispatcher.tick(e.getServer());
            }
        });

        // Client init (key mappings / HUD / world-render path overlay) is wired
        // from the client class, only on the physical client.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            NumenForgeClient.init(modBus);
        }

        CommonClass.init();
        Constants.LOG.info("Numen mod initialised on Forge.");
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player instanceof com.dwinovo.numen.entity.NumenPlayer) return;  // not the companion itself
        MinecraftServer server = player.level().getServer();
        if (server != null) {
            com.dwinovo.numen.entity.Companions.respawnAllOwnedBy(server, player.getUUID());
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
