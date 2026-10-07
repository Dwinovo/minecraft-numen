package com.dwinovo.numen;

import com.dwinovo.numen.debug.PathDebugPayload;
import com.dwinovo.numen.debug.PathDebugRenderer;
import com.dwinovo.numen.api.task.CompanionTickDispatcher;
import com.dwinovo.numen.scan.BlockSearch;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Path;

/**
 * NeoForge entry point for the Numen tool pack. Registers the tools and
 * task runners into Numen API, then wires the server-tick work its
 * tools need (budget-sliced block scans, the off-thread pathfinder's chunk
 * snapshots). Numen API itself is brought up by the separate Numen API mod,
 * which Numen depends on.
 */
@Mod(Constants.MOD_ID)
public class NumenContentNeoForge {

    public NumenContentNeoForge(IEventBus eventBus, ModContainer container) {
        NumenContent.init();

        // 内嵌的联动模组:装了目标模组才接上,没装当不存在。见 plugins.Builtin。
        com.dwinovo.numen.plugins.Builtin.registerAll(eventBus);

        NeoForge.EVENT_BUS.addListener(NumenContentNeoForge::onServerTickPost);
        // 寻路调试的下行包:类型在这里登记,处理体在客户端入口挂上(见 NumenContentNeoForgeClient)。
        eventBus.addListener((net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent e) ->
                e.registrar("1").playToClient(PathDebugPayload.TYPE, PathDebugPayload.STREAM_CODEC,
                        (payload, ctx) -> PathDebugPayload.handle(payload)));

        // Numen 的自带技能和联动的一样经插件那扇门交出去,原地读 jar 里的 skills/ 目录。技能喂的是主人客户端上的
        // 大脑,门在客户端接上时才声明(NumenPlugins.bindClient);专用服务器上没人接,它就一直攒着。
        declareBundledSkills();
        declareBundledModules();

        Constants.LOG.info("Numen content initialised on NeoForge.");
    }

    private static void declareBundledSkills() {
        Path root = ModJar.find("skills");
        if (root != null) {
            com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.api.NumenPlugins.NUMEN, numen -> numen.bundleSkills(root));
        } else {
            Constants.LOG.warn("[numen-content] no bundled skills/ dir found in jar");
        }
    }

    /**
     * Numen 的内置 Lua 模块同样经插件那扇门交出去,原地读 jar 里的 modules/ 目录。跑程序的大脑在哪一侧都要它们(主人客户端;评测与
     * GameTest 在服务端),所以直接登记,不等客户端。
     */
    private static void declareBundledModules() {
        Path root = ModJar.find("modules");
        if (root == null) {
            throw new IllegalStateException("[numen-content] no bundled modules/ dir found in jar");
        }
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.api.NumenPlugins.NUMEN, numen -> numen.bundleModules(root));
    }

    private static void onServerTickPost(ServerTickEvent.Post event) {
        // 排程机器的心跳随机器归了 Numen API;Numen 只 tick 自己的工具配套。
        BlockSearch.tick(event.getServer());
        // Route plans (route plan): poll finished searches and reply.
        com.dwinovo.numen.nav.RouteQueries.serverTick(event.getServer());
        // 寻路调试:把在走的同伴的路发给开了调试的玩家。
        PathDebugRenderer.serverTick(event.getServer());
    }
}
