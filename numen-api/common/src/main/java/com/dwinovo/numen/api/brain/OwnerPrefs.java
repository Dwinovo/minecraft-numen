package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Switches;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.dwinovo.numen.api.store.JsonDoc;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主人在服务器上的偏好({@code players/<主人>/prefs.json}):主动性档位——低 = 世界变化知道得及时、更费 token,
 * 高 = 知道得晚、更省——每位主人一个值,他名下所有同伴共用;以及各库条目的开关({@link Switches}):他关掉的内置条目
 * 记在这里,内置的目录他只读,关与不关是他自己的事。
 * 同一位主人永远是同一份(同伴每轮现问、界面经库协议改它,读写的是同一个对象);关服时放手。
 *
 * <p>线程:服务端主线程。
 */
public final class OwnerPrefs implements Switches {

    private static final Map<UUID, OwnerPrefs> OPEN = new ConcurrentHashMap<>();

    static {
        ServerLifecycle.onStopped(OPEN::clear);
    }

    private final JsonDoc doc;
    private int initiative = EventQueue.DEFAULT_LEVEL;
    /** 多人服上"服主看得到你的 key"的提醒已经给过他一次。 */
    private boolean keyNoticeShown;
    /** 关掉的条目:库的目录名 → 条目名({@code 命名空间:名字})。 */
    private final Map<String, Set<String>> disabled = new TreeMap<>();

    OwnerPrefs(JsonDoc doc) {
        this.doc = doc;
        try {
            doc.read().ifPresent(o -> {
                if (o.has("initiative")) {
                    initiative = EventQueue.clampLevel(o.get("initiative").getAsInt());
                }
                keyNoticeShown = o.has("keyNoticeShown") && o.get("keyNoticeShown").getAsBoolean();
                if (o.get("disabled") instanceof JsonObject off) {
                    off.entrySet().forEach(e -> e.getValue().getAsJsonArray()
                            .forEach(id -> disabled.computeIfAbsent(e.getKey(), k -> new TreeSet<>()).add(id.getAsString())));
                }
            });
        } catch (RuntimeException unreadable) {
            Constants.LOG.warn("[numen-prefs] {} 读不了,用默认值: {}", doc.file().getFileName(), unreadable.toString());
        }
    }

    public static OwnerPrefs of(MinecraftServer server, UUID owner) {
        return OPEN.computeIfAbsent(owner, id -> {
            Home home = Homes.of(server).owner(id);
            return new OwnerPrefs(new JsonDoc(home.file("prefs.json"), JsonDoc.Format.of(1), home.io()));
        });
    }

    /** 主动性档位,1 到 10。 */
    public int initiative() {
        return initiative;
    }

    public synchronized void setInitiative(int level) {
        initiative = EventQueue.clampLevel(level);
        doc.write(toJson());
    }

    public synchronized boolean keyNoticeShown() {
        return keyNoticeShown;
    }

    public synchronized void setKeyNoticeShown() {
        keyNoticeShown = true;
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

    public synchronized JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("initiative", initiative);
        if (keyNoticeShown) {
            o.addProperty("keyNoticeShown", true);
        }
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
