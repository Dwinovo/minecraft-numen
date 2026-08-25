package com.dwinovo.numen;

import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.mcp.client.McpClientManager;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;

import java.nio.file.Path;

/**
 * Client entry point for Forge 1.20.2. Forge keeps separate mod and game event
 * buses (mirroring the NeoForge reference this was ported from): registration
 * events (key mappings / GUI overlays / reload listeners) go on the mod bus,
 * while the per-tick / world-render / disconnect hooks go on the game bus
 * ({@link MinecraftForge#EVENT_BUS}).
 *
 * <p>Invoked from {@link NumenMod} only when {@code FMLEnvironment.dist} is the
 * physical client, so none of these client-only types load on a dedicated server.
 */
public final class NumenForgeClient {

    private NumenForgeClient() {}

    /** Wire every client listener. {@code modBus} is the mod event bus from the constructor. */
    public static void init(IEventBus modBus) {
        // 下行 payload 的处理体住在客户端源码集,先挂进主源码集的挂点
        com.dwinovo.numen.client.ClientPayloadHandlers.install();
        // MCP client: connect to external MCP servers in config/numen/mcp_clients.json
        // and register their tools for the built-in brain. Config dir from FML (no
        // Minecraft instance needed this early).
        McpClientManager.initClient(
                net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve(Constants.MOD_ID));

        // MCP server: the other direction — a loopback MCP server letting an external
        // agent drive companions directly, bypassing the built-in brain. Off unless
        // enabled in config/numen/mcp_server.json.
        com.dwinovo.numen.mcp.server.NumenMcp.initClient(
                net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());

        // 同伴数据的根,以及旧布局的一次性迁移。根从 FML 拿,不问 Minecraft
        // (datagen 里模组照样构造,那时没有 Minecraft 实例)。
        com.dwinovo.numen.client.agent.CompanionHome.init(
                net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("numen"));
        com.dwinovo.numen.client.agent.CompanionHome.migrateLegacy();

        // 读回上次选择的 GUI 主题(config/numen/ui.json)。
        com.dwinovo.numen.client.screen.UiTheme.init(
                net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("numen"));

        // Mod bus — registration events.
        modBus.addListener(NumenForgeClient::registerKeyMappings);
        modBus.addListener(NumenForgeClient::registerGuiOverlays);
        modBus.addListener(NumenForgeClient::registerReloadListeners);
        modBus.addListener(NumenForgeClient::registerShaders);
        // Game bus — per-tick / world-render / disconnect.
        MinecraftForge.EVENT_BUS.addListener(NumenForgeClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(NumenForgeClient::onLoggingOut);
        MinecraftForge.EVENT_BUS.addListener(NumenForgeClient::onRenderLevel);
    }

    static void onRenderLevel(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        // 寻路调试覆盖层:世界空间画线(半透明方块阶段之后)。
        // 头顶气泡不在这里——它走玩家实体渲染尾部(MixinPlayerRenderer),
        // 与名牌同管线,光影下才正常。
        if (event.getStage() == net.minecraftforge.client.event.RenderLevelStageEvent.Stage
                .AFTER_TRANSLUCENT_BLOCKS) {
            com.dwinovo.numen.client.debug.PathDebugRenderer.render(
                    event.getPoseStack(), event.getCamera());
        }
    }

    static void registerShaders(net.minecraftforge.client.event.RegisterShadersEvent event) {
        // GUI 圆角 SDF shader;加载失败仅告警——RoundRect 会自动降级成方角 fill。
        try {
            event.registerShader(new net.minecraft.client.renderer.ShaderInstance(
                            event.getResourceProvider(),
                            new net.minecraft.resources.ResourceLocation(
                                    Constants.MOD_ID, "rendertype_round_rect"),
                            com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR),
                    com.dwinovo.numen.client.ui.RoundRect::setShader);
        } catch (Exception e) {
            Constants.LOG.warn("round rect shader failed to load, falling back to square corners", e);
        }
    }

    static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        // G → companion roster panel (chat entry + settings/reset live in there).
        event.register(com.dwinovo.numen.client.NumenKeys.OPEN_ROSTER);
        // R(hold) → companion wheel; Y → quick chat; V(hold) → quick voice.
        event.register(com.dwinovo.numen.client.NumenKeys.COMPANION_WHEEL);
        event.register(com.dwinovo.numen.client.NumenKeys.TALK_COMPANION);
        event.register(com.dwinovo.numen.client.NumenKeys.QUICK_VOICE);
    }

    static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        com.dwinovo.numen.client.NumenKeys.tick();
        com.dwinovo.numen.client.agent.AgentLoopRegistry.tickAll();
        com.dwinovo.numen.mcp.server.McpMode.instance().clientTick();
    }

    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // 先掐大脑:作废在飞回合与工具链,别让上一个存档的回合漂进下一个存档
        com.dwinovo.numen.client.agent.AgentLoopRegistry.quiesceAll();
        com.dwinovo.numen.client.data.ClientNumenState.clear();
        com.dwinovo.numen.client.agent.KnownSkins.clear();
        com.dwinovo.numen.client.hud.SpeechBubbles.clear();
        com.dwinovo.numen.client.chat.SelectedCompanion.clear();
        com.dwinovo.numen.client.chat.QuickVoice.clear();
        com.dwinovo.numen.client.chat.ChatLines.clearLive();
        com.dwinovo.numen.client.agent.NumenRoster.instance().clear();
        com.dwinovo.numen.client.agent.CompanionHome.onDisconnect();
        com.dwinovo.numen.client.debug.PathDebugState.clear();
    }

    static void registerGuiOverlays(RegisterGuiOverlaysEvent event) {
        // HUD: 快捷对话提醒——准星指着同伴时浮「按 [键] 对话」;
        // toast 横幅同层(错误分类话术等,玩家不开面板也看得见)。
        event.registerAboveAll("talk_hint",
                (gui, g, partialTick, screenWidth, screenHeight) ->
                        com.dwinovo.numen.client.hud.TalkHint.render(g));
        event.registerAboveAll("numen_toasts",
                (gui, g, partialTick, screenWidth, screenHeight) ->
                        com.dwinovo.numen.client.hud.NumenHudToasts.render(g));
    }

    static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        Path skillsDir = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Constants.MOD_ID).resolve("skills");

        event.registerReloadListener((ResourceManagerReloadListener) rm -> {
            SkillRegistry.instance().scan(skillsDir);
        });
    }
}
