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

        // G → companion roster panel (chat entry + settings/reset live in there).
        KeyBindingHelper.registerKeyBinding(com.dwinovo.numen.client.NumenKeys.OPEN_ROSTER);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(client -> {
                    com.dwinovo.numen.client.NumenKeys.tick();
                    com.dwinovo.numen.client.hud.NumenToasts.tick();
                    com.dwinovo.numen.client.agent.AgentLoopRegistry.tickAll();
                });

        // HUD: advancement-style activity toasts (top-right) when not watching a panel.
        // 1.21.5 predates the HudElementRegistry layer API; use the classic HudRenderCallback.
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(
                (g, delta) -> com.dwinovo.numen.client.hud.NumenToasts.render(g));

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT
                .register((handler, client) -> {
                    com.dwinovo.numen.client.data.ClientNumenInventory.clear();
                    com.dwinovo.numen.client.hud.NumenToasts.clear();
                    com.dwinovo.numen.client.agent.ClientDeaths.clearAll();
                    com.dwinovo.numen.client.debug.PathDebugState.clear();
                });

        // 寻路调试覆盖层:世界空间画线(半透明方块阶段之后)。
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_TRANSLUCENT
                .register(context -> {
                    if (context.matrixStack() != null) {
                        com.dwinovo.numen.client.debug.PathDebugRenderer.render(
                                context.matrixStack(), context.camera());
                    }
                });
    }
}
