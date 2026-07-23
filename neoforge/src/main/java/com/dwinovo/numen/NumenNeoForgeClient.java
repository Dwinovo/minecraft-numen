package com.dwinovo.numen;

import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.mcp.client.McpClientManager;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;

/**
 * Client entry point. 1.21.4 still has SEPARATE mod and game event buses
 * (1.21.5 merged them), so a single {@code @EventBusSubscriber} can't carry both
 * — registration events (key mappings / GUI layers / reload listeners) are mod-bus,
 * the tick / world-render / disconnect hooks are game-bus. We register each on its
 * own bus from the mod constructor, mirroring {@link NumenMod}.
 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class NumenNeoForgeClient {

    public NumenNeoForgeClient(IEventBus modBus) {
        // MCP client: connect to external MCP servers in config/numen/mcp_clients.json
        // and register their tools for the built-in brain. Config dir from FML (no
        // Minecraft instance needed this early).
        McpClientManager.initClient(
                net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(Constants.MOD_ID));

        // MCP server: the other direction — a loopback MCP server letting an external
        // agent drive companions directly, bypassing the built-in brain. Off unless
        // enabled in config/numen/mcp_server.json.
        com.dwinovo.numen.mcp.server.NumenMcp.initClient(
                net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get());

        // Mod bus — registration events.
        modBus.addListener(NumenNeoForgeClient::registerKeyMappings);
        modBus.addListener(NumenNeoForgeClient::registerGuiLayers);
        modBus.addListener(NumenNeoForgeClient::registerReloadListeners);
        modBus.addListener(NumenNeoForgeClient::registerShaders);
        // Game bus — per-tick / world-render / disconnect.
        NeoForge.EVENT_BUS.addListener(NumenNeoForgeClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(NumenNeoForgeClient::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(NumenNeoForgeClient::onRenderLevel);
    }

    static void onRenderLevel(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        // 寻路调试覆盖层:世界空间画线(半透明方块阶段之后)。
        if (event.getStage() == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage
                .AFTER_TRANSLUCENT_BLOCKS) {
            com.dwinovo.numen.client.debug.PathDebugRenderer.render(
                    event.getPoseStack(), event.getCamera());
        }
    }

    static void registerKeyMappings(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        // G → companion roster panel (chat entry + settings/reset live in there).
        event.register(com.dwinovo.numen.client.NumenKeys.OPEN_ROSTER);
    }

    static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        com.dwinovo.numen.client.NumenKeys.tick();
        com.dwinovo.numen.client.hud.NumenToasts.tick();
        com.dwinovo.numen.client.agent.AgentLoopRegistry.tickAll();
    }

    static void onLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        com.dwinovo.numen.client.data.ClientNumenInventory.clear();
        com.dwinovo.numen.client.hud.NumenToasts.clear();
        com.dwinovo.numen.client.agent.ClientDeaths.clearAll();
        com.dwinovo.numen.client.debug.PathDebugState.clear();
    }

    static void registerGuiLayers(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
        // HUD: advancement-style activity toasts (top-right) when not watching a panel.
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "numen_toasts"),
                (g, delta) -> com.dwinovo.numen.client.hud.NumenToasts.render(g));
    }

    static void registerShaders(net.neoforged.neoforge.client.event.RegisterShadersEvent event) {
        // GUI 圆角 SDF shader;加载失败仅告警——RoundRect 会自动降级成方角 fill。
        try {
            event.registerShader(new net.minecraft.client.renderer.ShaderInstance(
                            event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "rendertype_round_rect"),
                            com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR),
                    com.dwinovo.numen.client.ui.RoundRect::setShader);
        } catch (Exception e) {
            Constants.LOG.warn("round rect shader failed to load, falling back to square corners", e);
        }
    }

    static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        // 1.21.1 uses RegisterClientReloadListenersEvent.registerReloadListener(listener) — no
        // ResourceLocation key (that's the 1.21.4 AddClientReloadListenersEvent API).
        Path numenConfigRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Constants.MOD_ID);
        Path skillsDir = numenConfigRoot.resolve("skills");

        event.registerReloadListener((ResourceManagerReloadListener) rm -> {
            SkillRegistry.instance().scan(skillsDir);
        });
    }
}
