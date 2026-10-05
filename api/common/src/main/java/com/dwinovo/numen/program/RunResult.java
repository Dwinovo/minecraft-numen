package com.dwinovo.numen.program;

import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.script.Program;
import com.dwinovo.numen.agent.script.ScriptCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端对"跑这段程序"的答复:跑完了({@link Ended},整张回执加每次调用的结局和用到的模块),或者没跑——清单里有些模块的正文服务端
 * 没有({@link Missing}),客户端把它们带上再来一次。线上是一段 JSON,写与读只在这里。线上有回执(成败与文字)、怎么结束的、每次调用的结局、用到的模块;
 * 程序 {@code return} 的值原样和完整的错误值不上网线,只在 {@link Program.Outcome} 里留给服务端进程内的读者。
 */
public sealed interface RunResult {

    /** 跑完了,不论怎么跑完的;回执说怎么跑完的。 */
    record Ended(Program.Outcome outcome) implements RunResult {}

    /** 没跑:这些指纹的正文服务端没有。 */
    record Missing(List<String> hashes) implements RunResult {}

    /** 一段没能开跑的程序的结果:一张失败的回执,没有调用,没有模块。 */
    static RunResult refused(String why) {
        return new Ended(new Program.Outcome(ToolOutcome.failure(why),
                new ScriptCall.Ending(ScriptCall.Status.ERROR, ErrorKind.FAILED.wire()), List.of(), List.of(), null));
    }

    /** 一张回执(服务端写的那一份 JSON)的文字,模型读的就是它。 */
    static String messageOf(String receiptJson) {
        return JsonParser.parseString(receiptJson).getAsJsonObject().get("message").getAsString();
    }

    default String toJson() {
        JsonObject out = new JsonObject();
        switch (this) {
            case Missing missing -> {
                JsonArray hashes = new JsonArray();
                missing.hashes().forEach(hashes::add);
                out.add("missing", hashes);
            }
            case Ended ended -> {
                Program.Outcome outcome = ended.outcome();
                out.addProperty("receipt", outcome.receipt());
                // 程序 return 的值与完整的错误值只留在服务端进程里,不上网线
                out.addProperty("status", outcome.ending().status().wire());
                if (outcome.ending().errorKind() != null) {
                    out.addProperty("error_kind", outcome.ending().errorKind());
                }
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
                out.add("calls", calls);
                if (outcome.stoppedFor() != null) {
                    out.addProperty("stopped_for", outcome.stoppedFor());
                }
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
                out.add("used", used);
            }
        }
        return out.toString();
    }

    /** @throws IllegalArgumentException 不是 {@link #toJson} 写的样子 */
    static RunResult fromJson(String json) {
        JsonObject in;
        try {
            in = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException notJson) {
            throw new IllegalArgumentException("not a program result: " + json, notJson);
        }
        if (in.has("missing")) {
            List<String> hashes = new ArrayList<>();
            in.getAsJsonArray("missing").forEach(h -> hashes.add(h.getAsString()));
            return new Missing(List.copyOf(hashes));
        }
        List<ScriptCall.Called> calls = new ArrayList<>();
        for (JsonElement element : in.getAsJsonArray("calls")) {
            JsonObject c = element.getAsJsonObject();
            calls.add(new ScriptCall.Called(c.get("function").getAsString(), c.get("call").getAsString(),
                    c.has("first") ? c.get("first").getAsString() : null,
                    c.has("kind") ? c.get("kind").getAsString() : null));
        }
        List<Program.Used> used = new ArrayList<>();
        for (JsonElement element : in.getAsJsonArray("used")) {
            JsonObject m = element.getAsJsonObject();
            used.add(new Program.Used(m.get("module").getAsString(), new ScriptCall.Tally(m.get("ok").getAsBoolean(),
                    m.get("line").getAsInt(), m.has("error") ? m.get("error").getAsString() : null)));
        }
        ScriptCall.Ending ending = new ScriptCall.Ending(
                ScriptCall.Status.valueOf(in.get("status").getAsString().toUpperCase(java.util.Locale.ROOT)),
                in.has("error_kind") ? in.get("error_kind").getAsString() : null);
        return new Ended(new Program.Outcome(in.get("receipt").getAsString(), ending, List.copyOf(calls), List.copyOf(used),
                in.has("stopped_for") ? in.get("stopped_for").getAsString() : null));
    }
}
