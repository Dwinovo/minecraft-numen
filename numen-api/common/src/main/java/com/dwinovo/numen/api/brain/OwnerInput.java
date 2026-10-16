package com.dwinovo.numen.api.brain;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * 主人经客户端对她们做的事,上线时的样子。每一种都是主人的一个动作,服务端收到后过一遍"这只同伴是不是你的",
 * 再交给和命令、聊天共用的那个入口({@link OwnerInputs})——客户端什么判断都不替服务端做。
 */
public sealed interface OwnerInput {

    /** 在一个会话里说一句话(面板输入框、快捷对话、语音转写)。会话 id 就他俩时是她的 UUID。 */
    record Say(String conversation, String text) implements OwnerInput {}

    /** 一条斜杠命令:{@code echo} 是主人打的原文;技能正文由服务端展开,聊天流只画 {@code echo}。 */
    record Command(UUID companion, String echo) implements OwnerInput {}

    /** 停止:会话里每个成员都停。 */
    record Stop(String conversation) implements OwnerInput {}

    record Compact(UUID companion) implements OwnerInput {}

    record Clear(UUID companion) implements OwnerInput {}

    /** 设长期目标:{@code echo} 是主人打的原文。 */
    record SetGoal(UUID companion, String objective, String echo) implements OwnerInput {}

    record ClearGoal(UUID companion) implements OwnerInput {}

    /** 给她换模型档案;{@code null} = 不绑。 */
    record SetProvider(UUID companion, String provider) implements OwnerInput {}

    /** 给她换人设;{@code null} = 默认人格。 */
    record SetPersona(UUID companion, String persona) implements OwnerInput {}

    /** 客户端的朗读此刻在不在放:身体据此在说话期间注视主人。 */
    record Speaking(UUID companion, boolean playing) implements OwnerInput {}

    /** 往会话里拉一个人。 */
    record Pull(String conversation, UUID companion) implements OwnerInput {}

    /** 把一个人移出会话。 */
    record Drop(String conversation, UUID companion) implements OwnerInput {}

    record Rename(String conversation, String name) implements OwnerInput {}

    record Dissolve(String conversation) implements OwnerInput {}

    // ---- 上线的样子 ----

    default JsonObject toJson() {
        JsonObject o = new JsonObject();
        switch (this) {
            case Say s -> put(o, "say", "conversation", s.conversation(), "text", s.text());
            case Command c -> put(o, "command", "companion", c.companion().toString(), "echo", c.echo());
            case Stop s -> put(o, "stop", "conversation", s.conversation());
            case Compact c -> put(o, "compact", "companion", c.companion().toString());
            case Clear c -> put(o, "clear", "companion", c.companion().toString());
            case SetGoal g -> put(o, "set_goal", "companion", g.companion().toString(), "objective", g.objective(),
                    "echo", g.echo());
            case ClearGoal g -> put(o, "clear_goal", "companion", g.companion().toString());
            case SetProvider p -> put(o, "set_provider", "companion", p.companion().toString(), "provider", p.provider());
            case SetPersona p -> put(o, "set_persona", "companion", p.companion().toString(), "persona", p.persona());
            case Speaking s -> {
                put(o, "speaking", "companion", s.companion().toString());
                o.addProperty("playing", s.playing());
            }
            case Pull p -> put(o, "pull", "conversation", p.conversation(), "companion", p.companion().toString());
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
                case "say" -> new Say(text(o, "conversation"), text(o, "text"));
                case "command" -> new Command(uuid(o, "companion"), text(o, "echo"));
                case "stop" -> new Stop(text(o, "conversation"));
                case "compact" -> new Compact(uuid(o, "companion"));
                case "clear" -> new Clear(uuid(o, "companion"));
                case "set_goal" -> new SetGoal(uuid(o, "companion"), text(o, "objective"), text(o, "echo"));
                case "clear_goal" -> new ClearGoal(uuid(o, "companion"));
                case "set_provider" -> new SetProvider(uuid(o, "companion"), text(o, "provider"));
                case "set_persona" -> new SetPersona(uuid(o, "companion"), text(o, "persona"));
                case "speaking" -> new Speaking(uuid(o, "companion"), o.get("playing").getAsBoolean());
                case "pull" -> new Pull(text(o, "conversation"), uuid(o, "companion"));
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
