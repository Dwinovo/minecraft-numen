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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lua 这一种语言接到脚本层:API 函数在调用处交出一次调用、拿到结局接着跑;成功直接返回值、失败抛错;声明了返回项的成败都
 * 返回那一项;撞上 Lua 自己的名字加 {@code _};停下时脚本线程放手;库先跑、库函数调到的记在脚本里调它的那一行;只读不跑时
 * 记下调了哪些函数;跑完交出返回值。
 */
class LuaEngineTest {

    /** 一段库:往 move 表里加一个函数,调两个宿主函数。 */
    private static final String WALK = """
            -- Walking helpers.

            -- Plan a route to a place, then walk it.
            function move.goto_(place, opts)
              route.plan(place)
              return move.go(place)
            end

            local function helper() end
            function sweep(a, b) end
            """;

    private static final ScriptCatalog CATALOG = new ScriptCatalog(Map.of(
            "work", Map.of("dig", new ScriptCatalog.Verb(null), "collect", new ScriptCatalog.Verb(null)),
            "move", Map.of("go", new ScriptCatalog.Verb(null)),
            "route", Map.of("plan", new ScriptCatalog.Verb(null)),
            "area", Map.of("parts", new ScriptCatalog.Verb("parts"), "has", new ScriptCatalog.Verb("has"))),
            Map.of("walk", WALK));

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
    void aCallIsHandedOverWhereItIsMadeAndTheScriptGoesOnWithItsResult() {
        ScriptRun run = run("""
                local said = work.dig("ores/g3")
                print("dug: " .. said)
                route.plan({120, 64, -35}, {arrive = "dig", alter = "natural"})
                """);
        ScriptRun.Call first = assertInstanceOf(ScriptRun.Call.class, run.start());
        assertEquals("work.dig", first.function());
        assertEquals(1, first.line());
        assertEquals(List.of("ores/g3"), first.args());
        assertTrue(first.options().isEmpty());

        ScriptRun.Call second = assertInstanceOf(ScriptRun.Call.class, run.resume(ok("4 blocks")));
        assertEquals(List.of("dug: 4 blocks"), printed);
        assertEquals("route.plan", second.function());
        assertEquals(3, second.line());
        assertEquals(List.of(List.of(120L, 64L, -35L)), second.args());
        assertEquals(Map.of("arrive", "dig", "alter", "natural"), second.options());

        assertTrue(assertInstanceOf(ScriptRun.Done.class, run.resume(ok("arrived"))).ok());
    }

    @Test
    void aLibraryFunctionCallsTheApiFromTheScriptsOwnLine() {
        ScriptRun run = run("""
                local x = 1
                local r = move.goto_("home")
                return {walked = r}
                """);
        ScriptRun.Call plan = assertInstanceOf(ScriptRun.Call.class, run.start());
        assertEquals("route.plan", plan.function());
        assertEquals(2, plan.line(), "库函数里的调用记在脚本里调它的那一行");
        ScriptRun.Call go = assertInstanceOf(ScriptRun.Call.class, run.resume(ok("planned")));
        assertEquals("move.go", go.function());
        assertEquals(2, go.line());
        ScriptRun.Done done = assertInstanceOf(ScriptRun.Done.class, run.resume(ok("t1 done")));
        assertTrue(done.ok());
        assertEquals(Map.of("walked", "t1 done"), done.value(), "跑完交出 return 的值");
    }

    @Test
    void theFunctionsALibraryDefinesComeWithTheCommentsAboveThem() {
        List<com.dwinovo.numen.agent.script.ScriptEngine.Defined> defined = LUA.functions(WALK);
        assertEquals(List.of(
                new com.dwinovo.numen.agent.script.ScriptEngine.Defined("move.goto_", List.of("place", "opts"),
                        "Plan a route to a place, then walk it."),
                new com.dwinovo.numen.agent.script.ScriptEngine.Defined("sweep", List.of("a", "b"), "")), defined);
    }

    @Test
    void readingWithoutRunningListsTheCallsAndTheirArguments() {
        List<ScriptRun.Call> calls = LUA.calls("example", """
                work.dig("ores", {count = 2})
                move.goto_({1, 2, 3}, {arrive = "use"})
                """, CATALOG).calls();
        assertEquals(2, calls.size());
        assertEquals("work.dig", calls.get(0).function());
        assertEquals(Map.of("count", 2L), calls.get(0).options());
        assertEquals("move", calls.get(1).group(), "库函数记成它自己的那一次调用,不进它的正文");
        assertEquals("goto_", calls.get(1).verb());
        assertEquals(List.of(List.of(1L, 2L, 3L)), calls.get(1).args());
        assertThrows(IllegalArgumentException.class, () -> LUA.calls("example", "work.dig(", CATALOG),
                "语法错读不通");
        com.dwinovo.numen.agent.script.ScriptEngine.Reading stopped = LUA.calls("example", """
                work.dig("ores")
                for _, p in ipairs(area.parts("ores")) do work.dig(p) end
                """, CATALOG);
        assertEquals(2, stopped.calls().size(), "停下之前调到的照记");
        assertTrue(stopped.error() != null, "拿返回值往下算的写法跑到那儿停下,说出原因");
        assertTrue(LUA.calls("example", "nope.dig()", CATALOG).error() != null);
        assertNull(LUA.calls("example", "work.collect()", CATALOG).error());
    }

    @Test
    void aSucceedingCallReturnsItsDataAndAFailingOneRaisesAtTheCall() {
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
    void aDeclaredValueComesBackWhetherTheCallSucceededOrNot() {
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
        assertEquals("move.go", LUA.function("move", "go"));
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
