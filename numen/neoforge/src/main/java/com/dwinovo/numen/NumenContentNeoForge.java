package com.dwinovo.numen;

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
        NumenContent.registerNetwork();

        // 内嵌的联动模组:装了目标模组才接上,没装当不存在。见 plugins.Builtin。
        com.dwinovo.numen.plugins.Builtin.registerAll(eventBus);

        NeoForge.EVENT_BUS.addListener(NumenContentNeoForge::onServerTickPost);
        // Numen 的自带内容(技能、Lua 模块、默认人设)和联动的一样经插件那扇门交出去:jar 里的 numen/ 目录,
        // 服务器起来时同步进 config/numen/server/。Numen 是插件之一,没有特殊待遇。
        declareBundledContent();

        Constants.LOG.info("Numen content initialised on NeoForge.");
    }

    private static void declareBundledContent() {
        Path root = ModJar.find("numen");
        if (root == null) {
            throw new IllegalStateException("[numen-content] no bundled numen/ dir found in jar");
        }
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.api.NumenPlugins.NUMEN, numen -> numen.bundleContent(root));
    }

    private static void onServerTickPost(ServerTickEvent.Post event) {
        // 排程机器的心跳随机器归了 Numen API;Numen 只 tick 自己的工具配套。
        BlockSearch.tick(event.getServer());
        // 寻路调试:把在走的同伴的路发给开了调试的玩家。
        PathDebugRenderer.serverTick(event.getServer());
    }
}
