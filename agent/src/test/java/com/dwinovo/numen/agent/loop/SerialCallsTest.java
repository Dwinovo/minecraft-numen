package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.script.ApiReply;
import com.dwinovo.numen.agent.script.Invocation;
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
 * 一轮里的调用按顺序执行:一个做完才派下一个,脚本里的身体活等它收尾;等的时候来了急件,余下的逐条回"没执行"。
 *
 * <p>假的执行口把派出去的调用停在 {@link #pending} 里等测试替它回结果:脚本里的 API 调用回 {@link ApiReply} 的那一份
 * ({@link #job} 是受理了一件会自己收尾的后台活);队列里 task_finished 的正文就是收尾的那件的编号(后面可以跟收尾状态与它交代的话),
 * 这一条读法是测试自己的约定,真的写法在 api({@code NumenEvents})。
 */
class SerialCallsTest {

    private final Map<String, Consumer<String>> pending = new LinkedHashMap<>();
    private final List<String> dispatched = new ArrayList<>();
    private final Map<String, String> results = new LinkedHashMap<>();
    private int settles;

    /**
     * 假的执行口:调用停在 {@link #pending} 里;{@code lua} 工具的参数就是脚本正文;脚本里的一次 API 调用读成参数 JSON
     * {@code {"objects": [...], 选项...}},派出去时记成一行 {@code 函数 对象... --选项 值}。
     */
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
        public void dispatch(LlmToolCall call, Invocation invocation, Consumer<String> done) {
            invoker.accept(call, done);
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
        public ScriptCatalog catalog() {
            return CATALOG;
        }

        @Override
        public Invocation invocation(ScriptRun.Call call) {
            if (call.options().containsKey("bad")) {
                throw new com.dwinovo.numen.agent.script.ApiError(com.dwinovo.numen.agent.script.ErrorKind.BAD_ARGUMENT,
                        "there is no option bad; usage: " + call.function() + "(place)", null);
            }
            com.google.gson.JsonObject args = new com.google.gson.JsonObject();
            com.google.gson.JsonArray objects = new com.google.gson.JsonArray();
            call.args().forEach(a -> objects.add(String.valueOf(a)));
            args.add("objects", objects);
            call.options().forEach((k, v) -> args.addProperty(k, String.valueOf(v)));
            return new Invocation(call.group(), call.name(), call.function(), args);
        }

        @Override
        public void tally(String module, ScriptCall.Tally tally) {
            tallies.add(module + " " + (tally.ok() ? "ok" : "line " + tally.line()));
        }

        @Override
        public long now() {
            return now;
        }
    }

    /** 一个测试用的函数:选项名是 {@code arrive} 与 {@code bad},按顺序的对象不限。 */
    private static ScriptCatalog.Function fn(ScriptCatalog.Kind kind) {
        return new ScriptCatalog.Function(Integer.MAX_VALUE, java.util.Set.of("arrive", "bad"), kind,
                com.dwinovo.numen.agent.script.ScriptType.NOTHING, null);
    }

    private static final ScriptCatalog CATALOG = new ScriptCatalog(java.util.Map.of(
            "work", java.util.Map.of("dig", fn(ScriptCatalog.Kind.JOB), "collect", fn(ScriptCatalog.Kind.JOB)),
            "move", java.util.Map.of("go", fn(ScriptCatalog.Kind.JOB)),
            "area", java.util.Map.of("has", fn(ScriptCatalog.Kind.VALUE))), new ScriptCatalog.ModuleSource() {
                /** 一个模块:挖一处、交回 3。 */
                @Override
                public String code(String name) {
                    return "pit".equals(name) ? "local M = {}\nfunction M.out(a)\n  work.dig(a)\n  return 3\nend\n"
                            + "return M" : null;
                }

                @Override
                public List<String> names() {
                    return List.of("pit");
                }
            }, java.util.Map.of());

    /** 受理了后台活 {@code task}。 */
    private static String job(String task) {
        return ApiReply.job(task).toString();
    }

    /** 当场的值。 */
    private static String value(Object value) {
        return ApiReply.value(new com.google.gson.Gson().toJsonTree(value)).toString();
    }

    /** 失败。 */
    private static String error(String message) {
        return ApiReply.error(com.dwinovo.numen.agent.script.ErrorKind.FAILED, message, null, null).toString();
    }

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
        return new LlmToolCall(id, "mcp_tool", "{}");
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
    void anUrgentEventWhileTheScriptWaitsNamesItsKind() {
        calls.run(List.of(lua("a", "work.dig(\"ores\")"), call("b")), sink);
        answer(dispatched.get(0), job("t3"));

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

    /** 派出去的 API 调用,每次一行 {@code 函数 对象... --选项 值}。 */
    private final List<String> lines = new ArrayList<>();

    private final FakePort linePort = new FakePort((call, done) -> {
        dispatched.add(call.id());
        if (!"mcp_tool".equals(call.name())) {
            com.google.gson.JsonObject args = json(call.arguments());
            StringBuilder line = new StringBuilder(call.name());
            args.getAsJsonArray("objects").forEach(o -> line.append(' ').append(o.getAsString()));
            args.entrySet().stream().filter(e -> !e.getKey().equals("objects"))
                    .forEach(e -> line.append(" --").append(e.getKey()).append(' ').append(e.getValue().getAsString()));
            lines.add(line.toString());
        } else {
            lines.add(call.name());
        }
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
    void aScriptRunsItsCallsOneByOneAndWaitsForBodyWork() {
        scripts.run(List.of(lua("s", """
                move.go("ores", {arrive = "dig"})
                work.dig("ores")
                work.collect()
                return "all done"
                """)), sink);
        assertEquals(List.of("move.go ores --arrive dig"), lines);

        answerLast(job("t1"));
        assertEquals(1, lines.size(), "t1 还没收尾,下一次调用不派");
        scripts.arrived(finished("t1"), true);
        assertEquals("work.dig ores", lines.get(1));

        answerLast(value(java.util.Map.of("dug", 4)));
        assertEquals("work.collect", lines.get(2));
        answerLast(job("t2"));
        assertTrue(results.isEmpty(), "脚本里的最后一件也等收尾");
        scripts.arrived(finished("t2"), true);

        com.google.gson.JsonObject receipt = json(results.get("s"));
        assertTrue(receipt.get("success").getAsBoolean(), receipt.toString());
        String msg = receipt.get("message").getAsString();
        assertTrue(msg.startsWith("The script ran to the end: 3 calls"), msg);
        assertTrue(msg.contains("\nline 1 move.go: ok — t1 done\nline 2 work.dig: ok — {dug = 4}\n"
                + "line 3 work.collect: ok — t2 done\nreturned: all done"), "每次调用一行,按先后,值写成字面量: " + msg);
        assertEquals("all done", receipt.getAsJsonObject("data").get("returned").getAsString());
        assertEquals(3, receipt.getAsJsonObject("data").get("calls").getAsInt());
        assertEquals(1, settles);
    }

    /** 一次调用的值(status.self 这类):程序拿到的是读成的表,不是一串 JSON 文字。 */
    @Test
    void aCallsValueComesBackAsATable() {
        scripts.run(List.of(lua("s", """
                local me = area.has("ores")
                return me.pos.y
                """)), sink);
        answerLast(value(java.util.Map.of("name", "Aria", "pos", java.util.Map.of("x", 1.5, "y", 64, "z", -3))));
        com.google.gson.JsonObject receipt = json(results.get("s"));
        assertTrue(receipt.get("success").getAsBoolean(), receipt.toString());
        assertEquals(64, receipt.getAsJsonObject("data").get("returned").getAsInt());
    }

    @Test
    void aCallThatDoesNotFitItsActionFailsAtItsLineWithoutBeingSent() {
        scripts.run(List.of(lua("s", """
                local ok, err = pcall(work.dig, "ores", {bad = 1})
                print(err)
                work.dig("ores")
                """)), sink);
        assertEquals(List.of("work.dig ores"), lines, "读不成的那次没派出去");
        answerLast(job("t1"));
        scripts.arrived(finished("t1"), true);
        String msg = json(results.get("s")).get("message").getAsString();
        assertTrue(msg.startsWith("The script ran to the end: 1 call"), "没派出去的不算一次调用: " + msg);
        assertTrue(msg.contains("line 1 work.dig: bad_argument — there is no option bad; usage: work.dig(place)"),
                msg);
        assertTrue(msg.contains("printed:\nwork.dig: bad_argument — there is no option bad; usage: work.dig(place)"),
                msg);
    }

    @Test
    void theScriptBranchesOnAResultAndAnErrorEndsItAtThatLine() {
        scripts.run(List.of(lua("s", """
                local ok, err = pcall(function() work.dig("ores") end)
                if not ok then error("could not dig: " .. err) end
                work.collect()
                """), call("after")), sink);
        answerLast(error("error: out of reach\nusage: work.dig(place)\nhint: walk"));

        assertEquals(List.of("work.dig ores", "mcp_tool"), lines, "collect 没派;脚本结束后照常派下一个调用");
        com.google.gson.JsonObject receipt = json(results.get("s"));
        assertFalse(receipt.get("success").getAsBoolean());
        String msg = receipt.get("message").getAsString();
        assertTrue(msg.startsWith("The script stopped at line 2 after 1 call: lua:2: could not dig: work.dig: "
                + "failed — error: out of reach\nusage: work.dig(place)\nhint: walk"), "出错的行号与那次调用的整段原话: "
                + msg);
        assertTrue(msg.contains("line 1 work.dig: failed — error: out of reach"), msg);
        assertEquals("runtime", receipt.getAsJsonObject("data").getAsJsonObject("error").get("kind").getAsString(),
                "程序自己 error 的一句话是 runtime");
    }

    /**
     * 程序等着的那件活的收尾归程序:它交代的整段话写进回执里那件活的那一行(第二行起缩进),只说这一次;程序停下之后还在跑的那件,
     * 收尾不再归程序。
     */
    @Test
    void theJobAProgramWaitsForEndsInItsReceiptWithItsWholeAccount() {
        scripts.run(List.of(lua("s", """
                move.go("ores")
                work.dig("ores")
                """)), sink);
        answerLast(job("t1"));
        assertTrue(scripts.awaits(finished("t1")), "等着的那件的收尾不归程序");
        assertFalse(scripts.awaits(finished("t9")), "别的活的收尾归了程序");
        scripts.arrived(new EventQueue.Entry(EventTypes.TASK_FINISHED, "t1 done walked there\nbroke 2 stone on the way",
                0, true), false);
        answerLast(job("t2"));
        scripts.arrived(ownerWords("先停一下"), true);

        String msg = json(results.get("s")).get("message").getAsString();
        assertTrue(msg.contains("\nline 1 move.go: ok — t1 done: walked there\n  broke 2 stone on the way"), msg);
        assertFalse(scripts.awaits(finished("t2")), "程序停下了,还在跑的那件的收尾不归它");
    }

    @Test
    void theOwnerSpeakingWhileTheScriptWaitsStopsItBetweenCalls() {
        scripts.run(List.of(lua("s", """
                move.go("ores")
                work.dig("ores")
                """), call("after")), sink);
        answerLast(job("t1"));
        scripts.arrived(ownerWords("先停一下"), true);

        assertEquals(1, lines.size(), "停在调用之间,第二次没派");
        String msg = json(results.get("s")).get("message").getAsString();
        assertTrue(msg.startsWith("The script stopped at line 1 (move.go) after 1 call: your owner spoke; t1 "
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
        answerLast(value(true));

        assertEquals(1, lines.size());
        String msg = json(results.get("s")).get("message").getAsString();
        assertTrue(msg.contains("stopped at line 1 (area.has)") && msg.contains("your owner spoke"), msg);
    }

    @Test
    void cuttingTheTurnOffMakesTheScriptReportWhereItStopped() {
        scripts.run(List.of(lua("s", """
                move.go("ores")
                work.dig("ores")
                """)), sink);
        answerLast(job("t1"));
        List<String> abandoned = scripts.cancel(true);

        assertTrue(abandoned.isEmpty(), "脚本交出了自己的回执,不算放弃: " + abandoned);
        String msg = json(results.get("s")).get("message").getAsString();
        assertTrue(msg.contains("stopped at line 1 (move.go) after 1 call: this turn was cut off; t1 was "
                + "stopped too"), msg);
        assertFalse(scripts.holds("s"));
    }

    @Test
    void aModulesCallsGoOutOneByOneAndTheModuleRecordsTheProgramThatUsedIt() {
        scripts.run(List.of(lua("s", """
                local n = pit.out("ores")
                return n + 1
                """)), sink);
        assertEquals(List.of("work.dig ores"), lines);
        answerLast(job("t1"));
        scripts.arrived(new EventQueue.Entry(EventTypes.TASK_FINISHED, "t1 done dug", 0, true), false);
        com.google.gson.JsonObject receipt = json(results.get("s"));
        assertTrue(receipt.get("success").getAsBoolean(), receipt.toString());
        assertEquals(4, receipt.getAsJsonObject("data").get("returned").getAsInt());
        assertTrue(receipt.get("message").getAsString().contains("line 1 work.dig: ok — t1 done: dug"), receipt.toString());
        assertEquals(List.of("pit ok"), linePort.tallies);
    }

    @Test
    void aProgramStoppedInsideAModuleRecordsWhereItStoppedForThatModule() {
        scripts.run(List.of(lua("s", """
                local x = 1
                pit.out("ores")
                """)), sink);
        answerLast(error("out of reach"));
        String msg = json(results.get("s")).get("message").getAsString();
        assertTrue(msg.startsWith("The script stopped at line 2"), msg);
        assertEquals(List.of("pit line 2"), linePort.tallies);
    }

    @Test
    void aLoopThatNeverEndsStopsAtTheCallLimit() {
        scripts.run(List.of(lua("s", """
                while area.has("ores") do
                  work.dig("ores")
                end
                """)), sink);
        int sent = 0;
        while (results.isEmpty()) {
            String line = lines.get(lines.size() - 1);
            answerLast(line.startsWith("area.has") ? value(true) : value("dug nothing new"));
            sent++;
        }
        assertEquals(com.dwinovo.numen.agent.script.ScriptLimits.COMMANDS, sent);
        String msg = json(results.get("s")).get("message").getAsString();
        assertTrue(msg.contains("it reached the limit of " + com.dwinovo.numen.agent.script.ScriptLimits.COMMANDS
                + " calls per run"), msg);
        assertEquals(com.dwinovo.numen.agent.script.ScriptLimits.COMMANDS / 2,
                msg.lines().filter(l -> l.equals("line 2 work.dig: ok — \"dug nothing new\"")).count(), msg);
    }

    @Test
    void aScriptRunningPastTheWallClockLimitStopsBeforeItsNextCall() {
        scripts.run(List.of(lua("s", """
                move.go("ores")
                work.dig("ores")
                """)), sink);
        linePort.now = com.dwinovo.numen.agent.script.ScriptLimits.WALL_MILLIS + 1;
        answerLast(job("t1"));
        scripts.arrived(finished("t1"), true);
        assertEquals(1, lines.size());
        String msg = json(results.get("s")).get("message").getAsString();
        assertTrue(msg.contains("stopped at line 2 (work.dig)") && msg.contains("minutes per run"), msg);
    }

    /** 中文的字面量原样进回执:print 的、error 的一句话,错误值里的 message 也是。 */
    @Test
    void chineseWordsComeThroughTheReceiptWhole() {
        scripts.run(List.of(lua("s", """
                print("砍了 3 棵")
                error("手边没有合成台", 0)
                """)), sink);
        com.google.gson.JsonObject receipt = json(results.get("s"));
        String msg = receipt.get("message").getAsString();
        assertTrue(msg.contains("手边没有合成台") && msg.contains("printed:\n砍了 3 棵"), msg);
        assertEquals("手边没有合成台", receipt.getAsJsonObject("data").getAsJsonObject("error").get("message")
                .getAsString());
    }
}
