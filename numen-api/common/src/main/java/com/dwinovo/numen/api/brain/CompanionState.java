package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.loop.Cause;
import com.dwinovo.numen.api.agent.loop.Hold;
import com.dwinovo.numen.api.agent.loop.LoopStatus;
import com.dwinovo.numen.api.agent.loop.Phase;
import com.dwinovo.numen.api.agent.provider.Usage;
import com.dwinovo.numen.api.entity.Keeper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一只同伴此刻的样子,界面要读的全部状态:内核的快照({@link LoopStatus})加上只有服务端知道的几样——此刻能不能整理/清空
 * (和理由的语言键)、长期目标、token 台账、上下文水位、绑定(每一项连同是谁绑的)。服务端算一次、变了才推给主人的客户端,客户端只读不算。
 * 这里面没有一个翻译过的字:原因、理由、在做的事都是语言键加现场的词,界面按主人的语言翻。
 *
 * @param phase           内核在做的那件事;{@code null} = 闲
 * @param hold            停牌;{@code null} = 没有
 * @param holdReason      停牌带着的原因
 * @param goal            长期目标({@code GoalState} 的 JSON);没有是 {@code null}
 * @param outstanding    结果还会来的工具调用的 id(在飞的与排着的):历史里没结果又不在这里的调用是被切断的
 * @param compactProblem  现在不能整理的理由(语言键);{@code null} = 能
 * @param clearProblem    现在不能清空的理由;{@code null} = 能
 * @param messages        她此刻的上下文里有几条消息
 * @param bindings        她绑着的那几项(档案、人设、声线),每一项带着是谁绑的;没绑的不在表里。声线由客户端据此朗读
 * @param skinId          她穿着的皮肤(签名缓存里的引用);默认皮肤是 {@code null}
 */
public record CompanionState(Phase phase, Hold hold, Cause holdReason, boolean bodyTaskRunning,
                             LoopStatus.Doing activity, double compactProgress, List<EventQueue.Preview> queued,
                             String compactProblem, String clearProblem, JsonObject goal,
                             long totalTokens, Usage usageSum, Usage usageLast, long wastedTokens, int wasteCount,
                             int contextPercent, int window, Map<Slot, Binding> bindings,
                             String skinId, String conversation, List<String> outstanding, int messages) {

    /**
     * 状态里的一项绑定:绑定本身({@link Bound}:谁绑的、哪一条),加绑的人给人看的名字——界面把别人绑的标成"某某的"用;
     * 名字不知道是空串。条目的内容不在这里:别人"我的"里的档案、人设、声线,看的人只看得到名字。
     */
    public record Binding(Bound bound, String byName) {

        public Binding {
            byName = byName == null ? "" : byName;
        }

        /** 绑的那一条的 id({@code 命名空间:名字})。 */
        public String id() {
            return bound.id().toString();
        }

        /** 绑的那个人:是谁、界面上怎么称呼他。 */
        public Keeper by() {
            return new Keeper(bound.by(), byName);
        }

        /**
         * 这一项是不是 {@code person} 库里的那一条 {@code entryId}:得是他绑的,id 也对得上。别人绑的同名条目是另一条——它在绑的人看到的库里
         * 解析、用绑的人的钥匙、账记在绑的人名下。界面上"当前绑的是哪一行"只按这一处认。
         */
        public boolean is(UUID person, String entryId) {
            return bound.by().equals(person) && id().equals(entryId);
        }
    }

    private static final String BY_NAME = "byName";

    public CompanionState {
        queued = List.copyOf(queued);
        outstanding = List.copyOf(outstanding);
        EnumMap<Slot, Binding> copy = new EnumMap<>(Slot.class);
        copy.putAll(bindings);
        bindings = Collections.unmodifiableMap(copy);
    }

    /** 这一项绑着什么;没绑是 {@code null}。 */
    public Binding binding(Slot slot) {
        return bindings.get(slot);
    }

    /** 还没听说过她的任何事时的样子:闲着,什么都没排,没有目标。 */
    public static CompanionState idle() {
        return new CompanionState(null, null, null, false, null, 0.0, List.of(), null, null, null, 0, Usage.ZERO,
                Usage.ZERO, 0, 0, 0, 0, Map.of(), null, null, List.of(), 0);
    }

    /** 停止键此刻有东西可停:在干活,或者排着主人的指令。 */
    public boolean canInterrupt() {
        return phase != null || bodyTaskRunning || !queued.isEmpty();
    }

    /** 大脑或身体在干活:等模型、跑工具、整理记忆,或身体有后台任务。 */
    public boolean busy() {
        return phase != null || bodyTaskRunning;
    }

    // ---- 上线的样子 ----

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        put(o, "phase", phase == null ? null : phase.name());
        put(o, "hold", hold == null ? null : hold.name());
        if (holdReason != null) {
            o.add("holdReason", cause(holdReason));
        }
        o.addProperty("bodyTask", bodyTaskRunning);
        if (activity != null) {
            JsonObject a = new JsonObject();
            a.addProperty("key", activity.key());
            a.add("args", strings(activity.args()));
            o.add("activity", a);
        }
        o.addProperty("compactProgress", compactProgress);
        JsonArray previews = new JsonArray();
        for (EventQueue.Preview p : queued) {
            previews.add(preview(p));
        }
        o.add("queued", previews);
        put(o, "compactProblem", compactProblem);
        put(o, "clearProblem", clearProblem);
        if (goal != null) {
            o.add("goal", goal);
        }
        o.addProperty("totalTokens", totalTokens);
        o.add("usageSum", usage(usageSum));
        o.add("usageLast", usage(usageLast));
        o.addProperty("wastedTokens", wastedTokens);
        o.addProperty("wasteCount", wasteCount);
        o.addProperty("contextPercent", contextPercent);
        o.addProperty("window", window);
        JsonObject bound = new JsonObject();
        bindings.forEach((slot, binding) -> {
            JsonObject b = binding.bound().toJson();
            b.addProperty(BY_NAME, binding.byName());
            bound.add(slot.wire(), b);
        });
        o.add("bindings", bound);
        put(o, "skinId", skinId);
        put(o, "conversation", conversation);
        o.add("outstanding", strings(outstanding));
        o.addProperty("messages", messages);
        return o;
    }

    public static CompanionState fromJson(JsonObject o) {
        LoopStatus.Doing doing = null;
        if (o.get("activity") instanceof JsonObject a) {
            doing = new LoopStatus.Doing(string(a, "key"), list(a.get("args")));
        }
        List<EventQueue.Preview> previews = new ArrayList<>();
        if (o.get("queued") instanceof JsonArray arr) {
            for (JsonElement e : arr) {
                JsonObject p = e.getAsJsonObject();
                previews.add(new EventQueue.Preview(string(p, "id"), string(p, "type"), string(p, "text")));
            }
        }
        return new CompanionState(
                string(o, "phase") == null ? null : Phase.valueOf(string(o, "phase")),
                string(o, "hold") == null ? null : Hold.valueOf(string(o, "hold")),
                o.get("holdReason") instanceof JsonObject c ? cause(c) : null,
                o.has("bodyTask") && o.get("bodyTask").getAsBoolean(), doing,
                o.has("compactProgress") ? o.get("compactProgress").getAsDouble() : 0.0, previews,
                string(o, "compactProblem"), string(o, "clearProblem"),
                o.get("goal") instanceof JsonObject g ? g : null,
                longOf(o, "totalTokens"), usage(o.getAsJsonObject("usageSum")), usage(o.getAsJsonObject("usageLast")),
                longOf(o, "wastedTokens"), (int) longOf(o, "wasteCount"), (int) longOf(o, "contextPercent"),
                (int) longOf(o, "window"), bindings(o.get("bindings")),
                string(o, "skinId"), string(o, "conversation"),
                list(o.get("outstanding")), (int) longOf(o, "messages"));
    }

    // ---- 小件:绑定、原因、预览、用量的 JSON ----

    private static Map<Slot, Binding> bindings(JsonElement el) {
        Map<Slot, Binding> out = new EnumMap<>(Slot.class);
        if (el instanceof JsonObject all) {
            for (Slot slot : Slot.values()) {
                if (all.get(slot.wire()) instanceof JsonObject b && Bound.fromJson(b) != null) {
                    out.put(slot, new Binding(Bound.fromJson(b), string(b, BY_NAME)));
                }
            }
        }
        return out;
    }

    public static JsonObject cause(Cause cause) {
        JsonObject o = new JsonObject();
        o.addProperty("code", cause.code());
        o.add("args", strings(cause.args()));
        return o;
    }

    public static Cause cause(JsonObject o) {
        return new Cause(string(o, "code"), list(o.get("args")));
    }

    static JsonObject preview(EventQueue.Preview p) {
        JsonObject o = new JsonObject();
        put(o, "id", p.id());
        o.addProperty("type", p.type());
        o.addProperty("text", p.text());
        return o;
    }

    static EventQueue.Preview preview(JsonObject o) {
        return new EventQueue.Preview(string(o, "id"), string(o, "type"), string(o, "text"));
    }

    private static JsonObject usage(Usage u) {
        JsonObject o = new JsonObject();
        o.addProperty("input", u.input());
        o.addProperty("output", u.output());
        o.addProperty("cacheRead", u.cacheRead());
        o.addProperty("cacheWrite", u.cacheWrite());
        return o;
    }

    private static Usage usage(JsonObject o) {
        return o == null ? Usage.ZERO
                : new Usage(longOf(o, "input"), longOf(o, "output"), longOf(o, "cacheRead"), longOf(o, "cacheWrite"));
    }

    static JsonArray strings(List<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }

    static List<String> list(JsonElement el) {
        List<String> out = new ArrayList<>();
        if (el instanceof JsonArray a) {
            a.forEach(e -> out.add(e.getAsString()));
        }
        return out;
    }

    static void put(JsonObject o, String key, String value) {
        if (value != null) {
            o.addProperty(key, value);
        }
    }

    static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static long longOf(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : 0L;
    }
}
