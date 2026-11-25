package com.dwinovo.numen.api.mind;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Switches;
import com.dwinovo.numen.api.store.JsonDoc;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 主人在这台电脑上的偏好({@code prefs.json}):主动性档位——低 = 世界变化知道得及时、更费 token,高 = 知道得晚、更省——他名下所有同伴共用;
 * 以及各库条目的开关({@link Switches}):他关掉的条目记在这里,关与不关是他自己的事。
 *
 * <p>线程:任何线程;改动在同一把锁里。
 */
public final class ClientPrefs implements Switches {

    private final JsonDoc doc;
    private int initiative = EventQueue.DEFAULT_LEVEL;
    /** 关掉的条目:库的目录名 → 条目名({@code 命名空间:名字})。 */
    private final Map<String, Set<String>> disabled = new TreeMap<>();

    public ClientPrefs(Home home) {
        this.doc = new JsonDoc(home.file("prefs.json"), JsonDoc.Format.of(1), home.io());
        try {
            doc.read().ifPresent(o -> {
                if (o.has("initiative")) {
                    initiative = EventQueue.clampLevel(o.get("initiative").getAsInt());
                }
                if (o.get("disabled") instanceof JsonObject off) {
                    off.entrySet().forEach(e -> e.getValue().getAsJsonArray()
                            .forEach(id -> disabled.computeIfAbsent(e.getKey(), k -> new TreeSet<>()).add(id.getAsString())));
                }
            });
        } catch (RuntimeException unreadable) {
            Constants.LOG.warn("[numen-prefs] {} cannot be read, using defaults: {}", doc.file().getFileName(),
                    unreadable.toString());
        }
    }

    /** 主动性档位,1 到 10。 */
    public synchronized int initiative() {
        return initiative;
    }

    public synchronized void setInitiative(int level) {
        initiative = EventQueue.clampLevel(level);
        doc.write(toJson());
    }

    @Override
    public synchronized boolean enabled(String library, EntryId id) {
        return !disabled.getOrDefault(library, Set.of()).contains(id.toString());
    }

    @Override
    public synchronized void set(String library, EntryId id, boolean enabled) {
        Set<String> off = disabled.computeIfAbsent(library, k -> new TreeSet<>());
        if (enabled ? off.remove(id.toString()) : off.add(id.toString())) {
            doc.write(toJson());
        }
    }

    /** 界面读的整份偏好。 */
    public synchronized JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("initiative", initiative);
        JsonObject off = new JsonObject();
        disabled.forEach((library, ids) -> {
            if (!ids.isEmpty()) {
                JsonArray names = new JsonArray();
                ids.forEach(names::add);
                off.add(library, names);
            }
        });
        if (off.size() > 0) {
            o.add("disabled", off);
        }
        return o;
    }
}
