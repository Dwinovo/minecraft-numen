package com.dwinovo.numen.plugins.kubejs;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.plugin.PluginRoster;
import dev.latvian.mods.kubejs.script.ScriptType;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 脚本插件和花名册之间的桥:服务端脚本每加载完一次(启动、{@code /reload}),上一批脚本插件整批撤回,再按新脚本的声明一个个登记。
 * 走的是花名册对所有插件一视同仁的那一对操作({@code withdraw} 与 {@code NumenPlugins.register}),来源记为
 * {@link PluginRoster.Source#SCRIPT}:花名册里有它们各自的一行,开关、出错隔离、体检和 Java 插件一样。没有专为 KubeJS 开的路。
 *
 * <p>脚本删掉了、改掉了的函数,撤回之后就不在她的 API 里;脚本整个删了,上一批撤回之后没有新的,她的清单也就回到没有脚本的样子。
 * 一个脚本插件声明或登记失败,花名册里记为失败、原因写进 KubeJS 控制台,别的脚本插件照常。
 */
final class ScriptPlugins {

    /** 上一批登记的脚本插件的 id(含登记失败的,它们在花名册里也有一行)。 */
    private static final Set<String> LAST = new LinkedHashSet<>();

    private ScriptPlugins() {}

    static synchronized void reload() {
        try {
            for (String id : LAST) {
                NumenPlugins.roster().withdraw(id);
            }
            LAST.clear();
            RegisterKubeEvent event = new RegisterKubeEvent();
            NumenEvents.REGISTER.post(ScriptType.SERVER, event);
            event.plugins().forEach(ScriptPlugins::install);
        } catch (RuntimeException e) {
            // 撤回或发事件本身出了事:不能带倒 KubeJS 的这次脚本重载,原因给脚本作者看
            ScriptType.SERVER.console.error("Numen could not reload the script plugins: " + e.getMessage(), e);
        }
    }

    private static void install(ScriptPlugin plugin) {
        String id = plugin.namespace();
        try {
            NumenPlugins.admit(PluginRoster.Source.SCRIPT, id, "");
        } catch (IllegalArgumentException taken) {
            ScriptType.SERVER.console.error("Numen plugin '" + id + "' was not set up: " + taken.getMessage(), taken);
            return;
        }
        LAST.add(id);
        if (plugin.failure() != null) {
            NumenPlugins.failed(id, plugin.failure());
            return;
        }
        try {
            NumenPlugins.register(id, plugin::setup);
        } catch (RuntimeException e) {
            NumenPlugins.failed(id, e);
            ScriptType.SERVER.console.error("Numen plugin '" + id + "' was not set up: " + e.getMessage(), e);
        }
    }
}
