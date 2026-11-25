package com.dwinovo.numen.api.config;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.store.JsonDoc;
import com.google.gson.JsonObject;

/**
 * 服主管的服务器设置,{@code config/numen/server/server.json}。管理员拿编辑器改,{@link #reload}(随 {@code /reload})读到。
 * 读不通就当缺省并记一笔,不阻断服务器起来。
 *
 * <ul>
 *   <li>{@code maxCompanions}:每位玩家最多召唤几只同伴(0 = 不限,缺省)。召唤只要一个名字,这是唯一的上限。</li>
 * </ul>
 */
public final class ServerSettings {

    public static final String FILE = "server.json";

    private final JsonDoc doc;
    private volatile int maxCompanions;

    public ServerSettings(Home server) {
        this.doc = new JsonDoc(server.file(FILE), JsonDoc.Format.of(1), server.io());
        reload();
    }

    /** 重新读文件;文件不在就写一份缺省的,免得管理员不知道有这一项。 */
    public synchronized void reload() {
        maxCompanions = 0;
        try {
            JsonObject root = doc.read().orElse(null);
            if (root == null) {
                doc.write(toJson());
                return;
            }
            if (root.get("maxCompanions") instanceof com.google.gson.JsonPrimitive max && max.isNumber()) {
                maxCompanions = Math.max(0, max.getAsInt());
            }
        } catch (RuntimeException unreadable) {
            Constants.LOG.warn("[numen-server] {} cannot be read, using default settings: {}", doc.file(), unreadable.toString());
        }
    }

    /** 每位玩家最多召唤几只同伴;0 = 不限。 */
    public int maxCompanions() {
        return maxCompanions;
    }

    /** 改每人最多几只并写回文件。 */
    public synchronized void setMaxCompanions(int max) {
        this.maxCompanions = Math.max(0, max);
        doc.write(toJson());
    }

    private JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("maxCompanions", maxCompanions);
        return root;
    }
}
