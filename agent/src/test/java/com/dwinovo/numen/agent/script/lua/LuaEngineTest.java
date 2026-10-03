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
 * Lua 这一种语言接到脚本层:API 函数在调用处交出一次调用、拿到结局接着跑;成功返回回执的数据(声明了返回项的是那一项,没有数据是
 * nil)、失败抛错误值({@code kind}、{@code message}、{@code hint}……,tostring 可读);撞上 Lua 自己的名字加 {@code _};停下时脚本
 * 线程放手;库先跑、库函数调到的记在脚本里调它的那一行;只读不跑时记下调了哪些函数;跑完交出返回值。
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
        return ScriptRun.Result.ok(text, new JsonObject());
    }

    private static ScriptRun.Result failed(String text) {
        return new ScriptRun.Result(false, text, new JsonObject(), "failed", null);
    }

    private static ScriptRun.Result data(String key, com.google.gson.JsonElement value) {
        JsonObject data = new JsonObject();
        data.add(key, value);
        return ScriptRun.Result.ok("", data);
    }

    @Test
    void aCallIsHandedOverWhereItIsMadeAndTheScriptGoesOnWithItsResult() {
        ScriptRun run = run("""
                local r = work.dig("ores/g3")
                print("dug: " .. r.dug)
                route.plan({x = 120, y = 64, z = -35}, {arrive = "dig", alter = "natural"})
                """);
        ScriptRun.Call first = assertInstanceOf(ScriptRun.Call.class, run.start());
        assertEquals("work.dig", first.function());
        assertEquals(1, first.line());
        assertEquals(List.of("ores/g3"), first.args());
        assertTrue(first.options().isEmpty());

        ScriptRun.Call second = assertInstanceOf(ScriptRun.Call.class, run.resume(data("dug", new JsonPrimitive(4))));
        assertEquals(List.of("dug: 4"), printed, "脚本拿到的是数据,不是回执那句话");
        assertEquals("route.plan", second.function());
        assertEquals(3, second.line());
        assertEquals(List.of(Map.of("x", 120L, "y", 64L, "z", -35L)), second.args());
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
        ScriptRun.Done done = assertInstanceOf(ScriptRun.Done.class, run.resume(data("route", new JsonPrimitive("home"))));
        assertTrue(done.ok());
        assertEquals(Map.of("walked", Map.of("route", "home")), done.value(), "跑完交出 return 的值");
    }

    @Test
    void theFunctionsALibraryDefinesComeWithTheCommentsAboveThem() {
        List<com.dwinovo.numen.agent.script.ScriptEngine.Defined> defined = LUA.functions(WALK);
        assertEquals(List.of(
                new com.dwinovo.numen.agent.script.ScriptEngine.Defined("move.goto_", List.of("place", "opts"),
                        List.of("-- Plan a route to a place, then walk it.")),
                new com.dwinovo.numen.agent.script.ScriptEngine.Defined("sweep", List.of("a", "b"), List.of())), defined);
        assertEquals("Plan a route to a place, then walk it.", LUA.summaryOf(defined.get(0)));
    }

    @Test
    void readingWithoutRunningListsTheCallsAndTheirArguments() {
        List<ScriptRun.Call> calls = LUA.calls("example", """
                work.dig("ores", {count = 2})
                move.goto_({x = 1, y = 2, z = 3}, {arrive = "use"})
                """, CATALOG).calls();
        assertEquals(2, calls.size());
        assertEquals("work.dig", calls.get(0).function());
        assertEquals(Map.of("count", 2L), calls.get(0).options());
        assertEquals("move", calls.get(1).group(), "库函数记成它自己的那一次调用,不进它的正文");
        assertEquals("goto_", calls.get(1).verb());
        assertEquals(List.of(Map.of("x", 1L, "y", 2L, "z", 3L)), calls.get(1).args());
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
                print(ok, err.kind, err.fn, tostring(err))
                work.dig("ores")
                """);
        run.start();
        JsonObject picked = new JsonObject();
        picked.addProperty("picked", 3);
        JsonArray kinds = new JsonArray();
        kinds.add("raw_iron");
        kinds.add("cobblestone");
        picked.add("kinds", kinds);
        run.resume(ScriptRun.Result.ok("picked up 3", picked));
        run.resume(failed("out of reach"));
        ScriptRun.Done done = assertInstanceOf(ScriptRun.Done.class, run.resume(failed("out of reach")));
        assertEquals(List.of("3\tcobblestone", "false\tfailed\twork.dig\twork.dig: failed — out of reach"), printed);
        assertFalse(done.ok());
        assertEquals(5, done.line());
        assertEquals("work.dig: failed — out of reach", done.error());
        assertEquals("failed", done.failure().get("kind"));
    }

    /** 失败的种类与下一步跟着错误值走:脚本按 kind 分支,hint 照抄,失败时的数据在 data 里;tostring 一并写出 hint。 */
    @Test
    void aFailureCarriesItsKindHintAndData() {
        ScriptRun run = run("""
                local ok, err = pcall(work.dig, "ores")
                if err.kind == "out_of_reach" then print(err.hint, err.data.nearest.x) end
                print(tostring(err))
                """);
        run.start();
        JsonObject nearest = new JsonObject();
        nearest.addProperty("x", 7);
        JsonObject data = new JsonObject();
        data.add("nearest", nearest);
        assertTrue(assertInstanceOf(ScriptRun.Done.class, run.resume(new ScriptRun.Result(false, "too far", data,
                "out_of_reach", "move.goto_(\"ores\", {arrive = \"dig\"})"))).ok());
        assertEquals(List.of("move.goto_(\"ores\", {arrive = \"dig\"})\t7",
                "work.dig: out_of_reach — too far\nhint: move.goto_(\"ores\", {arrive = \"dig\"})"), printed);
    }

    /** 没有数据的成功返回 nil,不返回那句话。 */
    @Test
    void aCallWithoutDataReturnsNil() {
        ScriptRun run = run("print(work.collect() == nil)");
        run.start();
        assertTrue(assertInstanceOf(ScriptRun.Done.class, run.resume(ok("collected"))).ok());
        assertEquals(List.of("true"), printed);
    }

    /** raise 抛同一种错误值;没接住时整段的错误值就是它,带上它的 kind。 */
    @Test
    void raiseThrowsTheSameKindOfErrorValue() {
        ScriptRun.Done done = assertInstanceOf(ScriptRun.Done.class,
                run("raise(\"stuck\", \"went round in circles\", \"build.left(\\\"house\\\")\", {left = 3})").start());
        assertFalse(done.ok());
        assertEquals("stuck", done.failure().get("kind"));
        assertEquals(Map.of("left", 3L), done.failure().get("data"));
        assertEquals("stuck — went round in circles\nhint: build.left(\"house\")", done.error());
        ScriptRun.Done wrong = assertInstanceOf(ScriptRun.Done.class, run("raise(1)").start());
        assertEquals("bad_argument", wrong.failure().get("kind"));
    }

    /** print 一张表写成 Lua 的字面量,键按名字排。 */
    @Test
    void printWritesATableAsLua() {
        assertTrue(assertInstanceOf(ScriptRun.Done.class,
                run("print({pos = {z = 3, x = 1, y = 2}, name = \"iron_ore\"}, {1, 2})").start()).ok());
        assertEquals(List.of("{name = \"iron_ore\", pos = {x = 1, y = 2, z = 3}}\t{1, 2}"), printed);
    }

    @Test
    void aDeclaredValueIsWhatTheCallReturns() {
        ScriptRun run = run("""
                for _, p in ipairs(area.parts("ores")) do print(p) end
                while area.has("ores") do work.dig("ores") end
                print("done")
                """);
        run.start();
        JsonArray parts = new JsonArray();
        parts.add("ores/g1");
        parts.add("ores/g2");
        assertInstanceOf(ScriptRun.Call.class, run.resume(data("parts", parts)));
        assertInstanceOf(ScriptRun.Call.class, run.resume(data("has", new JsonPrimitive(true))));
        assertInstanceOf(ScriptRun.Call.class, run.resume(ok("dug")));
        assertTrue(assertInstanceOf(ScriptRun.Done.class,
                run.resume(data("has", new JsonPrimitive(false)))).ok(), "没剩是 false,不是报错");
        assertEquals(List.of("ores/g1", "ores/g2", "done"), printed);
    }

    @Test
    void aFailedValueQueryRaises() {
        ScriptRun run = run("area.has('nope')");
        run.start();
        ScriptRun.Done done = assertInstanceOf(ScriptRun.Done.class, run.resume(new ScriptRun.Result(false,
                "there is no area named nope", new JsonObject(), "not_found", "area.list()")));
        assertFalse(done.ok());
        assertEquals("area.has: not_found — there is no area named nope\nhint: area.list()", done.error());
    }

    @Test
    void aRefusedCallFailsAtTheCallWithoutRunning() {
        ScriptRun run = run("local ok, err = pcall(function() work.dig(1, 2) end)\nprint(err.kind, tostring(err))");
        run.start();
        assertTrue(assertInstanceOf(ScriptRun.Done.class, run.refuse(new com.dwinovo.numen.agent.script.ApiError(
                com.dwinovo.numen.agent.script.ErrorKind.BAD_ARGUMENT, "takes 1 object, got 2", null))).ok());
        assertEquals(List.of("bad_argument\twork.dig: bad_argument — takes 1 object, got 2"), printed);
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

    /** 第 ① 层的函数与组谁都换不掉:停在那一行,种类是程序自己的运行错,说换个名字;往组里加别的名字照常。 */
    @Test
    void anApiFunctionCannotBeRedefinedButAGroupTakesNewNames() {
        ScriptRun.Done done = assertInstanceOf(ScriptRun.Done.class, run("""
                function move.mine() return 1 end
                function move.go() end
                """).start());
        assertFalse(done.ok());
        assertEquals(2, done.line());
        assertEquals("runtime", done.failure().get("kind"));
        assertTrue(done.error().startsWith("runtime — move.go is an API function"), done.error());
        ScriptRun.Done shadowed = assertInstanceOf(ScriptRun.Done.class, run("move = {}").start());
        assertTrue(shadowed.error().contains("move is built into the API"), shadowed.error());
    }
}
