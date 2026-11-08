package com.dwinovo.numen.api.plugin;

import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.store.JsonDoc;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 服主关掉的插件名单,{@code config/numen/server/plugins.json}:{@code {"disabled": ["tlm", ...]}}。插件的 id 在名单里,
 * 下次启动时它的 setup 就不跑。名单只有这一处;文件读不通(不是 JSON、版本比本程序新)就如实抛出,启动因此失败——
 * 悄悄当成"全开"会把服主关掉的插件又打开。
 */
public final class PluginSwitches {

    public static final String FILE = "plugins.json";

    private final JsonDoc doc;

    public PluginSwitches(JsonDoc doc) {
        this.doc = doc;
    }

    /** 服务器配置目录里的那一份。 */
    public static PluginSwitches onDisk() {
        var server = Homes.serverHome();
        return new PluginSwitches(new JsonDoc(server.file(FILE), JsonDoc.Format.of(1), server.io()));
    }

    /** 名单上的插件 id;文件不在是空。 */
    public Set<String> disabled() {
        Set<String> out = new LinkedHashSet<>();
        JsonObject root = doc.read().orElse(null);
        if (root != null && root.get("disabled") instanceof JsonArray ids) {
            for (JsonElement id : ids) {
                out.add(id.getAsString());
            }
        }
        return out;
    }

    /** 整份写回。 */
    public void write(Set<String> disabled) {
        JsonArray ids = new JsonArray();
        disabled.forEach(ids::add);
        JsonObject root = new JsonObject();
        root.add("disabled", ids);
        doc.write(root);
    }
}
