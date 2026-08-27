package com.dwinovo.numen;

import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.mcp.client.McpClientManager;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;

/**
 * Client entry point. 1.21.5 merged the mod and game event buses; registering
 * registration events (key mappings / GUI layers / reload listeners) on the mod bus
 * and the tick / world-render / disconnect hooks on {@code NeoForge.EVENT_BUS} from
 * the mod constructor still compiles and behaves identically (only the
 * {@code @EventBusSubscriber(bus=…)} attribute is deprecated), mirroring {@link NumenMod}.
 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class NumenNeoForgeClient {

    public NumenNeoForgeClient(IEventBus modBus) {
        // 下行 payload 的处理体住在客户端源码集,先挂进主源码集的挂点
        com.dwinovo.numen.client.ClientPayloadHandlers.install();
        // MCP client: connect to external MCP servers in config/numen/mcp_clients.json
        // and register their tools for the built-in brain. Config dir from FML (no
        // Minecraft instance needed this early).
        // 早先用 MOD_ID 当配置根,技能与 mcp_clients 落在了 config/numen_api/。
        // 先把它们接过来,再让下面各个 init 去读——否则老玩家的配置会凭空消失。
        com.dwinovo.numen.client.ConfigRootMigration.run(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get());

        McpClientManager.initClient(
                net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(Constants.CONFIG_ROOT));

        // MCP server: the other direction — a loopback MCP server letting an external
        // agent drive companions directly, bypassing the built-in brain. Off unless
        // enabled in config/numen/mcp_server.json.
        com.dwinovo.numen.mcp.server.NumenMcp.initClient(
                net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get());

        // 同伴数据的根,以及旧布局的一次性迁移。根从 FML 拿,不问 Minecraft
        // (datagen 里模组照样构造,那时没有 Minecraft 实例)。
        com.dwinovo.numen.client.agent.CompanionHome.init(
                net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(Constants.CONFIG_ROOT));
        com.dwinovo.numen.client.agent.CompanionHome.migrateLegacy();

        // 把只在客户端存在的能力接给插件那扇门。接上了本身就是"这是客户端"的判据——
        // 专用服务器上没人接,插件的 bundleSkills 与 onClient 自然成空操作,
        // 不必让每个插件自己去问一遍加载器"我在哪一侧"。
        com.dwinovo.numen.api.NumenPlugins.bindClient(
                root -> com.dwinovo.numen.agent.skill.SkillRegistry.instance().declareBundled(root),
                com.dwinovo.numen.api.NumenGateway::enqueue);

        // 读回上次选择的 GUI 主题(config/numen/ui.json)。
        com.dwinovo.numen.client.screen.UiTheme.init(
                net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(Constants.CONFIG_ROOT));

        // Mod bus — registration events.
        modBus.addListener(NumenNeoForgeClient::registerKeyMappings);
        modBus.addListener(NumenNeoForgeClient::registerGuiLayers);
        modBus.addListener(NumenNeoForgeClient::registerReloadListeners);
        // GUI 圆角 SDF shader 无需任何注册:1.21.5 改代码定义的 RenderPipeline,
        // 首次使用时懒编译(RegisterShadersEvent 已随 JSON shader 体系一并删除)。
        // Game bus — per-tick / world-render / disconnect.
        NeoForge.EVENT_BUS.addListener(NumenNeoForgeClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(NumenNeoForgeClient::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(NumenNeoForgeClient::onRenderLevel);
    }

    static void onRenderLevel(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        // 寻路调试覆盖层:世界空间画线(半透明方块阶段之后)。
        // 头顶气泡不在这里——它走玩家实体渲染尾部(MixinLivingEntityRenderer),
        // 与名牌同管线,光影下才正常。
        if (event.getStage() == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage
                .AFTER_TRANSLUCENT_BLOCKS) {
            com.dwinovo.numen.client.debug.PathDebugRenderer.render(
                    event.getPoseStack(), event.getCamera());
        }
    }

    static void registerKeyMappings(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        // G → companion roster panel (chat entry + settings/reset live in there).
        event.register(com.dwinovo.numen.client.NumenKeys.OPEN_ROSTER);
        // R(hold) → companion wheel; Y → quick chat; V(hold) → quick voice.
        event.register(com.dwinovo.numen.client.NumenKeys.COMPANION_WHEEL);
        event.register(com.dwinovo.numen.client.NumenKeys.TALK_COMPANION);
        event.register(com.dwinovo.numen.client.NumenKeys.QUICK_VOICE);
    }

    static void registerGuiLayers(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
        // HUD: 快捷对话提醒——准星指着同伴时浮「按 [键] 对话」;
        // toast 横幅同层(错误分类话术等,玩家不开面板也看得见)。
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "talk_hint"),
                (g, delta) -> com.dwinovo.numen.client.hud.TalkHint.render(g));
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "numen_toasts"),
                (g, delta) -> com.dwinovo.numen.client.hud.NumenHudToasts.render(g));
    }

    static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        com.dwinovo.numen.client.NumenKeys.tick();
        com.dwinovo.numen.client.agent.AgentLoopRegistry.tickAll();
        com.dwinovo.numen.mcp.server.McpMode.instance().clientTick();
    }

    static void onLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
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

    static void registerReloadListeners(AddClientReloadListenersEvent event) {
        // 1.21.4+ uses AddClientReloadListenersEvent.addListener(ResourceLocation, listener) —
        // the keyed API (1.21.1 was RegisterClientReloadListenersEvent.registerReloadListener,
        // no key).
        Path numenConfigRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Constants.CONFIG_ROOT);
        Path skillsDir = numenConfigRoot.resolve("skills");

        event.addListener(
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "skill_loader"),
                (ResourceManagerReloadListener) rm -> {
                    SkillRegistry.instance().scan(skillsDir);
                });
    }
}
