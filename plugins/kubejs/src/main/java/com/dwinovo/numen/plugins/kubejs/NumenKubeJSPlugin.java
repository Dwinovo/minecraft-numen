package com.dwinovo.numen.plugins.kubejs;

import dev.latvian.mods.kubejs.event.EventGroupRegistry;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.ScriptType;

/**
 * KubeJS 联动的入口:KubeJS 读各模组 jar 根上的 {@code kubejs.plugins.txt} 找到它(KubeJS 在场才会读,所以不需要 Numen 这边另设闸门)。
 * 它做两件事:登记脚本里能用的事件组({@link NumenEvents}),和每次服务端脚本加载完把脚本声明的插件换成新的一批({@link ScriptPlugins})。
 * 启动时的第一次加载与管理员的 {@code /reload} 走的是同一条路。
 */
public final class NumenKubeJSPlugin implements KubeJSPlugin {

    @Override
    public void registerEvents(EventGroupRegistry registry) {
        registry.register(NumenEvents.GROUP);
    }

    @Override
    public void afterScriptsLoaded(ScriptManager manager) {
        if (manager.scriptType == ScriptType.SERVER) {
            ScriptPlugins.reload();
        }
    }
}
