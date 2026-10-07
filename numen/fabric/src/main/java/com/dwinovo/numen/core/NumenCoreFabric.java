package com.dwinovo.numen.core;

import com.dwinovo.numen.core.debug.DebugCommands;
import com.dwinovo.numen.core.debug.PathDebugRenderer;
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

        // core 的自带技能和联动的一样经插件那扇门交出去,原地读 jar 里的 skills/ 目录(玩家在
        // config/numen/skills 下放同名目录就能盖过它)。技能喂的是主人客户端上的大脑,门在客户端接上时
        // 才声明(NumenPlugins.bindClient);专用服务器上没人接,它就一直攒着。
        java.nio.file.Path skills = ModJar.find("skills");
        if (skills != null) {
            com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.api.NumenPlugins.NUMEN, numen -> numen.bundleSkills(skills));
        } else {
            Constants.LOG.warn("[numen-core] no bundled skills/ dir found in jar");
        }

        // core 的内置 Lua 模块同样经插件那扇门交出去,原地读 jar 里的 modules/ 目录。跑程序的大脑在哪一侧都要它们(主人客户端;
        // 评测与 GameTest 在服务端),所以直接登记,不等客户端。
        java.nio.file.Path modules = ModJar.find("modules");
        if (modules == null) {
            throw new IllegalStateException("[numen-core] no bundled modules/ dir found in jar");
        }
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.api.NumenPlugins.NUMEN, numen -> numen.bundleModules(modules));

        // 排程机器的心跳随机器归了 numen-api;core 只 tick 自己的工具配套。
        // Advance budget-sliced block searches each tick (and sweep their shared index).
        ServerTickEvents.END_SERVER_TICK.register(BlockSearch::tick);
        // Route plans (route plan): poll finished searches and reply.
        ServerTickEvents.END_SERVER_TICK.register(com.dwinovo.numen.core.nav.RouteQueries::serverTick);
        // Debug particles for pathing state, sent only to players with debug on.
        ServerTickEvents.END_SERVER_TICK.register(PathDebugRenderer::serverTick);
        // Debug verbs merged into the /numen root registered by the engine mod.
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> DebugCommands.register(dispatcher));

        Constants.LOG.info("numen-core initialised on Fabric.");
    }
}
