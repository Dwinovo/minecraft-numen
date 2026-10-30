package com.dwinovo.numen.plugins.kubejs;

import com.dwinovo.numen.api.sdk.ApiFunction;
import dev.latvian.mods.rhino.util.HideFromJS;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 一组函数的声明:{@code group.fn(名字, fn => …)} 一个个加。 */
public final class ScriptGroup {

    private final String namespace;
    private final String group;
    private final List<ApiFunction> functions = new ArrayList<>();

    ScriptGroup(String namespace, String group) {
        this.namespace = namespace;
        this.group = group;
    }

    /**
     * 一个函数:{@code declare} 里写它的描述和执行体({@link ScriptFn})。
     *
     * @throws IllegalArgumentException 描述不全:没写说明、参数没写说明、没写执行体
     */
    public void fn(String name, Consumer<ScriptFn> declare) {
        ScriptFn fn = new ScriptFn(namespace, group, name);
        declare.accept(fn);
        functions.add(fn.build());
    }

    @HideFromJS
    List<ApiFunction> functions() {
        if (functions.isEmpty()) {
            throw new IllegalArgumentException("the group " + namespace + "." + group + " has no function");
        }
        return List.copyOf(functions);
    }
}
