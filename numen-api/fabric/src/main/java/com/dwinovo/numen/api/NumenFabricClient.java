package com.dwinovo.numen.api;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.Minecraft;


public class NumenFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // 下行 payload 的处理体住在客户端源码集,先挂进主源码集的挂点
        com.dwinovo.numen.api.client.ClientPayloadHandlers.install();
        // 早先用 MOD_ID 当配置根,技能与 mcp_clients 落在了 config/numen_api/。
        // 先把它们接过来,再让下面各个 init 去读——否则老玩家的配置会凭空消失。
        com.dwinovo.numen.api.client.ConfigRootMigration.run(FabricLoader.getInstance().getConfigDir());
        // 每只同伴用哪条声线、穿哪张皮肤是这台机器的偏好。根从 loader 拿,不问 Minecraft
        // (datagen 里模组照样构造,那时没有 Minecraft 实例)。
        com.dwinovo.numen.api.client.agent.CompanionLooks.init(
                FabricLoader.getInstance().getConfigDir().resolve(Constants.CONFIG_ROOT));

        // 把只在客户端存在的能力接给插件那扇门。接上了本身就是"这是客户端"的判据——
        // 专用服务器上没人接,插件的 onClient 自然成空操作,不必让每个插件自己去问一遍加载器"我在哪一侧"。
        com.dwinovo.numen.api.NumenPlugins.bindClient(com.dwinovo.numen.api.NumenGateway::emit);

        // 读回上次选择的 GUI 主题(config/numen/ui.json)。
        com.dwinovo.numen.api.client.screen.UiTheme.init(
                FabricLoader.getInstance().getConfigDir().resolve(Constants.CONFIG_ROOT));

        // G → companion roster panel (chat entry + settings/reset live in there).
        KeyBindingHelper.registerKeyBinding(com.dwinovo.numen.api.client.NumenKeys.OPEN_ROSTER);
        // R(hold) → companion wheel; Y → quick chat; V(hold) → quick voice.
        KeyBindingHelper.registerKeyBinding(com.dwinovo.numen.api.client.NumenKeys.COMPANION_WHEEL);
        KeyBindingHelper.registerKeyBinding(com.dwinovo.numen.api.client.NumenKeys.TALK_COMPANION);
        KeyBindingHelper.registerKeyBinding(com.dwinovo.numen.api.client.NumenKeys.QUICK_VOICE);

        // HUD: 快捷对话提醒——准星指着同伴时浮「按 [键] 对话」;
        // toast 横幅同层(错误分类话术等,玩家不开面板也看得见)。
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(
                (g, delta) -> {
                    com.dwinovo.numen.api.client.hud.TalkHint.render(g);
                    com.dwinovo.numen.api.client.hud.NumenHudToasts.render(g);
                    com.dwinovo.numen.api.client.notify.MessageNotices.renderHud(g);
                });
        // 消息通知开着界面时画在界面上面、接点击(界面的事件每次 init 重置,所以在 init 之后挂)
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterRender(screen).register(
                    (s, g, mouseX, mouseY, delta) ->
                            com.dwinovo.numen.api.client.notify.MessageNotices.renderOver(g, mouseX, mouseY));
            net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(screen).register(
                    (s, mouseX, mouseY, button) ->
                            !com.dwinovo.numen.api.client.notify.MessageNotices.click(mouseX, mouseY, button));
        });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(client -> {
                    com.dwinovo.numen.api.client.NumenKeys.tick();
                    com.dwinovo.numen.api.client.agent.RemoteCompanions.tick();
                });

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT
                .register((handler, client) -> {
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
                });

        // 世界空间的覆盖层(半透明方块阶段之后)。
        // 头顶气泡不在这里——它走玩家实体渲染尾部(MixinPlayerRenderer),
        // 与名牌同管线,光影下才正常。
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_TRANSLUCENT
                .register(context -> {
                    if (context.matrixStack() != null) {
                        com.dwinovo.numen.api.client.consent.ConsentOutlines.render(
                                context.matrixStack(), context.camera());
                    }
                });
    }
}
