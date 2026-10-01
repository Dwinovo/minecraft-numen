package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptRun;
import com.dwinovo.numen.agent.script.ScriptCall;
import com.dwinovo.numen.agent.provider.LlmToolCall;
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
 * 一轮里的调用按顺序执行:一个做完才派下一个,后台身体活等它收尾;等的时候来了急件,余下的逐条回"没执行"。
 *
 * <p>假的执行口把派出去的调用停在 {@link #pending} 里等测试替它回结果。结果写成 {@code running tN} 表示受理了一件会自己
 * 收尾的后台活 tN;队列里 task_finished 的正文就是收尾的那件的编号。两个读法都是测试自己的约定,真的写法在 api
 * ({@code TaskDispatch}、{@code NumenEvents})。
 */
class SerialCallsTest {

    private final Map<String, Consumer<String>> pending = new LinkedHashMap<>();
    private final List<String> dispatched = new ArrayList<>();
    private final Map<String, String> results = new LinkedHashMap<>();
    private int settles;

    /** 假的执行口:调用停在 {@link #pending} 里;{@code lua} 工具的参数就是脚本正文;脚本里的一行写成 {@code 函数 参数...}。 */
    private final class FakePort implements SerialCalls.Port {
        final java.util.function.BiConsumer<LlmToolCall, Consumer<String>> invoker;
        final List<String> tallies = new ArrayList<>();
        long now;

        FakePort(java.util.function.BiConsumer<LlmToolCall, Consumer<String>> invoker) {
            this.invoker = invoker;
        }

        @Override
        public void invoke(LlmToolCall call, Consumer<String> done) {
            invoker.accept(call, done);
        }

        @Override
        public String scriptOf(LlmToolCall call) {
            return "lua".equals(call.name()) ? json(call.arguments()).get("code").getAsString() : null;
        }

        @Override
        public LlmToolCall commandCall(String id, String line) {
            return command(id, line);
        }

        @Override
        public String leftRunning(String result) {
            return result.startsWith("running ") ? result.substring("running ".length()) : null;
        }

        @Override
        public ScriptCall.Finish finish(EventQueue.Entry entry) {
            if (!EventTypes.TASK_FINISHED.equals(entry.type())) {
                return null;
            }
            String[] parts = entry.text().split(" ", 2);
            return new ScriptCall.Finish(parts[0], parts.length > 1 ? parts[1] : "done", "");
        }

        @Override
        public ScriptCatalog catalog() {
            return CATALOG;
        }

        @Override
        public String line(ScriptRun.Call call) {
            StringBuilder line = new StringBuilder(call.group() + " " + call.verb());
            call.args().forEach(a -> line.append(' ').append(a));
            call.options().forEach((k, v) -> line.append(" --").append(k).append(' ').append(v));
            return line.toString();
        }

        @Override
        public void tally(String script, ScriptCall.Tally tally) {
            tallies.add(script + " " + (tally.ok() ? "ok" : "line " + tally.line()));
        }

        @Override
        public long now() {
            return now;
        }
    }

    private static final ScriptCatalog CATALOG = new ScriptCatalog(java.util.Map.of(
            "work", java.util.Map.of("dig", new ScriptCatalog.Verb(null), "collect", new ScriptCatalog.Verb(null)),
            "move", java.util.Map.of("goto", new ScriptCatalog.Verb(null)),
            "area", java.util.Map.of("has", new ScriptCatalog.Verb("has")),
            "script", java.util.Map.of("run", new ScriptCatalog.Verb(null))));

    private final FakePort port = new FakePort((call, done) -> {
        dispatched.add(call.id());
        pending.put(call.id(), done);
    });

    private final SerialCalls calls = new SerialCalls(port);

    private final ToolPort.Sink sink = new ToolPort.Sink() {
        @Override
        public void started(LlmToolCall call) {
        }

        @Override
        public void finished(LlmToolCall call, String resultJson) {
            assertNull(results.put(call.id(), resultJson), call.id() + " 拿到了两个结果");
        }

        @Override
        public void settled() {
            settles++;
        }
    };

    private static LlmToolCall call(String id) {
        return new LlmToolCall(id, "command", "{}");
    }

    private void answer(String id, String result) {
        pending.remove(id).accept(result);
    }

    private static EventQueue.Entry finished(String taskId) {
        return new EventQueue.Entry(EventTypes.TASK_FINISHED, taskId, 0, true);
    }

    private static EventQueue.Entry ownerWords(String words) {
        return new EventQueue.Entry(EventTypes.QUERY, EventQueue.query(words), 0, false);
    }

    @Test
    void theNextCallGoesOutOnlyWhenTheLastOneHasItsResult() {
        calls.run(List.of(call("a"), call("b")), sink);
        assertEquals(List.of("a"), dispatched);

        answer("a", "{\"success\":true}");
        assertEquals(List.of("a", "b"), dispatched);
        assertEquals(0, settles);

        answer("b", "{\"success\":true}");
        assertEquals(1, settles);
        assertEquals(List.of("a", "b"), List.copyOf(results.keySet()));
    }

    @Test
    void aBackgroundJobHoldsTheRestUntilItsOwnEndArrives() {
        calls.run(List.of(call("a"), call("b")), sink);
        answer("a", "running t3");
        assertEquals("running t3", results.get("a"), "受理回执照常是 a 的结果");
        assertEquals(List.of("a"), dispatched, "t3 还没做完,b 不派");

        calls.arrived(finished("t2"), false);
        calls.arrived(new EventQueue.Entry(EventTypes.REFLEX, "<event>换了口气</event>", 0, false), false);
        assertEquals(List.of("a"), dispatched, "别的活收尾、不急的事都不算");

        calls.arrived(finished("t3"), true);
        assertEquals(List.of("a", "b"), dispatched, "t3 做完了才派 b");
        answer("b", "{\"success\":true}");
        assertEquals(1, settles);
    }

    @Test
    void aJobThatNeverEndsDoesNotHoldAnything() {
        calls.run(List.of(call("follow"), call("look")), sink);
        answer("follow", "{\"success\":true,\"data\":{\"standing\":true}}");
        assertEquals(List.of("follow", "look"), dispatched, "常驻的活没有收尾,不等");
    }

    @Test
    void theOwnerSpeakingWhileWaitingLeavesTheRestUnrunAndSaysWhy() {
        calls.run(List.of(call("a"), call("b"), call("c")), sink);
        answer("a", "running t3");

        calls.arrived(ownerWords("先停一下"), true);

        assertEquals(List.of("a"), dispatched, "余下的一个都没派");
        assertEquals(1, settles, "这一批结算,模型下一次调用读到主人的话");
        String why = ToolOutcome.failure("Not run: while you were waiting for t3 to finish, your owner spoke. "
                + "t3 keeps running; read it, then decide what to do next.");
        assertEquals(why, results.get("b"));
        assertEquals(why, results.get("c"));
        assertEquals(3, results.size(), "每个调用恰好一个结果");
    }

    @Test
    void anUrgentEventWhileWaitingNamesItsKind() {
        calls.run(List.of(call("a"), call("b")), sink);
        answer("a", "running t3");

        calls.arrived(new EventQueue.Entry(EventTypes.OWNER_HURT, "<event>主人危险</event>", 0, true), true);

        assertTrue(ToolOutcome.failed(results.get("b")));
        assertTrue(results.get("b").contains("an urgent " + EventTypes.OWNER_HURT + " event arrived"), results.get("b"));
        assertEquals(1, settles);
    }

    @Test
    void inputWhileAToolIsStillRunningDropsNothing() {
        calls.run(List.of(call("a"), call("b")), sink);
        calls.arrived(ownerWords("先停一下"), true);
        assertEquals(List.of("a"), dispatched);
        assertTrue(results.isEmpty(), "工具本身有界短:等它的结果,输入跟下一次调用走");

        answer("a", "{\"success\":true}");
        assertEquals(List.of("a", "b"), dispatched);
    }

    /** 后面没有调用在等它:这一批当场结算,活在后台做,她照常说话、想事。 */
    @Test
    void aJobAtTheEndOfTheReplyLeavesHerFreeAtOnce() {
        calls.run(List.of(call("a")), sink);
        answer("a", "running t3");
        assertEquals(1, settles);

        calls.arrived(ownerWords("挖得怎么样了"), true);
        assertEquals(1, results.size(), "没有在等,输入不碰任何调用");
    }

    @Test
    void cancellingReturnsWhatHadNoResultAndLateResultsAreDropped() {
        calls.run(List.of(call("a"), call("b")), sink);
        Consumer<String> late = pending.get("a");

        assertEquals(List.of("a", "b"), calls.cancel(false));
        late.accept("{\"success\":true}");
        assertTrue(results.isEmpty(), "放弃之后回来的结果无处可报");
        assertFalse(calls.holds("a"));
        assertEquals(0, settles);
    }

    @Test
    void synchronousResultsDoNotRecurse() {
        SerialCalls atOnce = new SerialCalls(new FakePort((call, done) -> done.accept("{\"success\":true}")));
        List<LlmToolCall> many = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            many.add(call("c" + i));
        }
        atOnce.run(many, sink);
        assertEquals(20_000, results.size());
        assertEquals(1, settles);
    }

    @Test
    void aBatchHandedOverWhileSettlingIsRunToo() {
        SerialCalls atOnce = new SerialCalls(new FakePort((call, done) -> done.accept("{\"success\":true}")));
        List<LlmToolCall> second = List.of(call("second"));
        atOnce.run(List.of(call("first")), new ToolPort.Sink() {
            @Override
            public void started(LlmToolCall call) {
            }

            @Override
            public void finished(LlmToolCall call, String resultJson) {
                results.put(call.id(), resultJson);
            }

            @Override
            public void settled() {
                settles++;
                if (settles == 1) {
                    atOnce.run(second, sink);   // 模型当场又回了一批
                }
            }
        });
        assertTrue(results.containsKey("second"), "结算时当场收下的下一批照样执行");
        assertEquals(2, settles);
    }

    // ---- 脚本 ----

    private static LlmToolCall lua(String id, String code) {
        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("code", code);
        return new LlmToolCall(id, "lua", args.toString());
    }

    private static LlmToolCall command(String id, String line) {
        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("command", line);
        return new LlmToolCall(id, "command", args.toString());
    }

    /** 派出去的那一行命令的原文(假执行口把脚本的一行记成调用的参数)。 */
    private final List<String> lines = new ArrayList<>();

    private final FakePort linePort = new FakePort((call, done) -> {
        dispatched.add(call.id());
        lines.add(json(call.arguments()).get("command").getAsString());
        pending.put(call.id(), done);
    });

    private final SerialCalls scripts = new SerialCalls(linePort);

    private void answerLast(String result) {
        String id = dispatched.get(dispatched.size() - 1);
        pending.remove(id).accept(result);
    }

    private static com.google.gson.JsonObject json(String s) {
        return com.google.gson.JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void aScriptRunsItsCommandsOneByOneAndWaitsForBodyWork() {
        scripts.run(List.of(lua("s", """
                move.goto_("ores", {arrive = "dig"})
                work.dig("ores")
                work.collect()
                """)), sink);
        assertEquals(List.of("move goto ores --arrive dig"), lines);

        answerLast("running t1");
        assertEquals(1, lines.size(), "t1 还没收尾,下一行不派");
        scripts.arrived(finished("t1"), true);
        assertEquals("work dig ores", lines.get(1));

        answerLast("{\"success\":true,\"message\":\"dug 4 blocks\"}");
        assertEquals("work collect", lines.get(2));
        answerLast("running t2");
        assertTrue(results.isEmpty(), "脚本里的最后一件也等收尾");
        scripts.arrived(finished("t2"), true);

        com.google.gson.JsonObject receipt = json(results.get("s"));
        assertTrue(receipt.get("success").getAsBoolean(), receipt.toString());
        String msg = receipt.get("message").getAsString();
        assertTrue(msg.startsWith("The script ran to the end: 3 commands"), msg);
        assertTrue(msg.contains("line 1 move.goto_: ok — t1 done"), msg);
        assertTrue(msg.contains("line 2 work.dig: ok — dug 4 blocks"), msg);
        assertEquals(1, settles);
    }

    @Test
    void theScriptBranchesOnAResultAndAnErrorEndsItAtThatLine() {
        scripts.run(List.of(lua("s", """
                local ok, err = pcall(function() work.dig("ores") end)
                if not ok then error("could not dig: " .. err) end
                work.collect()
                """), command("after", "x")), sink);
        answerLast("{\"success\":false,\"message\":\"out of reach\"}");

        assertEquals(List.of("work dig ores", "x"), lines, "collect 没派;脚本结束后照常派下一个调用");
        com.google.gson.JsonObject receipt = json(results.get("s"));
        assertFalse(receipt.get("success").getAsBoolean());
        String msg = receipt.get("message").getAsString();
        assertTrue(msg.startsWith("The script stopped at line 2 after 1 command: lua:2: could not dig: lua:1: work.dig: "
                + "out of reach"), msg);
        assertTrue(msg.contains("line 1 work.dig: failed — out of reach"), msg);
    }

    @Test
    void theOwnerSpeakingWhileTheScriptWaitsStopsItBetweenCommands() {
        scripts.run(List.of(lua("s", """
                move.goto_("ores")
                work.dig("ores")
                """), command("after", "x")), sink);
        answerLast("running t1");
        scripts.arrived(ownerWords("先停一下"), true);

        assertEquals(1, lines.size(), "停在命令之间,第二行没派");
        String msg = json(results.get("s")).get("message").getAsString();
        assertTrue(msg.startsWith("The script stopped at line 1 (move.goto_) after 1 command: your owner spoke; t1 "
                + "keeps running. Nothing after that ran."), msg);
        assertTrue(ToolOutcome.failed(results.get("after")), "这一批余下的调用照旧回没执行");
        assertTrue(results.get("after").contains("while you were waiting for t1 to finish, your owner spoke"),
                results.get("after"));
        assertEquals(1, settles);
    }

    @Test
    void anUrgentEventWhileALineRunsStopsTheScriptWhenThatLineIsDone() {
        scripts.run(List.of(lua("s", """
                area.has("ores")
                work.dig("ores")
                """)), sink);
        scripts.arrived(ownerWords("等等"), true);
        assertTrue(results.isEmpty(), "那一行本身不打断");
        answerLast("{\"success\":true,\"message\":\"yes\",\"data\":{\"has\":true}}");

        assertEquals(1, lines.size());
        String msg = json(results.get("s")).get("message").getAsString();
        assertTrue(msg.contains("stopped at line 1 (area.has)") && msg.contains("your owner spoke"), msg);
    }

    @Test
    void cuttingTheTurnOffMakesTheScriptReportWhereItStopped() {
        scripts.run(List.of(lua("s", """
                move.goto_("ores")
                work.dig("ores")
                """)), sink);
        answerLast("running t1");
        List<String> abandoned = scripts.cancel(true);

        assertTrue(abandoned.isEmpty(), "脚本交出了自己的回执,不算放弃: " + abandoned);
        String msg = json(results.get("s")).get("message").getAsString();
        assertTrue(msg.contains("stopped at line 1 (move.goto_) after 1 command: this turn was cut off; t1 was "
                + "stopped too"), msg);
        assertFalse(scripts.holds("s"));
    }

    @Test
    void aCommandThatHandsBackAScriptRunsItAndCountsTheRun() {
        scripts.run(List.of(command("c", "script run mine ores")), sink);
        answerLast("{\"success\":true,\"message\":\"ready\",\"data\":{\"run\":{\"script\":\"mine\",\"code\":"
                + "\"work.dig(...)\\nwork.collect()\",\"args\":[\"ores\"]}}}");

        assertEquals(List.of("script run mine ores", "work dig ores"), lines);
        answerLast("{\"success\":true,\"message\":\"dug\"}");
        answerLast("{\"success\":true,\"message\":\"picked up 3\"}");

        String msg = json(results.get("c")).get("message").getAsString();
        assertTrue(msg.startsWith("Script mine ran to the end: 2 commands"), msg);
        assertEquals(List.of("mine ok"), linePort.tallies);
    }

    @Test
    void aScriptCanRunANamedOneInsideAndItsEndIsThatCallsResult() {
        scripts.run(List.of(lua("s", """
                local ok, err = pcall(script.run, "mine", "ores")
                print(ok, err)
                """)), sink);
        answerLast("{\"success\":true,\"data\":{\"run\":{\"script\":\"mine\",\"code\":\"work.dig(...)\","
                + "\"args\":[\"ores\"]}}}");
        answerLast("{\"success\":false,\"message\":\"out of reach\"}");

        String msg = json(results.get("s")).get("message").getAsString();
        assertTrue(msg.contains("mine line 1 work.dig: failed — out of reach"), msg);
        assertTrue(msg.contains("line 1 script.run: failed — mine:1: work.dig: out of reach"), msg);
        assertTrue(msg.contains("printed:\nfalse\tscript.run: mine:1: work.dig: out of reach"), msg);
        assertTrue(msg.startsWith("The script ran to the end"), "父脚本接住了里面那一份的失败: " + msg);
        assertEquals(List.of("mine line 1"), linePort.tallies);
    }

    @Test
    void aLoopThatNeverEndsStopsAtTheCommandLimit() {
        scripts.run(List.of(lua("s", """
                while area.has("ores") do
                  work.dig("ores")
                end
                """)), sink);
        int sent = 0;
        while (results.isEmpty()) {
            String line = lines.get(lines.size() - 1);
            answerLast(line.startsWith("area has") ? "{\"success\":true,\"data\":{\"has\":true}}"
                    : "{\"success\":true,\"message\":\"dug nothing new\"}");
            sent++;
        }
        assertEquals(com.dwinovo.numen.agent.script.ScriptLimits.COMMANDS, sent);
        String msg = json(results.get("s")).get("message").getAsString();
        assertTrue(msg.contains("it reached the limit of " + com.dwinovo.numen.agent.script.ScriptLimits.COMMANDS
                + " commands per run"), msg);
        assertTrue(msg.contains("work.dig: 100 calls, none failed"), msg);
    }

    @Test
    void aScriptRunningPastTheWallClockLimitStopsBeforeItsNextCommand() {
        scripts.run(List.of(lua("s", """
                move.goto_("ores")
                work.dig("ores")
                """)), sink);
        linePort.now = com.dwinovo.numen.agent.script.ScriptLimits.WALL_MILLIS + 1;
        answerLast("{\"success\":true}");
        assertEquals(1, lines.size());
        String msg = json(results.get("s")).get("message").getAsString();
        assertTrue(msg.contains("stopped at line 2 (work.dig)") && msg.contains("minutes per run"), msg);
    }
}
