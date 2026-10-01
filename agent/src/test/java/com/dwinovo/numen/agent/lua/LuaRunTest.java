package com.dwinovo.numen.agent.lua;

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
class LuaRunTest {

    private static final LuaCatalog CATALOG = new LuaCatalog(Map.of(
            "work", Map.of("dig", new LuaCatalog.Verb(null), "collect", new LuaCatalog.Verb(null)),
            "move", Map.of("goto", new LuaCatalog.Verb(null)),
            "area", Map.of("parts", new LuaCatalog.Verb("parts"), "has", new LuaCatalog.Verb("has"))));

    private final List<String> printed = new ArrayList<>();

    private LuaRun run(String code, String... args) {
        return new LuaRun("t", code, List.of(args), CATALOG, printed::add);
    }

    private static LuaRun.Result ok(String text) {
        return new LuaRun.Result(true, text, new JsonObject());
    }

    private static LuaRun.Result value(String key, com.google.gson.JsonElement v) {
        JsonObject data = new JsonObject();
        data.add(key, v);
        return new LuaRun.Result(true, "", data);
    }

    @Test
    void aCommandYieldsAtTheCallAndTheScriptGoesOnWithItsResult() {
        LuaRun run = run("""
                local r = work.dig("ores/g3")
                if r.ok then print("dug: " .. r.text) end
                move.goto("ores", {arrive = "dig", alter = "natural"})
                """);
        LuaRun.Call first = assertInstanceOf(LuaRun.Call.class, run.start());
        assertEquals("work.dig", first.function());
        assertEquals(1, first.line());
        assertEquals(List.of("ores/g3"), first.args());
        assertTrue(first.options().isEmpty());

        LuaRun.Call second = assertInstanceOf(LuaRun.Call.class, run.resume(ok("4 blocks")));
        assertEquals(List.of("dug: 4 blocks"), printed);
        assertEquals(3, second.line());
        assertEquals(List.of("ores"), second.args());
        assertEquals(Map.of("arrive", "dig", "alter", "natural"), second.options());

        LuaRun.Done done = assertInstanceOf(LuaRun.Done.class, run.resume(ok("arrived")));
        assertTrue(done.ok());
    }

    @Test
    void numbersBooleansAndListsArriveAsJavaValues() {
        LuaRun.Call call = assertInstanceOf(LuaRun.Call.class,
                run("move.goto(120, 64.5, -35, true, {\"a\", \"b\"})").start());
        assertEquals(List.of(120L, 64.5, -35L, true, List.of("a", "b")), call.args());
    }

    @Test
    void aQueryReturnsItsValueDirectlyForLoops() {
        LuaRun run = run("""
                for _, p in ipairs(area.parts("ores")) do print(p) end
                while area.has("ores") do work.dig("ores") end
                """);
        assertInstanceOf(LuaRun.Call.class, run.start());
        JsonArray parts = new JsonArray();
        parts.add("ores/g1");
        parts.add("ores/g2");
        LuaRun.Call has = assertInstanceOf(LuaRun.Call.class, run.resume(value("parts", parts)));
        assertEquals(List.of("ores/g1", "ores/g2"), printed);
        assertEquals("area.has", has.function());
        assertInstanceOf(LuaRun.Call.class, run.resume(value("has", new com.google.gson.JsonPrimitive(true))));
        assertInstanceOf(LuaRun.Call.class, run.resume(ok("dug")));
        assertTrue(assertInstanceOf(LuaRun.Done.class,
                run.resume(value("has", new com.google.gson.JsonPrimitive(false)))).ok());
    }

    @Test
    void aFailedQueryRaisesAtTheCallAndPcallCatchesIt() {
        LuaRun run = run("""
                local ok, err = pcall(area.parts, "nope")
                print(ok, err)
                area.has("nope")
                """);
        run.start();
        assertInstanceOf(LuaRun.Call.class, run.resume(new LuaRun.Result(false, "there is no area nope",
                new JsonObject())));
        assertEquals(1, printed.size());
        assertTrue(printed.get(0).startsWith("false\t"), printed.get(0));
        assertTrue(printed.get(0).contains("area.parts: there is no area nope"), printed.get(0));

        LuaRun.Done done = assertInstanceOf(LuaRun.Done.class, run.resume(new LuaRun.Result(false,
                "there is no area nope", new JsonObject())));
        assertFalse(done.ok());
        assertEquals(3, done.line(), done.error());
        assertTrue(done.error().contains("area.has: there is no area nope"), done.error());
    }

    @Test
    void aFailedActionReturnsATableWithOkFalse() {
        LuaRun run = run("""
                local r = work.dig("ores")
                if not r.ok then error("could not dig: " .. r.text) end
                """);
        run.start();
        LuaRun.Done done = assertInstanceOf(LuaRun.Done.class,
                run.resume(new LuaRun.Result(false, "out of reach", new JsonObject())));
        assertFalse(done.ok());
        assertEquals(2, done.line());
        assertTrue(done.error().contains("could not dig: out of reach"), done.error());
    }

    @Test
    void anErrorWithoutAPositionStillSaysWhichLine() {
        LuaRun run = run("""
                local r = work.dig("ores")
                if not r.ok then error("could not dig", 0) end
                """);
        run.start();
        LuaRun.Done done = assertInstanceOf(LuaRun.Done.class,
                run.resume(new LuaRun.Result(false, "out of reach", new JsonObject())));
        assertEquals("could not dig", done.error());
        assertEquals(2, done.line());
    }

    @Test
    void argumentsArriveAsDotsAndArg() {
        LuaRun run = run("""
                local where = ...
                print(where, arg[1], #arg)
                """, "ores");
        assertTrue(assertInstanceOf(LuaRun.Done.class, run.start()).ok());
        assertEquals(List.of("ores\tores\t1"), printed);
    }

    @Test
    void theSandboxHasNoWayToLoadCodeOrReachOutside() {
        LuaRun run = run("""
                print(load, loadstring, dofile, loadfile, require, io, os, debug, setfenv, getfenv, string.dump)
                print(type(string.format), type(table.insert), type(math.floor), type(coroutine.wrap))
                """);
        assertTrue(assertInstanceOf(LuaRun.Done.class, run.start()).ok());
        assertEquals("nil\tnil\tnil\tnil\tnil\tnil\tnil\tnil\tnil\tnil\tnil", printed.get(0));
        assertEquals("function\tfunction\tfunction\tfunction", printed.get(1));
    }

    @Test
    void aLoopThatNeverCallsACommandIsStoppedAndPcallCannotCatchIt() {
        LuaRun run = run("""
                local n = 0
                while true do
                  pcall(function() while true do n = n + 1 end end)
                end
                """);
        LuaRun.Done done = assertInstanceOf(LuaRun.Done.class, run.start());
        assertFalse(done.ok());
        assertTrue(done.line() == 2 || done.line() == 3, "停在循环里: " + done.line());
        assertTrue(done.error().contains("without calling a command"), done.error());
    }

    @Test
    void theBudgetIsPerSliceSoALongScriptWithCommandsRuns() {
        LuaRun run = run("""
                for i = 1, 3 do
                  local n = 0
                  for j = 1, 200000 do n = n + j end
                  work.dig("ores")
                end
                """);
        LuaRun.Step step = run.start();
        for (int i = 0; i < 3; i++) {
            assertInstanceOf(LuaRun.Call.class, step);
            step = run.resume(ok("dug"));
        }
        assertTrue(assertInstanceOf(LuaRun.Done.class, step).ok());
    }

    @Test
    void aCommandInsideACoroutineTheScriptMadeIsRefused() {
        LuaRun.Done done = assertInstanceOf(LuaRun.Done.class, run("""
                local co = coroutine.create(function() work.dig("ores") end)
                local ok, err = coroutine.resume(co)
                error(err, 0)
                """).start());
        assertFalse(done.ok());
        assertTrue(done.error().contains("not from inside a coroutine"), done.error());
    }

    @Test
    void aSyntaxErrorSaysWhichLine() {
        assertNull(LuaRun.check("mine", "work.dig('x')\n"));
        String error = LuaRun.check("mine", "local x = 1\nif x then\nwork.dig(\n");
        assertTrue(error.startsWith("mine:"), error);
        assertEquals(4, LuaRun.lineOf(error), error);

        LuaRun.Done done = assertInstanceOf(LuaRun.Done.class, run("x = = 1").start());
        assertFalse(done.ok());
        assertEquals(1, done.line());
    }

    @Test
    void theSummaryIsTheFirstCommentLine() {
        assertEquals("Dig out an area.", LuaRun.summary("\n-- Dig out an area.\n-- usage: mine <area>\nwork.dig(...)"));
        assertNull(LuaRun.summary("work.dig('x')\n-- late comment"));
    }

    @Test
    void anUnknownGroupIsAnOrdinaryLuaError() {
        LuaRun.Done done = assertInstanceOf(LuaRun.Done.class, run("fly.away()").start());
        assertFalse(done.ok());
        assertTrue(done.error().contains("fly"), done.error());
    }
}
