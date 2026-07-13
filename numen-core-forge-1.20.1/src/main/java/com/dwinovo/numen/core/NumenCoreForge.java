package com.dwinovo.numen.core;

import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.core.net.CancelTasksPayload;
import com.dwinovo.numen.core.net.ExecuteToolPayload;
import com.dwinovo.numen.core.net.TaskResultPayload;
import com.dwinovo.numen.core.pathing.cache.PathCaches;
import com.dwinovo.numen.core.task.CompanionTickDispatcher;
import com.dwinovo.numen.core.task.ScanBlocksJob;
import com.dwinovo.numen.platform.Services;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.nio.file.Path;

/**
 * Forge entry point for the numen-core tool pack. Registers the tools and
 * task runners into the numen-api engine, then wires the server-tick work its
 * tools need (budget-sliced block scans, the off-thread pathfinder's chunk
 * snapshots). The engine itself is brought up by the separate numen-api mod,
 * which core depends on.
 */
@Mod(Constants.MOD_ID)
public class NumenCoreForge {

    public NumenCoreForge() {
        NumenCore.init();

        // Register core network payloads with the numen-api network service

        MinecraftForge.EVENT_BUS.addListener(NumenCoreForge::onServerTickPost);
        // Snapshot before levels/entities are torn down; removal callbacks during
        // shutdown must not convert resumable work into cancellations.
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent e) ->
                CompanionTickDispatcher.prepareForShutdown(e.getServer()));
        // Release pathfinding chunk-ref snapshots when the server stops (don't pin an old world).
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> {
            CompanionTickDispatcher.shutdown(e.getServer());
            PathCaches.dropAll();
        });

        // Client-only: declare core's built-in skills, read in place from the
        // skills/ dir bundled in this jar. Skills feed the client-side LLM, so
        // this never runs on a dedicated server.
        if (FMLEnvironment.dist.isClient()) {
            declareBundledSkills();
        }

        Constants.LOG.info("numen-core initialised on Forge.");
    }

    private static void declareBundledSkills() {
        Path root = ModList.get().getModFileById(Constants.MOD_ID).getFile().findResource("skills");
        if (root != null) {
            SkillRegistry.instance().declareBundled(root);
        } else {
            Constants.LOG.warn("[numen-core] no bundled skills/ dir found in jar");
        }
    }

    private static void onServerTickPost(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            CompanionTickDispatcher.tick(event.getServer());
            ScanBlocksJob.tick(event.getServer());
            PathCaches.serverTick(event.getServer());
        }
    }
}
