package com.dwinovo.numen.agent.script;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一段程序整段在它自己的执行体上跑:每次 API 调用交给执行口、等结果,留下身体活就等收尾,急件让它停在调用之间,切断让它当场交出
 * 停在哪一行的回执。
 *
 * <p>假的执行口把派出去的调用停在 {@link #pending} 里等测试替它回结果;task_finished 的正文是 {@code 编号 状态 交代的话}
 * (测试自己的约定,真的写法在 api 的 {@code NumenEvents})。执行体用当场执行的:同一个线程上一步一步看。
 */
class ProgramTest {

    private final Map<String, Consumer<String>> pending = new LinkedHashMap<>();
    private final List<String> lines = new ArrayList<>();
    private final List<String> callIds = new ArrayList<>();
    private final List<EventQueue.Entry> forwarded = new ArrayList<>();
    private Program.Outcome outcome;
    private long now;

    private static ScriptCatalog.Function fn(ScriptCatalog.Kind kind) {
        return new ScriptCatalog.Function(Integer.MAX_VALUE, java.util.Set.of("arrive", "bad"), kind,
                ScriptType.NOTHING, null);
    }

    private static final ScriptCatalog CATALOG = new ScriptCatalog(Map.of(
            "work", Map.of("dig", fn(ScriptCatalog.Kind.JOB), "collect", fn(ScriptCatalog.Kind.JOB)),
            "move", Map.of("go", fn(ScriptCatalog.Kind.JOB)),
            "area", Map.of("has", fn(ScriptCatalog.Kind.VALUE))), new ScriptCatalog.ModuleSource() {
                @Override
                public String code(String name) {
                    return "pit".equals(name) ? "local M = {}\nfunction M.out(a)\n  work.dig(a)\n  return 3\nend\n"
                            + "return M" : null;
                }

                @Override
                public List<String> names() {
                    return List.of("pit");
                }
            }, Map.of());

    private final Program.Port port = new Program.Port() {
        @Override
        public ScriptCatalog catalog() {
            return CATALOG;
        }

        @Override
        public Invocation invocation(ScriptRun.Call call) {
            if (call.options().containsKey("bad")) {
                throw new ApiError(ErrorKind.BAD_ARGUMENT, "there is no option bad; usage: " + call.function()
                        + "(place)", null);
            }
            JsonObject args = new JsonObject();
            JsonArray objects = new JsonArray();
            call.args().forEach(a -> objects.add(String.valueOf(a)));
            args.add("objects", objects);
            call.options().forEach((k, v) -> args.addProperty(k, String.valueOf(v)));
            return new Invocation(call.group(), call.name(), call.function(), args);
        }

        @Override
        public void dispatch(String callId, Invocation invocation, Consumer<String> done) {
            callIds.add(callId);
            StringBuilder line = new StringBuilder(invocation.function());
            invocation.args().getAsJsonArray("objects").forEach(o -> line.append(' ').append(o.getAsString()));
            invocation.args().entrySet().stream().filter(e -> !e.getKey().equals("objects")).forEach(e -> line
                    .append(" --").append(e.getKey()).append(' ').append(e.getValue().getAsString()));
            lines.add(line.toString());
            pending.put(callId, done);
        }

        @Override
        public ScriptCall.Finish finish(EventQueue.Entry entry) {
            if (!EventTypes.TASK_FINISHED.equals(entry.type())) {
                return null;
            }
            String[] parts = entry.text().split(" ", 3);
            return new ScriptCall.Finish(parts[0], parts.length > 1 ? parts[1] : "done", parts.length > 2 ? parts[2] : "",
                    null);
        }

        @Override
        public void forward(EventQueue.Entry entry) {
            forwarded.add(entry);
        }

        @Override
        public long now() {
            return now;
        }
    };

    private Program run(String code) {
        Program program = new Program("p", code, port, Runnable::run, o -> {
            assertNull(outcome, "the outcome came twice");
            outcome = o;
        });
        program.start();
        return program;
    }

    private void answerLast(String result) {
        pending.remove(callIds.get(callIds.size() - 1)).accept(result);
    }

    private static String job(String task) {
        return ApiReply.job(task).toString();
    }

    private static String value(Object value) {
        return ApiReply.value(new com.google.gson.Gson().toJsonTree(value)).toString();
    }

    private static String error(String message) {
        return ApiReply.error(ErrorKind.FAILED, message, null, null).toString();
    }

    private static EventQueue.Entry finished(String text) {
        return new EventQueue.Entry(EventTypes.TASK_FINISHED, text, 0, true);
    }

    private JsonObject receipt() {
        return JsonParser.parseString(outcome.receipt()).getAsJsonObject();
    }

    private String message() {
        return receipt().get("message").getAsString();
    }

    @Test
    void aProgramRunsItsCallsOneByOneAndWaitsForBodyWork() {
        Program program = run("""
                move.go("ores", {arrive = "dig"})
                work.dig("ores")
                work.collect()
                return "all done"
                """);
        assertEquals(List.of("move.go ores --arrive dig"), lines);
        assertEquals(List.of("p#1"), callIds, "the n-th call of a program is <program>#<n>");

        answerLast(job("t1"));
        assertEquals(1, lines.size(), "t1 has not ended: the next call is not sent");
        program.taskFinished(finished("t1 done"));
        assertEquals("work.dig ores", lines.get(1));

        answerLast(value(Map.of("dug", 4)));
        assertEquals("work.collect", lines.get(2));
        answerLast(job("t2"));
        assertNull(outcome, "the last job is waited for too");
        program.taskFinished(finished("t2 done"));

        assertTrue(receipt().get("success").getAsBoolean(), outcome.receipt());
        assertTrue(message().startsWith("The script ran to the end: 3 calls"), message());
        assertTrue(message().contains("\nline 1 move.go: ok — t1 done\nline 2 work.dig: ok — {dug = 4}\n"
                + "line 3 work.collect: ok — t2 done\nreturned: all done"), message());
        assertEquals(3, outcome.calls().size());
        assertEquals("work.dig", outcome.calls().get(1).function());
        assertNull(outcome.calls().get(1).kind());
        assertEquals(List.of("p#1", "p#2", "p#3"), callIds);
    }

    @Test
    void aCallsValueComesBackAsATable() {
        run("""
                local me = area.has("ores")
                return me.pos.y
                """);
        answerLast(value(Map.of("name", "Aria", "pos", Map.of("x", 1.5, "y", 64, "z", -3))));
        assertTrue(receipt().get("success").getAsBoolean(), outcome.receipt());
        assertEquals(64, receipt().getAsJsonObject("data").get("returned").getAsInt());
    }

    @Test
    void aCallThatDoesNotFitItsActionFailsAtItsLineWithoutBeingSent() {
        Program program = run("""
                local ok, err = pcall(work.dig, "ores", {bad = 1})
                print(err)
                work.dig("ores")
                """);
        assertEquals(List.of("work.dig ores"), lines, "the call that did not read was not sent");
        answerLast(job("t1"));
        program.taskFinished(finished("t1 done"));
        assertTrue(message().startsWith("The script ran to the end: 1 call"), message());
        assertTrue(message().contains("line 1 work.dig: bad_argument — there is no option bad; usage: work.dig(place)"),
                message());
        assertEquals(java.util.Arrays.asList("bad_argument", null), outcome.calls().stream().map(ScriptCall.Called::kind).toList(),
                "a call refused at its line counts as a call with its kind");
    }

    @Test
    void theProgramBranchesOnAResultAndAnErrorEndsItAtThatLine() {
        run("""
                local ok, err = pcall(function() work.dig("ores") end)
                if not ok then error("could not dig: " .. err) end
                work.collect()
                """);
        answerLast(error("error: out of reach\nusage: work.dig(place)\nhint: walk"));

        assertEquals(List.of("work.dig ores"), lines, "collect was not sent");
        assertFalse(receipt().get("success").getAsBoolean());
        assertTrue(message().startsWith("The script stopped at line 2 after 1 call: lua:2: could not dig: work.dig: "
                + "failed — error: out of reach\nusage: work.dig(place)\nhint: walk"), message());
        assertEquals("runtime", receipt().getAsJsonObject("data").getAsJsonObject("error").get("kind").getAsString());
        assertEquals("failed", outcome.calls().get(0).kind());
    }

    @Test
    void theJobAProgramWaitsForEndsInItsReceiptWithItsWholeAccount() {
        Program program = run("""
                move.go("ores")
                work.dig("ores")
                """);
        answerLast(job("t1"));
        program.taskFinished(finished("t1 done walked there\nbroke 2 stone on the way"));
        answerLast(job("t2"));
        program.interrupt("your owner spoke");

        assertTrue(message().contains("\nline 1 move.go: ok — t1 done: walked there\n  broke 2 stone on the way"),
                message());
    }

    @Test
    void anUrgentEventWhileTheProgramWaitsStopsItBetweenCalls() {
        Program program = run("""
                move.go("ores")
                work.dig("ores")
                """);
        answerLast(job("t1"));
        program.arrived(new EventQueue.Entry(EventTypes.OWNER_HURT, "<event>主人危险</event>", 0, true));

        assertEquals(1, lines.size(), "stopped between calls: the second was not sent");
        assertTrue(message().startsWith("The script stopped at line 1 (move.go) after 1 call: an urgent "
                + EventTypes.OWNER_HURT + " event arrived; t1 keeps running. Nothing after that ran."), message());
    }

    @Test
    void anEventThatIsNotUrgentDoesNotStopTheProgram() {
        Program program = run("""
                move.go("ores")
                work.dig("ores")
                """);
        answerLast(job("t1"));
        program.arrived(new EventQueue.Entry(EventTypes.REFLEX, "<event>left the water</event>", 0, false));
        assertNull(outcome);
        program.taskFinished(finished("t1 done"));
        assertEquals(2, lines.size());
    }

    @Test
    void anUrgentEventWhileACallRunsStopsTheProgramWhenThatCallIsDone() {
        Program program = run("""
                area.has("ores")
                work.dig("ores")
                """);
        program.arrived(new EventQueue.Entry(EventTypes.HUNGRY, "<event>hungry</event>", 0, true));
        assertNull(outcome, "the call itself is not interrupted");
        answerLast(value(true));

        assertEquals(1, lines.size());
        assertTrue(message().contains("stopped at line 1 (area.has)") && message().contains("an urgent hungry event"),
                message());
    }

    @Test
    void theOwnerSpeakingWhileTheProgramWaitsStopsItBetweenCalls() {
        Program program = run("""
                move.go("ores")
                work.dig("ores")
                """);
        answerLast(job("t1"));
        program.interrupt("your owner spoke");

        assertEquals(1, lines.size());
        assertTrue(message().startsWith("The script stopped at line 1 (move.go) after 1 call: your owner spoke; t1 "
                + "keeps running. Nothing after that ran."), message());
    }

    @Test
    void cuttingTheTurnOffMakesTheProgramReportWhereItStopped() {
        Program program = run("""
                move.go("ores")
                work.dig("ores")
                """);
        answerLast(job("t1"));
        program.cancel(true);

        assertTrue(message().contains("stopped at line 1 (move.go) after 1 call: this turn was cut off; t1 was "
                + "stopped too"), message());
    }

    @Test
    void aResultThatComesAfterTheProgramWasCutOffIsDropped() {
        Program program = run("""
                area.has("ores")
                work.dig("ores")
                """);
        Consumer<String> late = pending.get("p#1");
        program.cancel(false);
        late.accept(value(true));
        assertEquals(1, lines.size());
        assertTrue(message().contains("this turn was cut off"), message());
    }

    @Test
    void aFinishThatArrivesAfterTheProgramEndedIsForwardedNotLost() {
        Program program = run("""
                move.go("ores")
                work.dig("ores")
                """);
        answerLast(job("t1"));
        program.interrupt("your owner spoke");
        EventQueue.Entry late = finished("t1 stopped");
        program.taskFinished(late);

        assertEquals(List.of(late), forwarded, "the program no longer waits for it: it goes to her as an event");
    }

    @Test
    void aModulesCallsGoOutOneByOneAndTheModuleRecordsTheProgramThatUsedIt() {
        Program program = run("""
                local n = pit.out("ores")
                return n + 1
                """);
        assertEquals(List.of("work.dig ores"), lines);
        answerLast(job("t1"));
        program.taskFinished(finished("t1 done dug"));
        assertTrue(receipt().get("success").getAsBoolean(), outcome.receipt());
        assertEquals(4, receipt().getAsJsonObject("data").get("returned").getAsInt());
        assertEquals(List.of(new Program.Used("pit", new ScriptCall.Tally(true, 0, null))), outcome.used());
    }

    @Test
    void aProgramStoppedInsideAModuleRecordsWhereItStoppedForThatModule() {
        run("""
                local x = 1
                pit.out("ores")
                """);
        answerLast(error("out of reach"));
        assertTrue(message().startsWith("The script stopped at line 2"), message());
        assertEquals(List.of(new Program.Used("pit", new ScriptCall.Tally(false, 2, outcome.used().get(0).tally()
                .error()))), outcome.used());
    }

    @Test
    void aLoopThatNeverEndsStopsAtTheCallLimit() {
        run("""
                while area.has("ores") do
                  work.dig("ores")
                end
                """);
        int sent = 0;
        while (outcome == null) {
            String line = lines.get(lines.size() - 1);
            answerLast(line.startsWith("area.has") ? value(true) : value("dug nothing new"));
            sent++;
        }
        assertEquals(ScriptLimits.COMMANDS, sent);
        assertTrue(message().contains("it reached the limit of " + ScriptLimits.COMMANDS + " calls per run"), message());
    }

    @Test
    void aProgramRunningPastTheWallClockLimitStopsBeforeItsNextCall() {
        Program program = run("""
                move.go("ores")
                work.dig("ores")
                """);
        now = ScriptLimits.WALL_MILLIS + 1;
        answerLast(job("t1"));
        program.taskFinished(finished("t1 done"));
        assertEquals(1, lines.size());
        assertTrue(message().contains("stopped at line 2 (work.dig)") && message().contains("minutes per run"),
                message());
    }

    @Test
    void chineseWordsComeThroughTheReceiptWhole() {
        run("""
                print("砍了 3 棵")
                error("手边没有合成台", 0)
                """);
        assertTrue(message().contains("手边没有合成台") && message().contains("printed:\n砍了 3 棵"), message());
        assertEquals("手边没有合成台", receipt().getAsJsonObject("data").getAsJsonObject("error").get("message")
                .getAsString());
    }

    /** 假执行口当场回结果的:几百次调用不递归、不爆栈。 */
    @Test
    void callsThatAnswerAtOnceDoNotRecurse() {
        Program.Port atOnce = new Program.Port() {
            @Override
            public ScriptCatalog catalog() {
                return CATALOG;
            }

            @Override
            public Invocation invocation(ScriptRun.Call call) {
                return port.invocation(call);
            }

            @Override
            public void dispatch(String callId, Invocation invocation, Consumer<String> done) {
                done.accept(value(true));
            }

            @Override
            public ScriptCall.Finish finish(EventQueue.Entry entry) {
                return null;
            }

            @Override
            public void forward(EventQueue.Entry entry) {
            }

            @Override
            public long now() {
                return 0;
            }
        };
        new Program("q", "for i = 1, 150 do area.has(\"ores\") end", atOnce, Runnable::run, o -> outcome = o).start();
        assertEquals(150, outcome.calls().size());
    }
}
