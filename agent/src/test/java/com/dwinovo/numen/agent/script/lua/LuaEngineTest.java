package com.dwinovo.numen.agent.script.lua;

import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptRun;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lua 这一种语言接到脚本层:命令函数在调用处交出一条命令、拿到结局接着跑;成功直接返回值、失败抛错;声明了返回项的命令成败都
 * 返回那一项;撞上 Lua 自己的名字加 {@code _};停下时脚本线程放手。
 */
class LuaEngineTest {

    private static final ScriptCatalog CATALOG = new ScriptCatalog(Map.of(
            "work", Map.of("dig", new ScriptCatalog.Verb(null), "collect", new ScriptCatalog.Verb(null)),
            "move", Map.of("goto", new ScriptCatalog.Verb(null)),
            "area", Map.of("parts", new ScriptCatalog.Verb("parts"), "has", new ScriptCatalog.Verb("has"))));

    private static final LuaEngine LUA = new LuaEngine();

    private final List<String> printed = Collections.synchronizedList(new ArrayList<>());

    private ScriptRun run(String code, String... args) {
        return LUA.start("t", code, List.of(args), CATALOG, printed::add);
    }

    private static ScriptRun.Result ok(String text) {
        return new ScriptRun.Result(true, text, new JsonObject());
    }

    private static ScriptRun.Result failed(String text) {
        return new ScriptRun.Result(false, text, new JsonObject());
    }

    private static ScriptRun.Result data(boolean ok, String key, com.google.gson.JsonElement value) {
        JsonObject data = new JsonObject();
        data.add(key, value);
        return new ScriptRun.Result(ok, "", data);
    }

    @Test
    void aCommandIsHandedOverAtItsCallAndTheScriptGoesOnWithItsResult() {
        ScriptRun run = run("""
                local said = work.dig("ores/g3")
                print("dug: " .. said)
                move.goto_({120, 64, -35}, {arrive = "dig", alter = "natural"})
                """);
        ScriptRun.Call first = assertInstanceOf(ScriptRun.Call.class, run.start());
        assertEquals("work.dig", first.function());
        assertEquals(1, first.line());
        assertEquals(List.of("ores/g3"), first.args());
        assertTrue(first.options().isEmpty());

        ScriptRun.Call second = assertInstanceOf(ScriptRun.Call.class, run.resume(ok("4 blocks")));
        assertEquals(List.of("dug: 4 blocks"), printed);
        assertEquals("move.goto_", second.function());
        assertEquals("goto", second.verb(), "命令名不变,只是脚本里的写法加了后缀");
        assertEquals(3, second.line());
        assertEquals(List.of(List.of(120L, 64L, -35L)), second.args());
        assertEquals(Map.of("arrive", "dig", "alter", "natural"), second.options());

        assertTrue(assertInstanceOf(ScriptRun.Done.class, run.resume(ok("arrived"))).ok());
    }

    @Test
    void aSucceedingCommandReturnsItsDataAndAFailingOneRaisesAtTheCall() {
        ScriptRun run = run("""
                local r = work.collect()
                print(r.picked, r.kinds[2])
                local ok, err = pcall(function() work.dig("ores") end)
                print(ok, err)
                work.dig("ores")
                """);
        run.start();
        JsonObject picked = new JsonObject();
        picked.addProperty("picked", 3);
        JsonArray kinds = new JsonArray();
        kinds.add("raw_iron");
        kinds.add("cobblestone");
        picked.add("kinds", kinds);
        run.resume(new ScriptRun.Result(true, "picked up 3", picked));
        run.resume(failed("out of reach"));
        ScriptRun.Done done = assertInstanceOf(ScriptRun.Done.class, run.resume(failed("out of reach")));
        assertEquals(List.of("3\tcobblestone", "false\tt:3: work.dig: out of reach"), printed);
        assertFalse(done.ok());
        assertEquals(5, done.line());
        assertTrue(done.error().contains("t:5: work.dig: out of reach"), done.error());
    }

    @Test
    void aDeclaredValueComesBackWhetherTheCommandSucceededOrNot() {
        ScriptRun run = run("""
                for _, p in ipairs(area.parts("ores")) do print(p) end
                while area.has("ores") do work.dig("ores") end
                print("done")
                """);
        run.start();
        JsonArray parts = new JsonArray();
        parts.add("ores/g1");
        parts.add("ores/g2");
        assertInstanceOf(ScriptRun.Call.class, run.resume(data(true, "parts", parts)));
        assertInstanceOf(ScriptRun.Call.class, run.resume(data(true, "has", new JsonPrimitive(true))));
        assertInstanceOf(ScriptRun.Call.class, run.resume(ok("dug")));
        assertTrue(assertInstanceOf(ScriptRun.Done.class,
                run.resume(data(false, "has", new JsonPrimitive(false)))).ok(), "没剩是 false,不是报错");
        assertEquals(List.of("ores/g1", "ores/g2", "done"), printed);
    }

    @Test
    void aValueQueryWithoutItsValueIsAnError() {
        ScriptRun run = run("area.has('nope')");
        run.start();
        ScriptRun.Done done = assertInstanceOf(ScriptRun.Done.class, run.resume(failed("there is no area nope")));
        assertFalse(done.ok());
        assertTrue(done.error().contains("area.has: there is no area nope"), done.error());
    }

    @Test
    void aRefusedCallFailsAtTheCallWithoutRunning() {
        ScriptRun run = run("local ok, err = pcall(function() work.dig(1, 2) end)\nprint(err)");
        run.start();
        assertTrue(assertInstanceOf(ScriptRun.Done.class, run.refuse("takes 1 object, got 2")).ok());
        assertEquals(List.of("t:1: work.dig: takes 1 object, got 2"), printed);
    }

    @Test
    void namesLuaAlreadyUsesGetATrailingUnderscore() {
        assertEquals("goto_", LUA.functionName("goto"));
        assertEquals("string_", LUA.functionName("string"));
        assertEquals("dig", LUA.functionName("dig"));
        assertEquals("move.goto_", LUA.function("move", "goto"));
        assertTrue(LUA.check("t", "move.goto(1)") != null, "goto 是保留字,原名写不出来");
        assertNull(LUA.check("t", "move.goto_(1)"));
    }

    @Test
    void optionsGoLastAndArgumentsArriveAsDots() {
        ScriptRun run = run("local where = ...\nwork.dig(where, {count = 4})", "ores");
        ScriptRun.Call call = assertInstanceOf(ScriptRun.Call.class, run.start());
        assertEquals(List.of("ores"), call.args());
        assertEquals(Map.of("count", 4L), call.options());
        run.close();
    }

    @Test
    void closingAScriptThatWaitsForAResultLetsItsThreadGo() throws InterruptedException {
        ScriptRun run = run("work.dig('ores')\nprint('never')");
        assertInstanceOf(ScriptRun.Call.class, run.start());
        run.close();
        Thread.sleep(100);
        assertTrue(printed.isEmpty());
    }

    @Test
    void theSummaryIsTheFirstCommentLine() {
        assertEquals("Dig out an area.", LUA.summary("\n-- Dig out an area.\n-- usage: mine <area>\nwork.dig(...)"));
        assertNull(LUA.summary("work.dig('x')\n-- late comment"));
    }

    @Test
    void anEndlessLoopEndsTheRun() {
        ScriptRun.Done done = assertInstanceOf(ScriptRun.Done.class,
                run("local n = 0\nwhile true do pcall(function() n = n + 1 end) end").start());
        assertFalse(done.ok());
        assertEquals(2, done.line());
        assertTrue(done.error().contains("without calling a host function"), done.error());
    }
}
