package com.dwinovo.numen;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.platform.Services;
import com.dwinovo.numen.debug.PathDebugRenderer;
import com.dwinovo.numen.scan.BlockSearch;
import net.minecraft.server.MinecraftServer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

/**
 * Numen 在 Paper 上的入口,对应 NeoForge 的 {@code NumenContentNeoForge} 与 Fabric 的 {@code NumenContentFabric}:
 * 把工具与任务登记进 Numen API,交出自带内容,再挂上工具要在服务端刻末做的事(分帧的方块搜索、寻路调试),
 * 以及模组上靠 Mixin 挂的那几处在 Paper 上的事件({@link PaperDropHooks}、{@link PaperBlockChanges})。
 * 内嵌联动是模组的东西,Paper 上不存在。Numen API 由另一个插件 {@code numen_api} 带起来。
 */
public final class NumenContentPlugin extends JavaPlugin implements Listener {

    private final PaperBlockChanges blockChanges = new PaperBlockChanges();

    @Override
    public void onEnable() {
        NumenContent.init();
        NumenContent.registerNetwork();

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new PaperDropHooks(), this);
        getServer().getPluginManager().registerEvents(blockChanges, this);

        // Numen 的自带内容(技能、Lua 模块、默认人设)和联动的一样经插件那扇门交出去:jar 里的 numen/ 目录,
        // 服务器起来时同步进 plugins/numen/server/。Numen 是插件之一,没有特殊待遇。
        Path content = Services.platform().modResource(Constants.MOD_ID, "numen");
        if (content == null) {
            throw new IllegalStateException("[numen-content] no bundled numen/ dir found in jar");
        }
        NumenPlugins.register(NumenPlugins.NUMEN, numen -> numen.bundleContent(content));

        Constants.LOG.info("Numen content initialised on Paper.");
    }

    /** 排程机器的心跳随机器归了 Numen API;Numen 只在刻末做自己的工具配套。 */
    @EventHandler
    void onTickEnd(ServerTickEndEvent event) {
        MinecraftServer server = MinecraftServer.getServer();
        blockChanges.flush();   // 这一刻动过的格先报给索引,再推搜索
        BlockSearch.tick(server);
        PathDebugRenderer.serverTick(server);
    }
}
