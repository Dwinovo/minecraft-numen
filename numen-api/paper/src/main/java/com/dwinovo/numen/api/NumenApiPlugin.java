package com.dwinovo.numen.api;

import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.platform.PaperPermissions;
import com.dwinovo.numen.api.platform.PluginJars;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.translation.GlobalTranslator;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Numen API 在 Paper 上的入口,对应 NeoForge 的 {@code NumenMod} 与 Fabric 的 {@code NumenMod}:
 * 登记网络、挂事件与指令,再跑 common 的 {@link CommonClass#init}。平台服务(配置、网络、方块读数……)经
 * {@code META-INF/services} 由 {@code Services} 取到,不在这里手接。
 */
public final class NumenApiPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        NumenNetwork.register();
        PaperPermissions.declare();

        getServer().getPluginManager().registerEvents(new PaperEvents(), this);
        getServer().getPluginManager().registerEvents(new PaperBodyHooks(), this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> PaperCommands.register(event.registrar()));

        CommonClass.init();

        // 翻译源在服务器起好之后才登记:语言表按语言缓存,要等所有带 Numen 内容的插件都启用了才读,免得缓存里缺它们的键
        NumenTranslator translator = new NumenTranslator();
        ServerLifecycle.onStarted(server -> GlobalTranslator.translator().addSource(translator));
        ServerLifecycle.onStopped(() -> GlobalTranslator.translator().removeSource(translator));
        Constants.LOG.info("Numen API initialised on Paper.");
    }

    /** 服务器停了:属于那个世界的进程内状态一起作废(见 {@link ServerLifecycle})。 */
    @Override
    public void onDisable() {
        ServerLifecycle.fireStopped();
        PluginJars.closeAll();
    }
}
