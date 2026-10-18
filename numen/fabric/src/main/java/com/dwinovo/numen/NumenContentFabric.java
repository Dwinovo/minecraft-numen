package com.dwinovo.numen;

import com.dwinovo.numen.debug.PathDebugPayload;
import com.dwinovo.numen.debug.PathDebugRenderer;
import com.dwinovo.numen.scan.BlockSearch;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Fabric entry point for the Numen tool pack. Registers the tools and task
 * runners into Numen API, then wires the server-tick work its tools
 * need (budget-sliced block scans, the off-thread pathfinder's chunk snapshots).
 * Numen API itself (entity, agent loop, UI, network) is brought up by the
 * separate Numen API mod, which Numen depends on.
 */
public class NumenContentFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        NumenContent.init();

        // 内嵌的联动模组:装了目标模组才接上,没装当不存在。见 plugins.Builtin。
        com.dwinovo.numen.plugins.Builtin.registerAll();

        // Numen 的自带内容(技能、Lua 模块、默认人设)和联动的一样经插件那扇门交出去:jar 里的 numen/ 目录,
        // 服务器起来时同步进 config/numen/server/。Numen 是插件之一,没有特殊待遇。
        java.nio.file.Path content = ModJar.find("numen");
        if (content == null) {
            throw new IllegalStateException("[numen-content] no bundled numen/ dir found in jar");
        }
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.api.NumenPlugins.NUMEN, numen -> numen.bundleContent(content));

        // 排程机器的心跳随机器归了 Numen API;Numen 只 tick 自己的工具配套。
        // Advance budget-sliced block searches each tick (and sweep their shared index).
        ServerTickEvents.END_SERVER_TICK.register(BlockSearch::tick);
        // 寻路调试:把在走的同伴的路发给开了调试的玩家;下行包的类型在这里登记,处理体在客户端入口挂上
        ServerTickEvents.END_SERVER_TICK.register(PathDebugRenderer::serverTick);
        PayloadTypeRegistry.playS2C().register(PathDebugPayload.TYPE, PathDebugPayload.STREAM_CODEC);

        Constants.LOG.info("Numen content initialised on Fabric.");
    }
}
