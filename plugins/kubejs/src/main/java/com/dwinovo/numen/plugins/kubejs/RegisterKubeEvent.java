package com.dwinovo.numen.plugins.kubejs;

import dev.latvian.mods.kubejs.event.KubeEvent;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.rhino.util.HideFromJS;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code NumenEvents.register} 的事件:脚本在里面一个名字空间一个名字空间地声明插件。声明的结果只是记录({@link ScriptPlugin}),
 * 事件发完之后由 {@link ScriptPlugins} 经 {@code NumenPlugins.register} 一个一个登记。
 */
public final class RegisterKubeEvent implements KubeEvent {

    private final Map<String, ScriptPlugin> declared = new LinkedHashMap<>();

    /**
     * 声明一个插件:{@code namespace} 是它在她的程序里的名字空间(她调 {@code <namespace>.<组>.<函数>}),{@code declare} 里往它加东西。
     * 同一个名字空间再声明一次是往同一个插件里加。{@code declare} 抛了,这个插件记为失败、不登记,别的插件不受影响,错误写进 KubeJS 控制台。
     */
    public void plugin(String namespace, Consumer<ScriptPlugin> declare) {
        ScriptPlugin plugin = declared.computeIfAbsent(namespace, ScriptPlugin::new);
        try {
            declare.accept(plugin);
        } catch (RuntimeException e) {
            plugin.failed(e);
            ScriptType.SERVER.console.error("Numen plugin '" + namespace + "' was not set up: " + e.getMessage(), e);
        }
    }

    @HideFromJS
    Collection<ScriptPlugin> plugins() {
        return declared.values();
    }
}
