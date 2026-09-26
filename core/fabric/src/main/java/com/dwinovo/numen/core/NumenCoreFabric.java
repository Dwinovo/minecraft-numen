package com.dwinovo.numen.core;

import com.dwinovo.numen.core.debug.DebugCommands;
import com.dwinovo.numen.core.debug.PathDebugRenderer;
import com.dwinovo.numen.core.pathing.cache.PathCaches;
import com.dwinovo.numen.core.scan.BlockSearch;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Fabric entry point for the numen-core tool pack. Registers the tools and task
 * runners into the numen-api engine, then wires the server-tick work its tools
 * need (budget-sliced block scans, the off-thread pathfinder's chunk snapshots).
 * The engine itself (entity, agent loop, UI, network) is brought up by the
 * separate numen-api mod, which core depends on.
 */
public class NumenCoreFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        NumenCore.init();

        // 内嵌的联动模组:装了目标模组才接上,没装当不存在。见 plugins.Builtin。
        com.dwinovo.numen.plugins.Builtin.registerAll();

        // 排程机器的心跳随机器归了 numen-api;core 只 tick 自己的工具配套。
        // Advance budget-sliced block searches each tick (and sweep their shared index).
        ServerTickEvents.END_SERVER_TICK.register(BlockSearch::tick);
        // Read-only route queries (move route): poll finished searches and reply.
        ServerTickEvents.END_SERVER_TICK.register(
                com.dwinovo.numen.core.pathing.plan.RoutePlanner::serverTick);
        // Snapshot loaded chunks near companions for the off-thread planner to read live.
        ServerTickEvents.END_SERVER_TICK.register(PathCaches::serverTick);
        // Debug particles for pathing state, sent only to players with debug on.
        ServerTickEvents.END_SERVER_TICK.register(PathDebugRenderer::serverTick);
        // Debug verbs merged into the /numen root registered by the engine mod.
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> DebugCommands.register(dispatcher));

        Constants.LOG.info("numen-core initialised on Fabric.");
    }
}
