package com.dwinovo.numen.api.keys;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.config.ServerSettings;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.store.JsonDoc;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服主出钱的 key 的每人每天用量与限额。限额是 {@link ServerSettings#dailyTokenLimit}(0 = 不限),用量是这位玩家今天用服主的 key
 * 烧掉的 token(新处理量,同 token 台账的口径);日期一变,用量重新从 0 记。台账在 {@code server/usage.json},管理员能用命令查每人的用量。
 * 主人自己的 key 不经这里。
 *
 * <p>线程:任何线程(调用方在服务端主线程上,模型回调切回来之后)。
 */
public final class ServerKeyQuota {

    private final ServerSettings settings;
    private final JsonDoc doc;
    private final Supplier<LocalDate> today;
    private LocalDate day;
    private final Map<UUID, Long> used = new TreeMap<>();

    public ServerKeyQuota(ServerSettings settings, Home server, Supplier<LocalDate> today) {
        this.settings = settings;
        this.doc = new JsonDoc(server.file("usage.json"), JsonDoc.Format.of(1), server.io());
        this.today = today;
        try {
            doc.read().ifPresent(root -> {
                day = LocalDate.parse(root.get("day").getAsString());
                root.getAsJsonObject("used").entrySet()
                        .forEach(e -> used.put(UUID.fromString(e.getKey()), e.getValue().getAsLong()));
            });
        } catch (RuntimeException unreadable) {
            Constants.LOG.warn("[numen-server] {} 读不了,今天的用量从 0 记: {}", doc.file(), unreadable.toString());
            used.clear();
            day = null;
        }
    }

    /** 每人每天的上限;0 = 不限。 */
    public long limit() {
        return settings.dailyTokenLimit();
    }

    /** 这位玩家今天的服主 key 用掉了。 */
    public synchronized boolean exceeded(UUID owner) {
        return limit() > 0 && used(owner) >= limit();
    }

    /** 这位玩家今天用服主的 key 用掉多少 token。 */
    public synchronized long used(UUID owner) {
        rollOver();
        return used.getOrDefault(owner, 0L);
    }

    /** 记下这位玩家用服主的 key 又烧了多少 token。 */
    public synchronized void charge(UUID owner, long tokens) {
        if (tokens <= 0) {
            return;
        }
        rollOver();
        used.merge(owner, tokens, Long::sum);
        save();
    }

    /** 今天每个人的用量,给管理员的命令。 */
    public synchronized Map<UUID, Long> usedToday() {
        rollOver();
        return new TreeMap<>(used);
    }

    private void rollOver() {
        LocalDate now = today.get();
        if (!now.equals(day)) {
            day = now;
            used.clear();
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        root.addProperty("day", day.toString());
        JsonObject people = new JsonObject();
        used.forEach((owner, tokens) -> people.addProperty(owner.toString(), tokens));
        root.add("used", people);
        doc.write(root);
    }
}
