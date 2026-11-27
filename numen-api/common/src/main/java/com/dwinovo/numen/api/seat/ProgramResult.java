package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.agent.loop.Program;
import com.dwinovo.numen.script.ScriptCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@link SeatMethods#PROGRAM_RUN} 的答复:一段程序的结构化结局,连同结局那一刻她的样子、答复时的代数。结局里只有能上网线的部分:
 * 回执、怎么结束的、每次调用的结局、用到的模块、为什么被叫停;程序 {@code return} 的值与完整错误值只存在于跑程序的进程里,不在这里。
 *
 * @param program    程序的编号,和请求里的一样
 * @param generation 结局产生时她的身体的代数
 * @param state      结局产生那一刻她的样子
 */
public record ProgramResult(String program, Program.Outcome outcome, long generation, SeatState state) {

    public JsonObject toJson() {
        JsonObject o = outcomeJson(outcome);
        o.addProperty("program", program);
        o.addProperty("generation", generation);
        state.toJson().asMap().forEach(o::add);
        return o;
    }

    public static ProgramResult fromJson(JsonObject o) {
        return new ProgramResult(o.get("program").getAsString(), outcomeOf(o), o.get("generation").getAsLong(),
                SeatState.fromJson(o));
    }

    /** 结局写成 JSON。 */
    public static JsonObject outcomeJson(Program.Outcome outcome) {
        JsonObject o = new JsonObject();
        o.addProperty("receipt", outcome.receipt());
        JsonObject ending = new JsonObject();
        ending.addProperty("status", outcome.ending().status().wire());
        if (outcome.ending().errorKind() != null) {
            ending.addProperty("error_kind", outcome.ending().errorKind());
        }
        o.add("ending", ending);
        JsonArray calls = new JsonArray();
        for (ScriptCall.Called called : outcome.calls()) {
            JsonObject c = new JsonObject();
            c.addProperty("function", called.function());
            c.addProperty("call", called.call());
            if (called.first() != null) {
                c.addProperty("first", called.first());
            }
            if (called.kind() != null) {
                c.addProperty("kind", called.kind());
            }
            calls.add(c);
        }
        o.add("calls", calls);
        if (outcome.stoppedFor() != null) {
            o.addProperty("stopped_for", outcome.stoppedFor());
        }
        return o;
    }

    /** {@link #outcomeJson} 写出的结局读回来。 */
    public static Program.Outcome outcomeOf(JsonObject o) {
        JsonObject ending = o.getAsJsonObject("ending");
        List<ScriptCall.Called> calls = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("calls")) {
            JsonObject c = e.getAsJsonObject();
            calls.add(new ScriptCall.Called(c.get("function").getAsString(), c.get("call").getAsString(),
                    text(c, "first"), text(c, "kind")));
        }
        return new Program.Outcome(o.get("receipt").getAsString(),
                new ScriptCall.Ending(ScriptCall.Status.valueOf(ending.get("status").getAsString()
                        .toUpperCase(Locale.ROOT)), text(ending, "error_kind")),
                List.copyOf(calls), text(o, "stopped_for"));
    }

    private static String text(JsonObject o, String name) {
        return o.has(name) ? o.get(name).getAsString() : null;
    }
}
