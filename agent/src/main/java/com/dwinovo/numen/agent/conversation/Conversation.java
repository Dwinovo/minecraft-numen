package com.dwinovo.numen.agent.conversation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 一个会话:<b>一个名字 + 一串同伴 + 发言号</b>。就这些。
 *
 * <h2>群没有自己的对话历史</h2>
 * 每只同伴的对话日志里本来就有她说出口的话和主人的话;群再存一份就是同一句话的第二个出处。
 * 面板要画群聊时按时间归并成员各自的日志。顺带白捡一个好处:<b>解散群不会丢历史</b>。
 *
 * <h2>发言号</h2>
 * 主人在多人会话里说的每一句,会被复制进每个醒着的成员的日志,各自盖各自的时间戳——
 * 时间戳对不上,原文又可能重复("好"说两遍),所以拿它们去重是猜。发言号是 {@code say}
 * 送话之前拨一次、落盘一次的计数:同一句话的 N 份副本号相同,两句不同的话号不同。
 * 视图按(会话 + 发言号 + 原文)归并,不看时间,不会误合也不会漏合。单成员会话用不到它。
 *
 * <h2>谁回不记在会话上</h2>
 * {@code @} 谁谁回,不 {@code @} 全体回,每句话各算各的(见 {@link Mentions});会话不记"上次点了谁",
 * 所以没有可粘住的状态。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public record Conversation(String id, String name, List<UUID> members, int turn) {

    public Conversation {
        members = List.copyOf(members == null ? List.of() : members);
    }

    /** 新建一个群:名字先空着,显示时拼成员名。 */
    public static Conversation of(List<UUID> members) {
        return new Conversation(UUID.randomUUID().toString(), null, dedup(members), 0);
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
    public Conversation withName(String newName) {
        return new Conversation(id, newName == null || newName.isBlank() ? null : newName.strip(),
                members, turn);
    }

    /** 换成员。 */
    public Conversation withMembers(List<UUID> newMembers) {
        return new Conversation(id, name, dedup(newMembers), turn);
    }

    /** 主人又说了一句:发言号拨一。 */
    public Conversation spoken() {
        return new Conversation(id, name, members, turn + 1);
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
        if (turn > 0) {
            o.addProperty("turn", turn);
        }
        return o;
    }

    /** 读一条;id 或成员读不出来则 null——半条群比没有更麻烦。多出来的字段(如早先的 floor)不认。 */
    public static Conversation fromJson(JsonObject o) {
        if (o == null || !o.has("id") || !o.get("id").isJsonPrimitive()) {
            return null;
        }
        List<UUID> members = readUuids(o, "members");
        if (members.isEmpty()) {
            return null;
        }
        String name = o.has("name") && o.get("name").isJsonPrimitive()
                ? o.get("name").getAsString() : null;
        int turn = o.has("turn") && o.get("turn").isJsonPrimitive() ? o.get("turn").getAsInt() : 0;
        return new Conversation(o.get("id").getAsString(), name, members, Math.max(0, turn));
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
}
