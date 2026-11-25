package com.dwinovo.numen.api.brain;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * 主人经客户端对服务端管的东西做的事,上线时的样子:谁是她的主人(分享)、会话的成员表。每一种都是主人的一个动作,
 * 服务端收到后过一遍"这只同伴是不是你的",再交给 {@link OwnerInputs}——客户端什么判断都不替服务端做。她的大脑(说话、停止、整理、
 * 目标、绑定、记忆)在主人的客户端上,不经这里。
 */
public sealed interface OwnerInput {

    /**
     * 把她分享给一位玩家:{@code player} 是他的名字(可以此刻不在线)。只有她的创建者办得成;界面在主人看过分享的后果之后才发。
     */
    record Share(UUID companion, String player) implements OwnerInput {}

    /** 不再把她分享给 {@code person}:创建者移人,或者被分享的人把自己移出去({@code person} 就是他自己)。 */
    record Unshare(UUID companion, UUID person) implements OwnerInput {}

    /** 往会话里拉一个人;从"就他俩"拉则新群用 {@code newId}。 */
    record Pull(String conversation, UUID companion, String newId) implements OwnerInput {}

    /** 把一个人移出会话。 */
    record Drop(String conversation, UUID companion) implements OwnerInput {}

    record Rename(String conversation, String name) implements OwnerInput {}

    record Dissolve(String conversation) implements OwnerInput {}

    // ---- 上线的样子 ----

    default JsonObject toJson() {
        JsonObject o = new JsonObject();
        switch (this) {
            case Share s -> put(o, "share", "companion", s.companion().toString(), "player", s.player());
            case Unshare u -> put(o, "unshare", "companion", u.companion().toString(), "person", u.person().toString());
            case Pull p -> put(o, "pull", "conversation", p.conversation(), "companion", p.companion().toString(),
                    "newId", p.newId());
            case Drop d -> put(o, "drop", "conversation", d.conversation(), "companion", d.companion().toString());
            case Rename r -> put(o, "rename", "conversation", r.conversation(), "name", r.name());
            case Dissolve d -> put(o, "dissolve", "conversation", d.conversation());
        }
        return o;
    }

    /**
     * 读一条;读不懂(未知种类、缺字段、UUID 写坏)返回 {@code null}——上行的东西不可信,读不懂就丢,不抛。
     */
    static OwnerInput fromJson(JsonObject o) {
        try {
            String kind = text(o, "kind");
            if (kind == null) {
                return null;
            }
            return switch (kind) {
                case "share" -> new Share(uuid(o, "companion"), text(o, "player"));
                case "unshare" -> new Unshare(uuid(o, "companion"), uuid(o, "person"));
                case "pull" -> new Pull(text(o, "conversation"), uuid(o, "companion"), text(o, "newId"));
                case "drop" -> new Drop(text(o, "conversation"), uuid(o, "companion"));
                case "rename" -> new Rename(text(o, "conversation"), text(o, "name"));
                case "dissolve" -> new Dissolve(text(o, "conversation"));
                default -> null;
            };
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static void put(JsonObject o, String kind, String... keyValues) {
        o.addProperty("kind", kind);
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                o.addProperty(keyValues[i], keyValues[i + 1]);
            }
        }
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static UUID uuid(JsonObject o, String key) {
        return UUID.fromString(o.get(key).getAsString());
    }
}
