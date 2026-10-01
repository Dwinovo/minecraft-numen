package com.dwinovo.numen.agent.script.lua;

import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptRun;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一次 Lua 运行:命令函数在调用处让出、拿到结局接着跑;沙箱里没有能装载代码或碰外面的东西;不调命令的死循环被中断。
 */
class CobaltLuaTest {

    private static final CobaltLua LUA = new CobaltLua();

    private static final ScriptCatalog CATALOG = new ScriptCatalog(Map.of(
            "work", Map.of("dig", new ScriptCatalog.Verb(null), "collect", new ScriptCatalog.Verb(null)),
            "move", Map.of("goto", new ScriptCatalog.Verb(null)),
            "area", Map.of("parts", new ScriptCatalog.Verb("parts"), "has", new ScriptCatalog.Verb("has"))));

    private final List<String> printed = new ArrayList<>();

    private ScriptRun run(String code, String... args) {
        return LUA.start("t", code, List.of(args), CATALOG, printed::add);
    }

    private static ScriptRun.Result ok(String text) {
        return new ScriptRun.Result(true, text, new JsonObject());
    }

    private static ScriptRun.Result value(String key, com.google.gson.JsonElement v) {
        JsonObject data = new JsonObject();
        data.add(key, v);
        return new ScriptRun.Result(true, "", data);
    }

    @Test
    void aCommandYieldsAtTheCallAndTheScriptGoesOnWithItsResult() {
        ScriptRun run = run("""
                local r = work.dig("ores/g3")
                if r.ok then print("dug: " .. r.text) end
                move.goto("ores", {arrive = "dig", alter = "natural"})
                """);
        ScriptRun.Call first = assertInstanceOf(ScriptRun.Call.class, run.start());
        assertEquals("work.dig", first.function());
        assertEquals(1, first.line());
        assertEquals(List.of("ores/g3"), first.args());
        assertTrue(first.options().isEmpty());

        ScriptRun.Call second = assertInstanceOf(ScriptRun.Call.class, run.resume(ok("4 blocks")));
        assertEquals(List.of("dug: 4 blocks"), printed);
        assertEquals(3, second.line());
        assertEquals(List.of("ores"), second.args());
        assertEquals(Map.of("arrive", "dig", "alter", "natural"), second.options());

        ScriptRun.Done done = assertInstanceOf(ScriptRun.Done.class, run.resume(ok("arrived")));
        assertTrue(done.ok());
    }

    @Test
    void numbersBooleansAndListsArriveAsJavaValues() {
        ScriptRun.Call call = assertInstanceOf(ScriptRun.Call.class,
                run("move.goto(120, 64.5, -35, true, {\"a\", \"b\"})").start());
        assertEquals(List.of(120L, 64.5, -35L, true, List.of("a", "b")), call.args());
    }

    @Test
    void aQueryReturnsItsValueDirectlyForLoops() {
        ScriptRun run = run("""
                for _, p in ipairs(area.parts("ores")) do print(p) end
                while area.has("ores") do work.dig("ores") end
                """);
        assertInstanceOf(ScriptRun.Call.class, run.start());
        JsonArray parts = new JsonArray();
        parts.add("ores/g1");
        parts.add("ores/g2");
        ScriptRun.Call has = assertInstanceOf(ScriptRun.Call.class, run.resume(value("parts", parts)));
        assertEquals(List.of("ores/g1", "ores/g2"), printed);
        assertEquals("area.has", has.function());
        assertInstanceOf(ScriptRun.Call.class, run.resume(value("has", new com.google.gson.JsonPrimitive(true))));
        assertInstanceOf(ScriptRun.Call.class, run.resume(ok("dug")));
        assertTrue(assertInstanceOf(ScriptRun.Done.class,
                run.resume(value("has", new com.google.gson.JsonPrimitive(false)))).ok());
    }

    @Test
    void aFailedQueryRaisesAtTheCallAndPcallCatchesIt() {
        ScriptRun run = run("""
                local ok, err = pcall(area.parts, "nope")
                print(ok, err)
                area.has("nope")
                """);
        run.start();
        assertInstanceOf(ScriptRun.Call.class, run.resume(new ScriptRun.Result(false, "there is no area nope",
                new JsonObject())));
        assertEquals(1, printed.size());
        assertTrue(printed.get(0).startsWith("false\t"), printed.get(0));
        assertTrue(printed.get(0).contains("area.parts: there is no area nope"), printed.get(0));

        ScriptRun.Done done = assertInstanceOf(ScriptRun.Done.class, run.resume(new ScriptRun.Result(false,
                "there is no area nope", new JsonObject())));
        assertFalse(done.ok());
        assertEquals(3, done.line(), done.error());
        assertTrue(done.error().contains("area.has: there is no area nope"), done.error());
    }

    @Test
    void aFailedActionReturnsATableWithOkFalse() {
        ScriptRun run = run("""
                local r = work.dig("ores")
                if not r.ok then error("could not dig: " .. r.text) end
                """);
        run.start();
        ScriptRun.Done done = assertInstanceOf(ScriptRun.Done.class,
                run.resume(new ScriptRun.Result(false, "out of reach", new JsonObject())));
        assertFalse(done.ok());
        assertEquals(2, done.line());
        assertTrue(done.error().contains("could not dig: out of reach"), done.error());
    }

    @Test
    void anErrorWithoutAPositionStillSaysWhichLine() {
        ScriptRun run = run("""
                local r = work.dig("ores")
                if not r.ok then error("could not dig", 0) end
                """);
        run.start();
        ScriptRun.Done done = assertInstanceOf(ScriptRun.Done.class,
                run.resume(new ScriptRun.Result(false, "out of reach", new JsonObject())));
        assertEquals("could not dig", done.error());
        assertEquals(2, done.line());
    }

    @Test
    void argumentsArriveAsDotsAndArg() {
        ScriptRun run = run("""
                local where = ...
                print(where, arg[1], #arg)
                """, "ores");
        assertTrue(assertInstanceOf(ScriptRun.Done.class, run.start()).ok());
        assertEquals(List.of("ores\tores\t1"), printed);
    }

    @Test
    void theSandboxHasNoWayToLoadCodeOrReachOutside() {
        ScriptRun run = run("""
                print(load, loadstring, dofile, loadfile, require, io, os, debug, setfenv, getfenv, string.dump)
                print(type(string.format), type(table.insert), type(math.floor), type(coroutine.wrap))
                """);
        assertTrue(assertInstanceOf(ScriptRun.Done.class, run.start()).ok());
        assertEquals("nil\tnil\tnil\tnil\tnil\tnil\tnil\tnil\tnil\tnil\tnil", printed.get(0));
        assertEquals("function\tfunction\tfunction\tfunction", printed.get(1));
    }

    @Test
    void aLoopThatNeverCallsACommandIsStoppedAndPcallCannotCatchIt() {
        ScriptRun run = run("""
                local n = 0
                while true do
                  pcall(function() while true do n = n + 1 end end)
                end
                """);
        ScriptRun.Done done = assertInstanceOf(ScriptRun.Done.class, run.start());
        assertFalse(done.ok());
        assertTrue(done.line() == 2 || done.line() == 3, "停在循环里: " + done.line());
        assertTrue(done.error().contains("without calling a command"), done.error());
    }

    @Test
    void theBudgetIsPerSliceSoALongScriptWithCommandsRuns() {
        ScriptRun run = run("""
                for i = 1, 3 do
                  local n = 0
                  for j = 1, 200000 do n = n + j end
                  work.dig("ores")
                end
                """);
        ScriptRun.Step step = run.start();
        for (int i = 0; i < 3; i++) {
            assertInstanceOf(ScriptRun.Call.class, step);
            step = run.resume(ok("dug"));
        }
        assertTrue(assertInstanceOf(ScriptRun.Done.class, step).ok());
    }

    @Test
    void aCommandInsideACoroutineTheScriptMadeIsRefused() {
        ScriptRun.Done done = assertInstanceOf(ScriptRun.Done.class, run("""
                local co = coroutine.create(function() work.dig("ores") end)
                local ok, err = coroutine.resume(co)
                error(err, 0)
                """).start());
        assertFalse(done.ok());
        assertTrue(done.error().contains("not from inside a coroutine"), done.error());
    }

    @Test
    void aSyntaxErrorSaysWhichLine() {
        assertNull(LUA.check("mine", "work.dig('x')\n"));
        String error = LUA.check("mine", "local x = 1\nif x then\nwork.dig(\n");
        assertTrue(error.startsWith("mine:"), error);
        assertEquals(4, CobaltLua.lineOf(error), error);

        ScriptRun.Done done = assertInstanceOf(ScriptRun.Done.class, run("x = = 1").start());
        assertFalse(done.ok());
        assertEquals(1, done.line());
    }

    @Test
    void theSummaryIsTheFirstCommentLine() {
        assertEquals("Dig out an area.", LUA.summary("\n-- Dig out an area.\n-- usage: mine <area>\nwork.dig(...)"));
        assertNull(LUA.summary("work.dig('x')\n-- late comment"));
    }

    @Test
    void anUnknownGroupIsAnOrdinaryLuaError() {
        ScriptRun.Done done = assertInstanceOf(ScriptRun.Done.class, run("fly.away()").start());
        assertFalse(done.ok());
        assertTrue(done.error().contains("fly"), done.error());
    }
}
