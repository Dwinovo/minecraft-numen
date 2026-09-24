package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 她的一次 {@code todowrite} 读成一份清单(Telegram 的清单消息:一条消息里几项待办,做完就勾)。
 * 对话流把它画成她说的一条消息;同一份计划只是状态变了,就在第一次出现的那条上原地更新,
 * 条目内容变了才另起一条——"是不是同一份"只看 {@link #sameItems}。
 *
 * <p>纯逻辑,不碰 Minecraft,单元测试直接喂调用。
 */
public final class PlanChecklist {

    /** 计划工具的名字:只有它的调用是一份清单。 */
    public static final String TOOL = "todowrite";

    /** 一项的状态,与 {@code todowrite} 的四个取值一一对应。 */
    public enum State { PENDING, IN_PROGRESS, COMPLETED, CANCELLED }

    public record Item(String content, State state) {}

    private PlanChecklist() {}

    /**
     * 这次调用写下的清单;不是 {@code todowrite}、参数读不出一份清单(不是对象、没有 todos 数组、
     * 某项没有内容或状态不认得)时是 null——那样的调用画不成清单,仍是一次普通的工具调用。
     */
    public static List<Item> of(LlmToolCall call) {
        if (!TOOL.equals(call.name())) return null;
        JsonElement root;
        try {
            root = JsonParser.parseString(call.arguments());
        } catch (RuntimeException e) {
            return null;   // 参数不是 JSON:没有清单可画
        }
        if (!root.isJsonObject() || !root.getAsJsonObject().has("todos")
                || !root.getAsJsonObject().get("todos").isJsonArray()) {
            return null;
        }
        JsonArray todos = root.getAsJsonObject().getAsJsonArray("todos");
        List<Item> out = new ArrayList<>(todos.size());
        for (JsonElement e : todos) {
            if (!e.isJsonObject()) return null;
            JsonObject o = e.getAsJsonObject();
            String content = string(o, "content");
            State state = state(string(o, "status"));
            if (content == null || content.isBlank() || state == null) return null;
            out.add(new Item(content.strip(), state));
        }
        return out.isEmpty() ? null : List.copyOf(out);
    }

    /** 是不是同一份计划:条目一样多、逐条内容相同(状态不算)。 */
    public static boolean sameItems(List<Item> a, List<Item> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).content().equals(b.get(i).content())) return false;
        }
        return true;
    }

    /** 做完了几项(抬头的"计划 2/5"里的 2)。 */
    public static int done(List<Item> items) {
        int n = 0;
        for (Item it : items) {
            if (it.state() == State.COMPLETED) n++;
        }
        return n;
    }

    private static State state(String status) {
        if (status == null) return null;
        return switch (status) {
            case "pending" -> State.PENDING;
            case "in_progress" -> State.IN_PROGRESS;
            case "completed" -> State.COMPLETED;
            case "cancelled" -> State.CANCELLED;
            default -> null;
        };
    }

    private static String string(JsonObject o, String k) {
        JsonElement v = o.get(k);
        return v != null && v.isJsonPrimitive() ? v.getAsString() : null;
    }
}
