package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.dwinovo.numen.api.store.JsonDoc;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主人在服务器上的偏好({@code players/<主人>/prefs.json}):循环要读的那几项。现在只有主动性档位——
 * 低 = 世界变化知道得及时、更费 token,高 = 知道得晚、更省——每位主人一个值,他名下所有同伴共用。
 * 同一位主人永远是同一份(同伴每轮现问、界面经库协议改它,读写的是同一个对象);关服时放手。
 *
 * <p>线程:服务端主线程。
 */
public final class OwnerPrefs {

    private static final Map<UUID, OwnerPrefs> OPEN = new ConcurrentHashMap<>();

    static {
        ServerLifecycle.onStopped(OPEN::clear);
    }

    private final JsonDoc doc;
    private int initiative = EventQueue.DEFAULT_LEVEL;

    OwnerPrefs(JsonDoc doc) {
        this.doc = doc;
        try {
            doc.read().ifPresent(o -> {
                if (o.has("initiative")) {
                    initiative = EventQueue.clampLevel(o.get("initiative").getAsInt());
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

    public void setInitiative(int level) {
        initiative = EventQueue.clampLevel(level);
        doc.write(toJson());
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("initiative", initiative);
        return o;
    }
}
