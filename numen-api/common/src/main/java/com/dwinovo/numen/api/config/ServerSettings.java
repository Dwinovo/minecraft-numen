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
 *   <li>{@code dailyTokenLimit}:服主的 key 给每位玩家每天能用多少 token(0 = 不限,缺省)。玩家自己的 key 不受限。</li>
 * </ul>
 */
public final class ServerSettings {

    public static final String FILE = "server.json";

    private final JsonDoc doc;
    private volatile long dailyTokenLimit;

    public ServerSettings(Home server) {
        this.doc = new JsonDoc(server.file(FILE), JsonDoc.Format.of(1), server.io());
        reload();
    }

    /** 重新读文件;文件不在就写一份缺省的,免得管理员不知道有这一项。 */
    public synchronized void reload() {
        dailyTokenLimit = 0;
        try {
            JsonObject root = doc.read().orElse(null);
            if (root == null) {
                doc.write(toJson());
                return;
            }
            if (root.get("dailyTokenLimit") instanceof com.google.gson.JsonPrimitive limit && limit.isNumber()) {
                dailyTokenLimit = Math.max(0, limit.getAsLong());
            }
        } catch (RuntimeException unreadable) {
            Constants.LOG.warn("[numen-server] {} 读不了,用缺省设置: {}", doc.file(), unreadable.toString());
        }
    }

    /** 服主的 key 每人每天的 token 上限;0 = 不限。 */
    public long dailyTokenLimit() {
        return dailyTokenLimit;
    }

    private JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("dailyTokenLimit", dailyTokenLimit);
        return root;
    }
}
