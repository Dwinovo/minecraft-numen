package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.llm.ConvoLog;
import com.dwinovo.numen.api.agent.loop.Cause;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端推给主人客户端的东西:循环里发生的事里界面要画、要听的那些。服务端按刻攒成一包({@link FeedEncoder}),
 * 客户端照序往它的视图里放,不判断、不推断。
 *
 * <p>只有展示要的:内核的 {@code LoopEvent} 带着整份回复和工具参数,面板要的行从日志来({@link Line}),
 * 所以不原样搬。一条回复的正文只在两处出现:正在长的时候是 {@link Delta},落库之后是 {@link Line}。
 */
public sealed interface Feed {

    /** 要调一次模型了:打字机清空,语音开一轮。{@code ownerSpoke}:主人的话还没被回应(语音硬停上一轮)。 */
    record TurnStart(boolean ownerSpoke) implements Feed {}

    /** 流式回复长出来一段(同一刻里的合并成一条)。 */
    record Delta(String content, String reasoning) implements Feed {}

    /** 她的一条回复落了库。有工具调用时 {@code content} 是开工前顺嘴的一句。 */
    record Replied(String content, boolean hasToolCalls) implements Feed {}

    /** 这次调用没落库就结束了(失败或被切断):半截文字作废,语音收尾。 */
    record TurnCut() implements Feed {}

    /** 执行了一次切断(停止、死亡、登出……):语音闭嘴,头顶的残句收起。 */
    record Halted() implements Feed {}

    /** 调用失败而且不再重试:该让主人看见了。 */
    record Failed(Cause cause) implements Feed {}

    /** 她发不了请求(没绑档案、没填 key):配置问题不能静默。 */
    record Blocked(Cause cause) implements Feed {}

    /** 日志里写下的一行面板记录。 */
    record Line(ConvoLog.Line line) implements Feed {}

    /** 主人清空了她的记忆:面板上她的对话记录作废(之后翻页取到的是空的新日志)。 */
    record Wiped() implements Feed {}

    /** 排着的输入(整份,变了才发)。 */
    record Pending(List<EventQueue.Preview> items) implements Feed {

        public Pending {
            items = List.copyOf(items);
        }
    }

    /** 她此刻的样子(整份,变了才发)。 */
    record Status(CompanionState state) implements Feed {}

    // ---- 上线的样子 ----

    default JsonObject toJson() {
        return switch (this) {
            case TurnStart t -> {
                JsonObject o = tagged("turn_start");
                o.addProperty("ownerSpoke", t.ownerSpoke());
                yield o;
            }
            case Delta d -> {
                JsonObject o = tagged("delta");
                o.addProperty("content", d.content());
                o.addProperty("reasoning", d.reasoning());
                yield o;
            }
            case Replied r -> {
                JsonObject o = tagged("replied");
                o.addProperty("content", r.content());
                o.addProperty("hasToolCalls", r.hasToolCalls());
                yield o;
            }
            case TurnCut t -> tagged("turn_cut");
            case Halted h -> tagged("halted");
            case Failed f -> {
                JsonObject o = tagged("failed");
                o.add("cause", CompanionState.cause(f.cause()));
                yield o;
            }
            case Blocked b -> {
                JsonObject o = tagged("blocked");
                o.add("cause", CompanionState.cause(b.cause()));
                yield o;
            }
            case Line l -> {
                JsonObject o = tagged("line");
                o.add("line", ConvoLog.toJson(l.line()));
                yield o;
            }
            case Wiped w -> tagged("wiped");
            case Pending p -> {
                JsonObject o = tagged("pending");
                JsonArray items = new JsonArray();
                p.items().forEach(i -> items.add(CompanionState.preview(i)));
                o.add("items", items);
                yield o;
            }
            case Status s -> {
                JsonObject o = tagged("status");
                o.add("state", s.state().toJson());
                yield o;
            }
        };
    }

    static JsonArray toJson(List<Feed> events) {
        JsonArray a = new JsonArray();
        events.forEach(e -> a.add(e.toJson()));
        return a;
    }

    /** 读不懂的一条(新版本加的种类)跳过,其余照常。 */
    static List<Feed> listFromJson(JsonArray array) {
        List<Feed> out = new ArrayList<>();
        for (JsonElement e : array) {
            Feed f = fromJson(e.getAsJsonObject());
            if (f != null) {
                out.add(f);
            }
        }
        return out;
    }

    static Feed fromJson(JsonObject o) {
        String kind = CompanionState.string(o, "kind");
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case "turn_start" -> new TurnStart(o.get("ownerSpoke").getAsBoolean());
            case "delta" -> new Delta(CompanionState.string(o, "content"), CompanionState.string(o, "reasoning"));
            case "replied" -> new Replied(CompanionState.string(o, "content"), o.get("hasToolCalls").getAsBoolean());
            case "turn_cut" -> new TurnCut();
            case "halted" -> new Halted();
            case "failed" -> new Failed(CompanionState.cause(o.getAsJsonObject("cause")));
            case "blocked" -> new Blocked(CompanionState.cause(o.getAsJsonObject("cause")));
            case "line" -> {
                ConvoLog.Line line = ConvoLog.lineFromJson(o.getAsJsonObject("line"));
                yield line == null ? null : new Line(line);
            }
            case "wiped" -> new Wiped();
            case "pending" -> {
                List<EventQueue.Preview> items = new ArrayList<>();
                for (JsonElement p : o.getAsJsonArray("items")) {
                    items.add(CompanionState.preview(p.getAsJsonObject()));
                }
                yield new Pending(items);
            }
            case "status" -> new Status(CompanionState.fromJson(o.getAsJsonObject("state")));
            default -> null;
        };
    }

    /** 每种事件的 JSON 都是 {@code {"kind":…}} 加它自己的字段。 */
    static JsonObject tagged(String kind) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        return o;
    }
}
