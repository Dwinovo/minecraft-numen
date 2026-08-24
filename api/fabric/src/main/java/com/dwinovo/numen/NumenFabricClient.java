package com.dwinovo.numen;

import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.mcp.client.McpClientManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

import java.nio.file.Path;

public class NumenFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // 下行 payload 的处理体住在客户端源码集,先挂进主源码集的挂点
        com.dwinovo.numen.client.ClientPayloadHandlers.install();
        Path numenConfigRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Constants.MOD_ID);
        Path skillsDir = numenConfigRoot.resolve("skills");

        // 同伴数据的根,以及旧布局的一次性迁移。根从 loader 拿,不问 Minecraft
        // (datagen 里模组照样构造,那时没有 Minecraft 实例)。
        com.dwinovo.numen.client.agent.CompanionHome.init(
                FabricLoader.getInstance().getConfigDir().resolve("numen"));
        com.dwinovo.numen.client.agent.CompanionHome.migrateLegacy();

        // 读回上次选择的 GUI 主题(config/numen/ui.json)。
        com.dwinovo.numen.client.screen.UiTheme.init(
                FabricLoader.getInstance().getConfigDir().resolve("numen"));

        // MCP client: connect to any external MCP servers listed in
        // config/numen/mcp_clients.json and register their tools so the built-in
        // brain can call them. Config dir from the loader (avoids Minecraft timing).
        McpClientManager.initClient(FabricLoader.getInstance().getConfigDir().resolve(Constants.MOD_ID));

        // MCP server: the other direction — stand up a loopback MCP server so an
        // external agent can drive companions directly, bypassing the built-in brain.
        // Off unless enabled in config/numen/mcp_server.json.
        com.dwinovo.numen.mcp.server.NumenMcp.initClient(FabricLoader.getInstance().getConfigDir());

        // Skills live under config/numen/skills. Hook the resource reload
        // pipeline so /reload picks up newly added SKILL.md files without a
        // client restart.
        ResourceLocation skillLoaderId = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "skill_loader");
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public ResourceLocation getFabricId() {
                        return skillLoaderId;
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager rm) {
                        // The engine ships no built-in skills; pick up any SKILL.md the
                        // player (or a tool pack) has placed under config/numen/skills.
                        SkillRegistry.instance().scan(skillsDir);
                    }
                });

        // GUI 圆角 SDF shader:1.21.4 的 ShaderManager 自动扫描编译资源树里的全部
        // shader 配置,fabric 侧无需(也已无)注册 API——RoundRect 按键查表即可。

        // G → companion roster panel (chat entry + settings/reset live in there).
        KeyBindingHelper.registerKeyBinding(com.dwinovo.numen.client.NumenKeys.OPEN_ROSTER);
        // R(hold) → companion wheel; Y → quick chat; V(hold) → quick voice.
        KeyBindingHelper.registerKeyBinding(com.dwinovo.numen.client.NumenKeys.COMPANION_WHEEL);
        KeyBindingHelper.registerKeyBinding(com.dwinovo.numen.client.NumenKeys.TALK_COMPANION);
        KeyBindingHelper.registerKeyBinding(com.dwinovo.numen.client.NumenKeys.QUICK_VOICE);

        // HUD: 快捷对话提醒——准星指着同伴时浮「按 [键] 对话」;
        // toast 横幅同层(错误分类话术等,玩家不开面板也看得见)。
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(
                (g, delta) -> {
                    com.dwinovo.numen.client.hud.TalkHint.render(g);
                    com.dwinovo.numen.client.hud.NumenHudToasts.render(g);
                });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(client -> {
                    com.dwinovo.numen.client.NumenKeys.tick();
                    com.dwinovo.numen.client.agent.AgentLoopRegistry.tickAll();
                });

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT
                .register((handler, client) -> {
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
                });

        // 寻路调试覆盖层:世界空间画线(半透明方块阶段之后)。
        // 头顶气泡不在这里——它走玩家实体渲染尾部(MixinLivingEntityRenderer),
        // 与名牌同管线,光影下才正常。
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_TRANSLUCENT
                .register(context -> {
                    if (context.matrixStack() != null) {
                        com.dwinovo.numen.client.debug.PathDebugRenderer.render(
                                context.matrixStack(), context.camera());
                    }
                });
    }
}
