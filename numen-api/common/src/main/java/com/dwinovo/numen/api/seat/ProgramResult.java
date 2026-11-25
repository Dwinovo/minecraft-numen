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
 * {@link SeatMethods#PROGRAM_RUN} 的答复:一段程序的结构化结局,连同她此刻的样子与游戏刻、答复时的代数。结局里只有能上网线的部分:
 * 回执、怎么结束的、每次调用的结局、用到的模块、为什么被叫停;程序 {@code return} 的值与完整错误值只存在于跑程序的进程里,不在这里。
 *
 * @param program    程序的编号,和请求里的一样
 * @param generation 结局产生时她的身体的代数
 * @param state      她此刻的样子({@code <runtime_state>}),没有可说的是空串
 * @param gameTime   主世界的游戏刻
 */
public record ProgramResult(String program, Program.Outcome outcome, long generation, String state, long gameTime) {

    public JsonObject toJson() {
        JsonObject o = outcomeJson(outcome);
        o.addProperty("program", program);
        o.addProperty("generation", generation);
        o.addProperty("state", state);
        o.addProperty("game_time", gameTime);
        return o;
    }

    public static ProgramResult fromJson(JsonObject o) {
        return new ProgramResult(o.get("program").getAsString(), outcomeOf(o), o.get("generation").getAsLong(),
                o.get("state").getAsString(), o.get("game_time").getAsLong());
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
        JsonArray used = new JsonArray();
        for (Program.Used u : outcome.used()) {
            JsonObject m = new JsonObject();
            m.addProperty("module", u.module());
            m.addProperty("ok", u.tally().ok());
            m.addProperty("line", u.tally().line());
            if (u.tally().error() != null) {
                m.addProperty("error", u.tally().error());
            }
            used.add(m);
        }
        o.add("used", used);
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
        List<Program.Used> used = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("used")) {
            JsonObject m = e.getAsJsonObject();
            used.add(new Program.Used(m.get("module").getAsString(), new ScriptCall.Tally(m.get("ok").getAsBoolean(),
                    m.get("line").getAsInt(), text(m, "error"))));
        }
        return new Program.Outcome(o.get("receipt").getAsString(),
                new ScriptCall.Ending(ScriptCall.Status.valueOf(ending.get("status").getAsString()
                        .toUpperCase(Locale.ROOT)), text(ending, "error_kind")),
                List.copyOf(calls), List.copyOf(used), text(o, "stopped_for"));
    }

    private static String text(JsonObject o, String name) {
        return o.has(name) ? o.get(name).getAsString() : null;
    }
}
