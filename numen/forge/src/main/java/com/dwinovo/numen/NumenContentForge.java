package com.dwinovo.numen;

import com.dwinovo.numen.debug.PathDebugRenderer;
import com.dwinovo.numen.scan.BlockSearch;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.nio.file.Path;

/** Forge 的模组入口:Numen 的内容接上 Numen API,内嵌的联动过闸门,客户端那一侧只在物理客户端上接。 */
@Mod(Constants.MOD_ID)
public class NumenContentForge {

    public NumenContentForge() {
        boolean installed = NumenContent.init();
        if (installed) {
            NumenContent.registerNetwork();
        }

        // 内嵌的联动模组:装了目标模组才接上,没装当不存在。见 plugins.Builtin。
        com.dwinovo.numen.plugins.Builtin.registerAll(FMLJavaModLoadingContext.get().getModEventBus());

        if (installed) {
            MinecraftForge.EVENT_BUS.addListener(NumenContentForge::onServerTickPost);
        }
        // Numen 的自带内容(技能、Lua 模块、默认人设)和联动的一样经插件那扇门交出去:jar 里的 numen/ 目录,
        // 服务器起来时同步进 config/numen/server/。Numen 是插件之一,没有特殊待遇。
        declareBundledContent();

        if (FMLEnvironment.dist == Dist.CLIENT) {
            NumenContentForgeClient.init();
        }

        Constants.LOG.info("Numen content initialised on Forge.");
    }

    private static void declareBundledContent() {
        Path root = ModJar.find("numen");
        if (root == null) {
            throw new IllegalStateException("[numen-content] no bundled numen/ dir found in jar");
        }
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.api.NumenPlugins.NUMEN, numen -> numen.bundleContent(root));
    }

    private static void onServerTickPost(TickEvent.ServerTickEvent event) {
        // Forge 的 tick 事件一 tick 发两次(START/END),这里只在刻尾
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // 排程机器的心跳随机器归了 Numen API;Numen 只 tick 自己的工具配套。
        BlockSearch.tick(event.getServer());
        // 寻路调试:把在走的同伴的路发给开了调试的玩家。
        PathDebugRenderer.serverTick(event.getServer());
    }
}
