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
 *   <li>{@code maxCompanions}:每位玩家最多召唤几只同伴(0 = 不限,缺省)。召唤只要一个名字,这是唯一的上限。</li>
 *   <li>{@code hardRules}:硬规则(别人的东西碰不得)开不开,缺省开。单机就是你自己,想关就关;</li>
 *   <li>{@code unknownAsOther}:说不清是谁的方块按别人的算。专用服务器缺省是:多人服上说不清的多半是别人装模组前盖的,
 *       让主人答应就等于让想搞破坏的主人一键拆别人的房子;单机与局域网缺省否,照旧问主人。</li>
 * </ul>
 */
public final class ServerSettings {

    public static final String FILE = "server.json";

    private final JsonDoc doc;
    private final boolean dedicated;
    private volatile long dailyTokenLimit;
    private volatile int maxCompanions;
    private volatile boolean hardRules;
    private volatile boolean unknownAsOther;

    /** @param dedicated 是不是专用服务器:有的设置缺省值随它而不同 */
    public ServerSettings(Home server, boolean dedicated) {
        this.doc = new JsonDoc(server.file(FILE), JsonDoc.Format.of(1), server.io());
        this.dedicated = dedicated;
        reload();
    }

    /** 重新读文件;文件不在就写一份缺省的,免得管理员不知道有这一项。 */
    public synchronized void reload() {
        dailyTokenLimit = 0;
        maxCompanions = 0;
        hardRules = true;
        unknownAsOther = dedicated;
        try {
            JsonObject root = doc.read().orElse(null);
            if (root == null) {
                doc.write(toJson());
                return;
            }
            if (root.get("dailyTokenLimit") instanceof com.google.gson.JsonPrimitive limit && limit.isNumber()) {
                dailyTokenLimit = Math.max(0, limit.getAsLong());
            }
            if (root.get("maxCompanions") instanceof com.google.gson.JsonPrimitive max && max.isNumber()) {
                maxCompanions = Math.max(0, max.getAsInt());
            }
            if (root.get("hardRules") instanceof com.google.gson.JsonPrimitive hard && hard.isBoolean()) {
                hardRules = hard.getAsBoolean();
            }
            if (root.get("unknownAsOther") instanceof com.google.gson.JsonPrimitive other && other.isBoolean()) {
                unknownAsOther = other.getAsBoolean();
            }
        } catch (RuntimeException unreadable) {
            Constants.LOG.warn("[numen-server] {} 读不了,用缺省设置: {}", doc.file(), unreadable.toString());
        }
    }

    /** 服主的 key 每人每天的 token 上限;0 = 不限。 */
    public long dailyTokenLimit() {
        return dailyTokenLimit;
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

    /** 硬规则生效。 */
    public boolean hardRules() {
        return hardRules;
    }

    /** 说不清是谁的方块按别人的算。 */
    public boolean unknownAsOther() {
        return unknownAsOther;
    }

    /** 改权限的这两项并写回文件。 */
    public synchronized void setPermission(boolean hardRules, boolean unknownAsOther) {
        this.hardRules = hardRules;
        this.unknownAsOther = unknownAsOther;
        doc.write(toJson());
    }

    private JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("dailyTokenLimit", dailyTokenLimit);
        root.addProperty("maxCompanions", maxCompanions);
        root.addProperty("hardRules", hardRules);
        root.addProperty("unknownAsOther", unknownAsOther);
        return root;
    }
}
