package com.dwinovo.numen.agent.group;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 一个群:<b>一个名字 + 一串同伴 + 当前话头</b>。就这些。
 *
 * <h2>群没有自己的对话历史</h2>
 * 每只同伴的对话日志里本来就有她说出口的话和主人的话;群再存一份就是同一句话的第二个出处。
 * 面板要画群聊时按时间归并成员各自的日志。顺带白捡一个好处:<b>解散群不会丢历史</b>。
 *
 * <h2>话头是一组人,不是一个人</h2>
 * {@code @A @B 一起去挖铁} 之后的"小心点"该让两只都听见,所以话头记的是<b>上一次被点名的那些</b>。
 * 空 = 全体——没点过名时说的话通常本来就是说给全体的("我回来了"、"天黑了")。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public record CompanionGroup(String id, String name, List<UUID> members, List<UUID> floor) {

    public CompanionGroup {
        members = List.copyOf(members == null ? List.of() : members);
        floor = List.copyOf(floor == null ? List.of() : floor);
    }

    /** 新建一个群:名字先空着,显示时拼成员名;话头空着 = 全体。 */
    public static CompanionGroup of(List<UUID> members) {
        return new CompanionGroup(UUID.randomUUID().toString(), null, dedup(members), List.of());
    }

    /**
     * 界面上叫什么:主人改过名就用他起的,没改过就拼成员名。
     *
     * <p>默认跟着成员走,一旦改名就固定——Discord 与微信共同的做法。好处是建群那一步不必问名字,
     * 而且不会出现"群里早没有阿岚了,群名还叫阿岚"。
     *
     * @param nameOf 同伴 UUID → 显示名;查不到的成员跳过
     */
    public String displayName(Function<UUID, String> nameOf) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        List<String> names = new ArrayList<>();
        for (UUID m : members) {
            String n = nameOf.apply(m);
            if (n != null && !n.isBlank()) {
                names.add(n);
            }
        }
        return String.join("、", names);
    }

    /** 主人起的名(空白 = 退回拼成员名)。 */
    public CompanionGroup withName(String newName) {
        return new CompanionGroup(id, newName == null || newName.isBlank() ? null : newName.strip(),
                members, keepMembers(floor, dedup(members)));
    }

    /** 换成员。话头里已经不在群里的那些跟着掉——话头只可能落在成员身上。 */
    public CompanionGroup withMembers(List<UUID> newMembers) {
        List<UUID> ms = dedup(newMembers);
        return new CompanionGroup(id, name, ms, keepMembers(floor, ms));
    }

    /** 转话头(空 = 回到全体)。不在群里的一律不收。 */
    public CompanionGroup withFloor(List<UUID> next) {
        return new CompanionGroup(id, name, members, keepMembers(dedup(next), members));
    }

    public boolean has(UUID companion) {
        return members.contains(companion);
    }

    // ---- 落盘 ----

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        if (name != null) {
            o.addProperty("name", name);
        }
        o.add("members", uuids(members));
        if (!floor.isEmpty()) {
            o.add("floor", uuids(floor));
        }
        return o;
    }

    /** 读一条;id 或成员读不出来则 null——半条群比没有更麻烦。 */
    public static CompanionGroup fromJson(JsonObject o) {
        if (o == null || !o.has("id") || !o.get("id").isJsonPrimitive()) {
            return null;
        }
        List<UUID> members = readUuids(o, "members");
        if (members.isEmpty()) {
            return null;
        }
        String name = o.has("name") && o.get("name").isJsonPrimitive()
                ? o.get("name").getAsString() : null;
        return new CompanionGroup(o.get("id").getAsString(), name, members,
                keepMembers(readUuids(o, "floor"), members));
    }

    private static JsonArray uuids(List<UUID> list) {
        JsonArray a = new JsonArray();
        for (UUID u : list) {
            a.add(u.toString());
        }
        return a;
    }

    private static List<UUID> readUuids(JsonObject o, String key) {
        List<UUID> out = new ArrayList<>();
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return out;
        }
        for (var e : o.getAsJsonArray(key)) {
            if (!e.isJsonPrimitive()) {
                continue;
            }
            try {
                out.add(UUID.fromString(e.getAsString()));
            } catch (IllegalArgumentException notUuid) {
                // 手改坏的一行:跳过这一个,别拖垮整个群
            }
        }
        return out;
    }

    private static List<UUID> dedup(List<UUID> in) {
        if (in == null) {
            return List.of();
        }
        LinkedHashSet<UUID> set = new LinkedHashSet<>();
        for (UUID u : in) {
            if (u != null) {
                set.add(u);
            }
        }
        return List.copyOf(set);
    }

    private static List<UUID> keepMembers(List<UUID> some, List<UUID> members) {
        List<UUID> out = new ArrayList<>();
        for (UUID u : some) {
            if (members.contains(u)) {
                out.add(u);
            }
        }
        return List.copyOf(out);
    }
}
