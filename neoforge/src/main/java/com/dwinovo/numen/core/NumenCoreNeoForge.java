package com.dwinovo.numen.core;

import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.core.debug.DebugCommands;
import com.dwinovo.numen.core.debug.PathDebugRenderer;
import com.dwinovo.numen.core.pathing.cache.PathCaches;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.core.task.ScanBlocksJob;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Path;

/**
 * NeoForge entry point for the numen-core tool pack. Registers the tools and
 * task runners into the numen-api engine, then wires the server-tick work its
 * tools need (budget-sliced block scans, the off-thread pathfinder's chunk
 * snapshots). The engine itself is brought up by the separate numen-api mod,
 * which core depends on.
 */
@Mod(Constants.MOD_ID)
public class NumenCoreNeoForge {

    public NumenCoreNeoForge(IEventBus eventBus, ModContainer container) {
        NumenCore.init();

        NeoForge.EVENT_BUS.addListener(NumenCoreNeoForge::onServerTickPost);
        // Release pathfinding chunk-ref snapshots when the server stops (don't pin an old world).
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> PathCaches.dropAll());
        // Debug verbs merged into the /numen root registered by the engine mod.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent e) ->
                DebugCommands.register(e.getDispatcher()));

        // Client-only: declare core's built-in skills, read in place from the
        // skills/ dir bundled in this jar. Skills feed the client-side LLM, so
        // this never runs on a dedicated server.
        if (FMLLoader.getCurrent().getDist() == Dist.CLIENT) {
            declareBundledSkills();
        }

        Constants.LOG.info("numen-core initialised on NeoForge.");
    }

    private static void declareBundledSkills() {
        // Resolve the jar-internal skills/ dir via the classloader (loader-agnostic and stable
        // across MC versions, unlike NeoForge's shifting IModFile API): the resource URL maps to a
        // Path on the mod's (union) filesystem, which the engine reads in place.
        try {
            java.net.URL url = NumenCoreNeoForge.class.getResource("/skills");
            if (url != null) {
                SkillRegistry.instance().declareBundled(Path.of(url.toURI()));
                return;
            }
        } catch (Exception ex) {
            Constants.LOG.warn("[numen-core] failed to resolve bundled skills/: {}", ex.toString());
        }
        Constants.LOG.warn("[numen-core] no bundled skills/ dir found in jar");
    }

    private static void onServerTickPost(ServerTickEvent.Post event) {
        // 排程机器的心跳随机器归了 numen-api;core 只 tick 自己的工具配套。
        ScanBlocksJob.tick(event.getServer());
        PathCaches.serverTick(event.getServer());
        // Debug particles for pathing state, sent only to players with debug on.
        PathDebugRenderer.serverTick(event.getServer());
    }
}
