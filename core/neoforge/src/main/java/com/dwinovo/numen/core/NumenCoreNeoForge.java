package com.dwinovo.numen.core;

import com.dwinovo.numen.core.debug.DebugCommands;
import com.dwinovo.numen.core.debug.PathDebugRenderer;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.core.scan.BlockSearch;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
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

        // 内嵌的联动模组:装了目标模组才接上,没装当不存在。见 plugins.Builtin。
        com.dwinovo.numen.plugins.Builtin.registerAll(eventBus);

        NeoForge.EVENT_BUS.addListener(NumenCoreNeoForge::onServerTickPost);
        // Debug verbs merged into the /numen root registered by the engine mod.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent e) ->
                DebugCommands.register(e.getDispatcher()));

        // core 的自带技能和联动的一样经插件那扇门交出去,原地读 jar 里的 skills/ 目录。技能喂的是主人客户端上的
        // 大脑,门在客户端接上时才声明(NumenPlugins.bindClient);专用服务器上没人接,它就一直攒着。
        declareBundledSkills();
        declareBundledModules();

        Constants.LOG.info("numen-core initialised on NeoForge.");
    }

    private static void declareBundledSkills() {
        Path root = ModJar.find("skills");
        if (root != null) {
            com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.api.NumenPlugins.NUMEN, numen -> numen.bundleSkills(root));
        } else {
            Constants.LOG.warn("[numen-core] no bundled skills/ dir found in jar");
        }
    }

    /**
     * core 的内置 Lua 模块同样经插件那扇门交出去,原地读 jar 里的 modules/ 目录。跑程序的大脑在哪一侧都要它们(主人客户端;评测与
     * GameTest 在服务端),所以直接登记,不等客户端。
     */
    private static void declareBundledModules() {
        Path root = ModJar.find("modules");
        if (root == null) {
            throw new IllegalStateException("[numen-core] no bundled modules/ dir found in jar");
        }
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.api.NumenPlugins.NUMEN, numen -> numen.bundleModules(root));
    }

    private static void onServerTickPost(ServerTickEvent.Post event) {
        // 排程机器的心跳随机器归了 numen-api;core 只 tick 自己的工具配套。
        BlockSearch.tick(event.getServer());
        // Route plans (route plan): poll finished searches and reply.
        com.dwinovo.numen.core.nav.RouteQueries.serverTick(event.getServer());
        // Debug particles for pathing state, sent only to players with debug on.
        PathDebugRenderer.serverTick(event.getServer());
    }
}
