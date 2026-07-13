package com.dwinovo.numen;

import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.hud.NumenToasts;
import com.dwinovo.numen.client.NumenKeys;
import com.dwinovo.numen.client.path.ClientPathViz;
import com.dwinovo.numen.client.path.PathVizRenderer;
import com.dwinovo.numen.client.screen.CompanionInventoryScreen;
import com.dwinovo.numen.inventory.NumenMenus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.nio.file.Path;

/**
 * Client entry point for Forge 1.20.1. Registration events (key mappings /
 * GUI overlays) are subscribed on the mod bus; per-tick / world-render /
 * disconnect hooks are registered on the Forge game bus from
 * {@link FMLClientSetupEvent}.
 */
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class NumenForgeClient {

    /** Scanned once on the first client tick — no reload-listener event in 1.20.1. */
    private static boolean skillsScanned = false;

    private NumenForgeClient() {}

    // ---- Mod-bus registration events ---------------------------------

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        // G — companion roster panel (chat entry + settings/reset live in there).
        event.register(NumenKeys.OPEN_ROSTER);
    }

    @SubscribeEvent
    public static void registerGuiOverlays(RegisterGuiOverlaysEvent event) {
        // HUD: advancement-style activity toasts (top-right) when not watching a panel.
        event.registerAboveAll("numen_toasts",
                (gui, poseStack, partialTick, screenWidth, screenHeight) -> {
                    GuiGraphics gfx = new GuiGraphics(Minecraft.getInstance(),
                            Minecraft.getInstance().renderBuffers().bufferSource());
                    NumenToasts.render(gfx);
                });
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(NumenMenus.COMPANION_INVENTORY.get(), CompanionInventoryScreen::new));
        // Game bus — per-tick / world-render / disconnect.
        MinecraftForge.EVENT_BUS.addListener(NumenForgeClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(NumenForgeClient::onRenderLevel);
        MinecraftForge.EVENT_BUS.addListener(NumenForgeClient::onLoggingOut);
    }

    // ---- Game-bus listeners (registered during FMLClientSetupEvent) ---

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // First tick: scan skills from the config directory (no reload-listener
        // event in Forge 1.20.1; this covers both initial load and /reload via
        // the skills-invalidate command).
        if (!skillsScanned) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                Path numenConfigRoot = mc.gameDirectory.toPath()
                        .resolve("config").resolve(Constants.MOD_ID);
                Path skillsDir = numenConfigRoot.resolve("skills");
                SkillRegistry.instance().scan(skillsDir);
            }
            skillsScanned = true;
        }

        NumenKeys.tick();
        NumenToasts.tick();
        AgentLoopRegistry.tickAll();
    }

    private static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        // In-world path overlay for every companion (Baritone PathRenderer port).
        PathVizRenderer.render(event.getPoseStack());
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // Drop every path overlay on disconnect so a frozen path can't survive a relog.
        ClientPathViz.clearAll();
        com.dwinovo.numen.client.data.ClientTaskList.clear();
        com.dwinovo.numen.client.data.ClientCompanionSettings.clear();
        NumenToasts.clear();
        com.dwinovo.numen.client.agent.ClientDeaths.clearAll();
    }
}
