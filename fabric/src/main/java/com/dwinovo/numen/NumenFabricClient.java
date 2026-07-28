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
        Path numenConfigRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Constants.MOD_ID);
        Path skillsDir = numenConfigRoot.resolve("skills");

        // 读回上次选择的 GUI 主题(config/numen/ui.json)。
        com.dwinovo.numen.client.screen.UiTheme.init(
                Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("numen"));

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

        // GUI 圆角 SDF shader;注册失败仅告警——RoundRect 会自动降级成方角 fill。
        net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback.EVENT
                .register(context -> {
                    try {
                        context.register(
                                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "rendertype_round_rect"),
                                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR,
                                com.dwinovo.numen.client.ui.RoundRect::setShader);
                    } catch (Exception e) {
                        Constants.LOG.warn("round rect shader failed to load, falling back to square corners", e);
                    }
                });

        // G → companion roster panel (chat entry + settings/reset live in there).
        KeyBindingHelper.registerKeyBinding(com.dwinovo.numen.client.NumenKeys.OPEN_ROSTER);
        // V → face-to-face chat with the companion under the crosshair.
        KeyBindingHelper.registerKeyBinding(com.dwinovo.numen.client.NumenKeys.TALK_COMPANION);

        // HUD: 快捷对话提醒——准星指着同伴时浮「按 [键] 对话」。
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(
                (g, delta) -> com.dwinovo.numen.client.hud.TalkHint.render(g));
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(client -> {
                    com.dwinovo.numen.client.NumenKeys.tick();
                    com.dwinovo.numen.client.agent.AgentLoopRegistry.tickAll();
                });

        // @名字 消息 → 路由给同伴,不上公屏(名字没命中照常发送)。
        net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.ALLOW_CHAT
                .register(message -> !com.dwinovo.numen.client.chat.NumenChatRouter.route(message));

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT
                .register((handler, client) -> {
                    com.dwinovo.numen.client.data.ClientNumenInventory.clear();
                    com.dwinovo.numen.client.agent.KnownSkins.clear();
                    com.dwinovo.numen.client.hud.SpeechBubbles.clear();
                    com.dwinovo.numen.client.chat.CompanionCompletions.clear();
                    com.dwinovo.numen.client.agent.ClientDeaths.clearAll();
                    com.dwinovo.numen.client.debug.PathDebugState.clear();
                });

        // 世界空间覆盖层(半透明方块阶段之后):寻路调试线 + 同伴头顶气泡。
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_TRANSLUCENT
                .register(context -> {
                    if (context.matrixStack() != null) {
                        com.dwinovo.numen.client.debug.PathDebugRenderer.render(
                                context.matrixStack(), context.camera());
                        com.dwinovo.numen.client.hud.SpeechBubbleRenderer.render(
                                context.matrixStack(), context.camera());
                    }
                });
    }
}
