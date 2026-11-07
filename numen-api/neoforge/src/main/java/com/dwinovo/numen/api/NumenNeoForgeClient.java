package com.dwinovo.numen.api;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;


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
        // 下行 payload 的处理体住在客户端源码集,先挂进主源码集的挂点
        com.dwinovo.numen.api.client.ClientPayloadHandlers.install();
        // 早先用 MOD_ID 当配置根,技能与 mcp_clients 落在了 config/numen_api/。
        // 先把它们接过来,再让下面各个 init 去读——否则老玩家的配置会凭空消失。
        com.dwinovo.numen.api.client.ConfigRootMigration.run(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get());

        // 这台电脑自己的文件住在 config/numen/client/;旧版散放在 config/numen/ 根下的先接过来。
        // 根从 FML 拿,不问 Minecraft
        // (datagen 里模组照样构造,那时没有 Minecraft 实例)。
        com.dwinovo.numen.api.client.ClientFiles.migrate(com.dwinovo.numen.api.NumenPaths.config(),
                com.dwinovo.numen.api.home.Homes.clientDir());

        // 把只在客户端存在的能力接给插件那扇门。接上了本身就是"这是客户端"的判据——
        // 专用服务器上没人接,插件的 onClient 自然成空操作,不必让每个插件自己去问一遍加载器"我在哪一侧"。
        com.dwinovo.numen.api.NumenPlugins.bindClient(com.dwinovo.numen.api.NumenGateway::emit);

        // 读回上次选择的 GUI 主题(config/numen/client/ui.json)。
        com.dwinovo.numen.api.client.screen.UiTheme.init(com.dwinovo.numen.api.home.Homes.clientDir());

        // Mod bus — registration events.
        modBus.addListener(NumenNeoForgeClient::registerKeyMappings);
        modBus.addListener(NumenNeoForgeClient::registerGuiLayers);
        // Game bus — per-tick / world-render / disconnect.
        NeoForge.EVENT_BUS.addListener(NumenNeoForgeClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(NumenNeoForgeClient::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(NumenNeoForgeClient::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(NumenNeoForgeClient::onScreenRendered);
        NeoForge.EVENT_BUS.addListener(NumenNeoForgeClient::onScreenMousePressed);
    }

    static void onRenderLevel(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        // 世界空间的覆盖层(半透明方块阶段之后)。
        // 头顶气泡不在这里——它走玩家实体渲染尾部(MixinPlayerRenderer),
        // 与名牌同管线,光影下才正常。
        if (event.getStage() == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage
                .AFTER_TRANSLUCENT_BLOCKS) {
            com.dwinovo.numen.api.client.consent.ConsentOutlines.render(
                    event.getPoseStack(), event.getCamera());
        }
    }

    static void registerKeyMappings(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        // G → companion roster panel (chat entry + settings/reset live in there).
        event.register(com.dwinovo.numen.api.client.NumenKeys.OPEN_ROSTER);
        // R(hold) → companion wheel; Y → quick chat; V(hold) → quick voice.
        event.register(com.dwinovo.numen.api.client.NumenKeys.COMPANION_WHEEL);
        event.register(com.dwinovo.numen.api.client.NumenKeys.TALK_COMPANION);
        event.register(com.dwinovo.numen.api.client.NumenKeys.QUICK_VOICE);
    }

    static void registerGuiLayers(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
        // HUD: 快捷对话提醒——准星指着同伴时浮「按 [键] 对话」;
        // toast 横幅同层(错误分类话术等,玩家不开面板也看得见)。
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "talk_hint"),
                (g, delta) -> com.dwinovo.numen.api.client.hud.TalkHint.render(g));
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "numen_toasts"),
                (g, delta) -> com.dwinovo.numen.api.client.hud.NumenHudToasts.render(g));
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "message_notices"),
                (g, delta) -> com.dwinovo.numen.api.client.notify.MessageNotices.renderHud(g));
    }

    /** 消息通知开着界面时画在界面上面。 */
    static void onScreenRendered(net.neoforged.neoforge.client.event.ScreenEvent.Render.Post event) {
        com.dwinovo.numen.api.client.notify.MessageNotices.renderOver(
                event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
    }

    /** 点在消息通知上的那一下归通知,界面不再处理。 */
    static void onScreenMousePressed(net.neoforged.neoforge.client.event.ScreenEvent.MouseButtonPressed.Pre event) {
        if (com.dwinovo.numen.api.client.notify.MessageNotices.click(
                event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        com.dwinovo.numen.api.client.NumenKeys.tick();
        com.dwinovo.numen.api.client.agent.RemoteCompanions.tick();
    }

    static void onLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        // 服务端推来的一切都不算了:别让上一个存档的东西漂进下一个存档
        com.dwinovo.numen.api.client.agent.RemoteCompanions.disconnected();
        com.dwinovo.numen.api.network.NumenNetwork.disconnectedFromServer();
        com.dwinovo.numen.api.client.data.ClientNumenState.clear();
        com.dwinovo.numen.api.client.agent.KnownSkins.clear();
        com.dwinovo.numen.api.client.hud.SpeechBubbles.clear();
        com.dwinovo.numen.api.client.chat.QuickVoice.clear();
        com.dwinovo.numen.api.client.chat.ChatLines.clearLive();
        com.dwinovo.numen.api.client.agent.NumenRoster.instance().clear();
        com.dwinovo.numen.api.client.consent.ConsentCards.clear();
    }
}
