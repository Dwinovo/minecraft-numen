package com.dwinovo.numen;

import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.inventory.NumenMenus;
import com.dwinovo.numen.platform.ForgeNumenConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod(Constants.MOD_ID)
public class NumenForgeMod {

    public NumenForgeMod() {
        NumenMenus.register(FMLJavaModLoadingContext.get().getModEventBus());
        // Register the TOML config spec — Forge handles file creation +
        // hot-reload from this point on. SPEC is built lazily in the
        // ForgeNumenConfig static initialiser so referencing it here is
        // safe (no I/O happens until the world loads).
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ForgeNumenConfig.SPEC);

        // Register all network payloads (both C→S and S→C).
        NumenNetwork.register();

        // Dev: /numen_summon — create a companion fake player at the caller.
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) ->
                com.dwinovo.numen.entity.NumenCommands.register(e.getDispatcher()));
        // When an owner logs in, bring their dormant companions back.
        MinecraftForge.EVENT_BUS.addListener(NumenForgeMod::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(NumenForgeMod::onPlayerChangedDimension);

        CommonClass.init();
        Constants.LOG.info("Numen mod initialised on Forge.");
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player instanceof com.dwinovo.numen.entity.NumenPlayer) return;  // not the companion itself
        MinecraftServer server = player.level.getServer();
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
