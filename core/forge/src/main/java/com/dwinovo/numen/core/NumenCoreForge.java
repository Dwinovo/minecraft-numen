package com.dwinovo.numen.core;

import com.dwinovo.numen.core.debug.DebugCommands;
import com.dwinovo.numen.core.debug.PathDebugRenderer;
import com.dwinovo.numen.core.scan.BlockSearch;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Path;

/**
 * Forge entry point for the numen-core tool pack. Registers the tools and task
 * runners into the numen-api engine, then wires the server-tick work its tools
 * need (budget-sliced block scans, the off-thread pathfinder's chunk snapshots).
 * The engine itself is brought up by the separate numen-api mod, which core
 * depends on.
 *
 * <p>Forge keeps separate mod and game event buses — per-tick / world lifecycle
 * events go on {@link MinecraftForge#EVENT_BUS}.
 */
@Mod(Constants.MOD_ID)
public class NumenCoreForge {

    public NumenCoreForge() {
        NumenCore.init();

        // 内嵌的联动模组:装了目标模组才接上,没装当不存在。见 plugins.Builtin。
        com.dwinovo.numen.plugins.Builtin.registerAll(
                net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());

        MinecraftForge.EVENT_BUS.addListener(NumenCoreForge::onServerTickPost);
        // Debug verbs merged into the /numen root registered by the engine mod.
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.RegisterCommandsEvent e) ->
                DebugCommands.register(e.getDispatcher()));

        // core 的自带技能和联动的一样经插件那扇门交出去,原地读 jar 里的 skills/ 目录。技能喂的是主人客户端上的
        // 大脑,门在客户端接上时才声明(NumenPlugins.bindClient);专用服务器上没人接,它就一直攒着。
        declareBundledSkills();
        declareBundledModules();

        Constants.LOG.info("numen-core initialised on Forge.");
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

    private static void onServerTickPost(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        // 排程机器的心跳随机器归了 numen-api;core 只 tick 自己的工具配套。
        BlockSearch.tick(server);
        // Route plans (route plan): poll finished searches and reply.
        com.dwinovo.numen.core.nav.RouteQueries.serverTick(server);
        // Debug particles for pathing state, sent only to players with debug on.
        PathDebugRenderer.serverTick(server);
    }
}
