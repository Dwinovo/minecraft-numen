package com.dwinovo.lua;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 沙箱里的 Lua:语义照原生 Lua 5.2;沙箱删干净、共享的字符串元表锁住;四种预算与外部打断都停得住,pcall 包着也停;宿主函数在
 * 脚本的虚拟线程上阻塞不碰调用方;值两边互换。
 */
class LuaSandboxTest {

    private static final LuaSandbox.Limits ROOMY = new LuaSandbox.Limits(1_000_000, 10_000_000, 16 << 20,
            Duration.ofSeconds(30));

    private final List<String> printed = Collections.synchronizedList(new ArrayList<>());

    private LuaSandbox.Outcome run(String code, String... args) throws InterruptedException {
        return run(LuaSandbox.builder(ROOMY).print(printed::add).build(), code, args);
    }

    private static LuaSandbox.Outcome run(LuaSandbox sandbox, String code, String... args)
            throws InterruptedException {
        return sandbox.start("t", code, List.of(args), o -> { }).await();
    }

    private void ok(String code) throws InterruptedException {
        LuaSandbox.Outcome o = run(code);
        assertTrue(o.finished(), String.valueOf(o));
    }

    // ---- 语义 ----

    @Test
    void tablesClosuresMetatablesAndStringsBehaveLikeLua() throws InterruptedException {
        ok("""
                local t = {3, 1, 2}
                table.sort(t)
                assert(table.concat(t, ",") == "1,2,3")
                assert(#t == 3 and select("#", 1, 2) == 2)
                local function counter() local n = 0 return function() n = n + 1 return n end end
                local c = counter(); c(); assert(c() == 2)
                local v = setmetatable({}, {__index = function(_, k) return k .. "!" end,
                                            __add = function(a, b) return 42 end})
                assert(v.hi == "hi!" and v + v == 42)
                assert(("abc"):upper() == "ABC" and string.format("%d-%s", 7, "x") == "7-x")
                assert(("a,b,c"):gsub(",", ";") == "a;b;c")
                assert(string.match("key=val", "(%w+)=(%w+)") == "key")
                assert(math.max(3, 9, 2) == 9 and math.floor(2.7) == 2 and 7 % 3 == 1)
                local s = 0 for i = 1, 10 do s = s + i end assert(s == 55)
                for k, v in pairs({a = 1}) do assert(k == "a" and v == 1) end
                assert(tostring(nil) == "nil" and tonumber("0x10") == 16)
                """);
    }

    @Test
    void errorsAndPcallCarryTheirLineAndValue() throws InterruptedException {
        ok("""
                local ok, err = pcall(function() error("boom") end)
                assert(not ok and err:find("t:1: boom"), err)
                local ok2, err2 = pcall(error, {code = 7})
                assert(not ok2 and err2.code == 7)
                assert(not pcall(function() return nil + 1 end))
                """);
        LuaSandbox.Outcome o = run("local x = 1\nerror('nope')\n");
        assertEquals(LuaSandbox.Ending.ERROR, o.ending());
        assertEquals(2, o.line());
        assertTrue(o.message().contains("t:2: nope"), o.message());
        LuaSandbox.Outcome bare = run("local x = 1\nerror('as written', 0)\n");
        assertEquals(LuaSandbox.Ending.ERROR, bare.ending());
        assertEquals(2, bare.line());
        assertEquals("as written", bare.message(), "error(消息, 0) 不带位置");
    }

    @Test
    void aSyntaxErrorSaysWhereAndCheckReadsWithoutRunning() throws InterruptedException {
        LuaSandbox.Outcome o = run("local x = 1\nif x then\n");
        assertEquals(LuaSandbox.Ending.UNREADABLE, o.ending());
        assertEquals(3, o.line(), o.message());
        assertNull(LuaSandbox.check("mine", "print(1)"));
        assertEquals(2, LuaSandbox.lineOf(LuaSandbox.check("mine", "local a\nlocal = 1")));
        assertTrue(LuaSandbox.check("mine", "move.goto(1)").startsWith("mine:1:"), "goto 是保留字");
    }

    @Test
    void argumentsArriveAsDotsAndArg() throws InterruptedException {
        LuaSandbox.Outcome o = run("local a, b = ...\nassert(a == 'ores' and b == '3' and arg[1] == 'ores' and #arg == 2)\n"
                + "print(a, b)", "ores", "3");
        assertTrue(o.finished(), String.valueOf(o));
        assertEquals(List.of("ores\t3"), printed);
    }

    // ---- 沙箱 ----

    @Test
    void theSandboxHasNothingThatLoadsCodeOrReachesOutside() throws InterruptedException {
        ok("""
                for _, name in ipairs({"load", "loadstring", "dofile", "loadfile", "require", "collectgarbage",
                                       "io", "os", "debug", "package", "coroutine", "luajava", "module"}) do
                  assert(_G[name] == nil, name)
                end
                assert(string.dump == nil)
                """);
        assertTrue(LuaSandbox.STANDARD_GLOBALS.containsAll(List.of("string", "table", "math", "pairs", "print")));
        assertFalse(LuaSandbox.STANDARD_GLOBALS.contains("load"));
    }

    @Test
    void oneScriptCannotChangeTheStringsAnotherSees() throws InterruptedException {
        LuaSandbox.Outcome hack = run("""
                assert(not pcall(function() string.rep = function() return "HACKED" end end))
                assert(not pcall(function() getmetatable("").__index.rep = function() return "HACKED" end end))
                assert(not pcall(rawset, string, "upper", print))
                assert(not pcall(table.insert, string, 1))
                """);
        assertTrue(hack.finished(), String.valueOf(hack));
        ok("assert(('x'):rep(2) == 'xx' and ('x'):upper() == 'X')");
    }

    // ---- 预算 ----

    @Test
    void anEndlessLoopIsStoppedEvenInsidePcall() throws InterruptedException {
        LuaSandbox.Outcome o = run("""
                local n = 0
                while true do
                  pcall(function() while true do n = n + 1 end end)
                end
                """);
        assertEquals(LuaSandbox.Ending.SLICE, o.ending());
        assertTrue(o.line() == 2 || o.line() == 3, "停在循环里: " + o);
    }

    @Test
    void theSliceBudgetResetsAtEachHostCallButTheTotalDoesNot() throws InterruptedException {
        LuaSandbox sandbox = LuaSandbox.builder(new LuaSandbox.Limits(5_000, 40_000, 1 << 20, Duration.ofSeconds(30)))
                .function("tick", args -> null).build();
        LuaSandbox.Outcome fine = run(sandbox, "for i = 1, 3 do for j = 1, 500 do end tick() end");
        assertTrue(fine.finished(), String.valueOf(fine));
        LuaSandbox.Outcome all = run(sandbox, "while true do for j = 1, 500 do end tick() end");
        assertEquals(LuaSandbox.Ending.INSTRUCTIONS, all.ending());
    }

    @Test
    void doublingAStringStopsAtTheByteBudgetNotAtTheHeap() throws InterruptedException {
        LuaSandbox.Outcome o = run("local s = 'x'\nfor i = 1, 40 do s = s .. s end\nreturn #s");
        assertEquals(LuaSandbox.Ending.STRINGS, o.ending());
        assertEquals(2, o.line());
        assertEquals(LuaSandbox.Ending.STRINGS, run("return string.rep('x', 2000000000)").ending());
        assertEquals(LuaSandbox.Ending.STRINGS,
                run("local t = {} for i = 1, 100 do t[i] = ('y'):rep(1000000) end").ending());
    }

    @Test
    void theWallClockStopsAScriptWaitingInAHostFunction() throws InterruptedException {
        LuaSandbox sandbox = LuaSandbox.builder(new LuaSandbox.Limits(1_000_000, 10_000_000, 1 << 20,
                        Duration.ofMillis(100)))
                .function("nap", args -> {
                    Thread.sleep(150);
                    return null;
                }).build();
        LuaSandbox.Outcome o = run(sandbox, "nap()\nprint('never')");
        assertEquals(LuaSandbox.Ending.WALL_CLOCK, o.ending());
        assertEquals(1, o.line());
    }

    @Test
    void deepRecursionEndsAsAStackOverflow() throws InterruptedException {
        LuaSandbox.Outcome o = run("local function f() return 1 + f() end\nf()");
        assertTrue(o.ending() == LuaSandbox.Ending.STACK || o.ending() == LuaSandbox.Ending.SLICE, String.valueOf(o));
    }

    // ---- 打断 ----

    @Test
    void interruptingABusyScriptStopsItAtTheNextInstruction() throws Exception {
        LuaSandbox sandbox = LuaSandbox.builder(new LuaSandbox.Limits(Long.MAX_VALUE, Long.MAX_VALUE, 1 << 20,
                Duration.ofMinutes(1))).build();
        LuaSandbox.Running running = sandbox.start("t", "while true do pcall(function() while true do end end) end",
                List.of(), o -> { });
        Thread.sleep(100);
        long t0 = System.nanoTime();
        running.interrupt();
        LuaSandbox.Outcome o = running.await();
        assertEquals(LuaSandbox.Ending.INTERRUPTED, o.ending());
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(1));
    }

    @Test
    void interruptingAScriptBlockedInAHostFunctionStopsItThereEvenUnderPcall() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        LuaSandbox sandbox = LuaSandbox.builder(ROOMY)
                .function("work", "wait", args -> {
                    inside.countDown();
                    Thread.sleep(60_000);
                    return null;
                }).build();
        LuaSandbox.Running running = sandbox.start("t", "x = 1\nwhile true do pcall(work.wait) end", List.of(), o -> { });
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        running.interrupt();
        LuaSandbox.Outcome o = running.await();
        assertEquals(LuaSandbox.Ending.INTERRUPTED, o.ending());
        assertEquals(2, o.line());
    }

    // ---- 宿主函数 ----

    @Test
    void aBlockingHostFunctionRunsOnTheScriptsOwnVirtualThread() throws Exception {
        AtomicReference<Thread> seen = new AtomicReference<>();
        CountDownLatch release = new CountDownLatch(1);
        LuaSandbox sandbox = LuaSandbox.builder(ROOMY)
                .function("move", "to", args -> {
                    seen.set(Thread.currentThread());
                    release.await();
                    return "arrived";
                }).print(printed::add).build();
        AtomicReference<LuaSandbox.Outcome> done = new AtomicReference<>();
        LuaSandbox.Running running = sandbox.start("t", "print(move.to('x'))", List.of(), done::set);
        // 调用方当场拿回控制:脚本在自己的线程上等着
        assertNull(done.get());
        release.countDown();
        assertTrue(running.await().finished());
        assertTrue(seen.get().isVirtual() && seen.get() != Thread.currentThread());
        assertEquals(List.of("arrived"), printed);
    }

    /** 读一组里没有的函数当场报错,报的话由宿主写,收到的是那一组的名字、读的名字与组里有的名字。 */
    @Test
    void readingAFunctionAGroupLacksRaisesTheHostsWords() throws InterruptedException {
        LuaSandbox sandbox = LuaSandbox.builder(ROOMY)
                .function("move", "to", args -> "arrived")
                .missing((table, key, present) -> new LuaSandbox.ScriptError(table + "|" + key + "|" + present))
                .build();
        LuaSandbox.Outcome o = run(sandbox, "local f = move.too");
        assertFalse(o.finished());
        assertEquals(1, o.line());
        assertTrue(o.message().contains("move|too|[to]"), o.message());
    }

    @Test
    void valuesCrossBothWaysAndAFailedCallIsACatchableErrorAtTheCall() throws InterruptedException {
        AtomicReference<List<Object>> got = new AtomicReference<>();
        LuaSandbox sandbox = LuaSandbox.builder(ROOMY)
                .function("scan", "blocks", args -> {
                    got.set(args);
                    return Map.of("count", 3L, "parts", List.of("ores/g1", "ores/g2"), "ok", true);
                })
                .function("work", "dig", args -> {
                    throw new LuaSandbox.ScriptError("work.dig: out of reach");
                })
                .function("where", args -> LuaSandbox.currentLine())
                .print(printed::add).build();
        LuaSandbox.Outcome o = run(sandbox, """
                local r = scan.blocks("iron_ore", 12, 1.5, true, {"a", "b"}, {into = "ores"}, nil)
                assert(r.count == 3 and r.parts[2] == "ores/g2" and r.ok == true)
                local ok, err = pcall(function() work.dig("ores") end)
                print(ok, err)
                print(where())
                """);
        assertTrue(o.finished(), String.valueOf(o));
        assertEquals(List.of("iron_ore", 12L, 1.5, true, List.of("a", "b"), Map.of("into", "ores")),
                got.get().subList(0, 6));
        assertNull(got.get().get(6));
        assertEquals("false\tt:3: work.dig: out of reach", printed.get(0));
        assertEquals("5", printed.get(1));
    }

    /**
     * 宿主可以抛一张错误值的表:脚本 pcall 接住的就是这张表,按字段分支;tostring 是宿主写的那段文字;没接住时结局的那句话也是它,
     * 结局另带上这张表。
     */
    @Test
    void aHostCanRaiseAnErrorTableTheScriptBranchesOn() throws InterruptedException {
        LuaSandbox sandbox = LuaSandbox.builder(ROOMY)
                .function("work", "dig", args -> {
                    throw new LuaSandbox.ScriptError(Map.of("kind", "out_of_reach", "message", "too far"));
                })
                .errors(e -> e.get("kind") + ": " + e.get("message"))
                .print(printed::add).build();
        LuaSandbox.Outcome caught = run(sandbox, """
                local ok, err = pcall(work.dig, "ores")
                print(ok, err.kind, err.message, tostring(err))
                """);
        assertTrue(caught.finished(), String.valueOf(caught));
        assertEquals("false\tout_of_reach\ttoo far\tout_of_reach: too far", printed.get(0));

        LuaSandbox.Outcome thrown = run(sandbox, "work.dig(\"ores\")");
        assertFalse(thrown.finished());
        assertEquals("out_of_reach: too far", thrown.message());
        assertEquals(Map.of("kind", "out_of_reach", "message", "too far"), thrown.error());
        assertEquals(1, thrown.line());
    }

    /** 脚本自己 error(表) 没接住时,结局的那句话同样按宿主的写法写成文字,结局带上这张表。 */
    @Test
    void aScriptsOwnErrorTableIsWrittenTheHostsWay() throws InterruptedException {
        LuaSandbox sandbox = LuaSandbox.builder(ROOMY).errors(e -> "kind " + e.get("kind")).build();
        LuaSandbox.Outcome o = run(sandbox, "error({kind = \"stuck\"})");
        assertEquals("kind stuck", o.message());
        assertEquals(Map.of("kind", "stuck"), o.error());
    }

    /** print 一张表(没有自己的 __tostring)按宿主给的写法写出,键按名字排;有 __tostring 的照它。 */
    @Test
    void printWritesAPlainTableTheHostsWay() throws InterruptedException {
        LuaSandbox sandbox = LuaSandbox.builder(ROOMY).show(String::valueOf).print(printed::add).build();
        LuaSandbox.Outcome o = run(sandbox, """
                print({z = 3, x = 1, y = 2}, {1, 2})
                print(setmetatable({}, {__tostring = function() return "mine" end}))
                """);
        assertTrue(o.finished(), String.valueOf(o));
        assertEquals("{x=1, y=2, z=3}\t[1, 2]", printed.get(0));
        assertEquals("mine", printed.get(1));
    }

    /** 数照 Lua 5.2 的十四位有效数字写,但从不写成科学计数法:远处的坐标、很小的数读出来就是它们自己。 */
    @Test
    void numbersPrintInFullWithoutAnExponent() throws InterruptedException {
        LuaSandbox.Outcome o = run("print(-10537096.5, 0.1, 0.00001, 123456789012.25, 1/3, 2^60, -0.5 + 0.25)");
        assertTrue(o.finished(), String.valueOf(o));
        assertEquals(List.of("-10537096.5\t0.1\t0.00001\t123456789012.25\t0.33333333333333\t1152921504606846976"
                + "\t-0.25"), printed);
        assertEquals("-10537096.5", LuaSandbox.number(-10537096.5));
    }

    @Test
    void namesLuaCannotSpellAreRefusedAtRegistration() {
        assertThrows(IllegalArgumentException.class,
                () -> LuaSandbox.builder(ROOMY).function("move", "goto", args -> null));
        assertThrows(IllegalArgumentException.class,
                () -> LuaSandbox.builder(ROOMY).function("string", "x", args -> null));
        assertTrue(LuaSandbox.KEYWORDS.contains("goto"));
    }

    @Test
    void aScriptReturnsItsFirstValue() throws InterruptedException {
        assertEquals(Map.of("dug", 3L, "left", List.of("ores/g2")),
                run("return {dug = 3, left = {\"ores/g2\"}}, \"ignored\"").value());
        assertNull(run("local x = 1").value());
        assertTrue(String.valueOf(run("return print").value()).startsWith("function"));
        assertNull(run("error(\"no\")").value());
    }

    /** 带方法的值:宿主交回的 Instance 换成 Lua 值时,模块里与类同名的表是它的元表;模块不在就是普通的表;交回宿主时元表不算数据。 */
    @Test
    void anInstanceTakesItsClassFromItsModule() throws InterruptedException {
        Shelf shelf = new Shelf().with("geo", """
                local M = {}
                M.Pos = {}
                M.Pos.__index = M.Pos
                function M.Pos:twice() return self.x * 2 end
                function M.Pos.__eq(a, b) return a.x == b.x end
                function M.pos(x) return setmetatable({x = x}, M.Pos) end
                return M
                """);
        List<Object> handed = Collections.synchronizedList(new ArrayList<>());
        LuaSandbox sandbox = LuaSandbox.builder(ROOMY).print(printed::add).modules(shelf)
                .function("api", "where", args -> List.of(new LuaSandbox.Instance("Pos", "geo", Map.of("x", 3L))))
                .function("api", "plain", args -> new LuaSandbox.Instance("Pos", "nowhere", Map.of("x", 3L)))
                .function("api", "take", args -> {
                    handed.addAll(args);
                    return null;
                })
                .build();
        LuaSandbox.Outcome o = run(sandbox, """
                local p = api.where()[1]
                print(p:twice(), getmetatable(p) == geo.Pos, p == geo.pos(3), getmetatable(api.plain()) == nil)
                api.take(p)
                """);
        assertTrue(o.finished(), String.valueOf(o));
        assertEquals(List.of("6\ttrue\ttrue\ttrue"), printed);
        assertEquals(List.of(Map.of("x", 3L)), handed, "交回宿主的是数据,没有元表");
    }

    /** 只读不跑(不给模块、只给类的来源)时,带方法的值从各自的模块另装一份取元表,方法照样调得通。 */
    @Test
    void readingWithoutModulesStillGivesAnInstanceItsMethods() throws InterruptedException {
        Shelf shelf = new Shelf().with("geo", """
                local M = {}
                M.Pos = {}
                M.Pos.__index = M.Pos
                function M.Pos:twice() return self.x * 2 end
                return M
                """);
        LuaSandbox sandbox = LuaSandbox.builder(ROOMY).print(printed::add).classes(shelf)
                .function("api", "where", args -> new LuaSandbox.Instance("Pos", "geo", Map.of("x", 4L)))
                .build();
        LuaSandbox.Outcome o = run(sandbox, "print(api.where():twice())");
        assertTrue(o.finished(), String.valueOf(o));
        assertEquals(List.of("8"), printed);
    }

    /** 模块来源:名字到正文,每次问都读这张表此刻的样子;记下被问了哪些。 */
    private static final class Shelf implements LuaSandbox.ModuleSource {
        final Map<String, String> modules = new java.util.concurrent.ConcurrentHashMap<>();
        final List<String> asked = Collections.synchronizedList(new ArrayList<>());

        Shelf with(String name, String code) {
            modules.put(name, code);
            return this;
        }

        @Override
        public String code(String name) {
            asked.add(name);
            return modules.get(name);
        }

        @Override
        public List<String> names() {
            return modules.keySet().stream().sorted().toList();
        }
    }

    @Test
    void aModuleIsUsedByNameLoadedWhenFirstUsedAndItsCallsCountAgainstTheScriptsOwnLine()
            throws InterruptedException {
        List<Integer> lines = Collections.synchronizedList(new ArrayList<>());
        Shelf shelf = new Shelf().with("walk", """
                local M = {}
                function M.there(name)
                  route.plan(name)
                  route.plan(name)
                  return "walked " .. name
                end
                return M
                """).with("route", """
                local M = {}
                function M.twice(name) return walk.there(name) end
                return M
                """).with("unused", "this does not compile");
        LuaSandbox sandbox = LuaSandbox.builder(ROOMY)
                .function("route", "plan", args -> {
                    lines.add(LuaSandbox.currentLine());
                    return null;
                })
                .modules(shelf)
                .print(printed::add).build();
        LuaSandbox.Running running = sandbox.start("t", """
                local a = 1

                print(route.twice("home"))
                local b = walk.there("mine")
                error("stop here")
                """, List.of(), o -> { });
        LuaSandbox.Outcome o = running.await();
        assertEquals(LuaSandbox.Ending.ERROR, o.ending());
        assertEquals(List.of(3, 3, 4, 4), lines);
        assertEquals(List.of("walked home"), printed);
        assertEquals(5, o.line());
        assertEquals(List.of("route", "walk"), running.modules(), "route 的模块加进 route 表;没用到的模块不读");
        assertFalse(shelf.asked.contains("unused"));
    }

    @Test
    void eachRunReadsTheModuleAsItIsNow() throws InterruptedException {
        Shelf shelf = new Shelf().with("greet", "return {hi = function() return 'one' end}");
        LuaSandbox sandbox = LuaSandbox.builder(ROOMY).modules(shelf).print(printed::add).build();
        assertTrue(run(sandbox, "print(greet.hi())").finished());
        shelf.with("greet", "return {hi = function() return 'two' end}");
        assertTrue(run(sandbox, "print(greet.hi())").finished());
        assertEquals(List.of("one", "two"), printed);
    }

    @Test
    void aBrokenModuleFailsOnlyWhereItIsUsedAndSaysWhere() throws InterruptedException {
        Shelf shelf = new Shelf().with("lib", """
                local M = {}
                function M.boom()
                  local t = nil
                  return t.x
                end
                return M
                """).with("broken", "local = 1").with("bare", "local x = 1");
        LuaSandbox sandbox = LuaSandbox.builder(ROOMY).modules(shelf)
                .unknown((name, present) -> new LuaSandbox.ScriptError("no module named " + name + "; there are: "
                        + String.join(", ", present)))
                .build();
        assertTrue(run(sandbox, "local x = 1 + 1").finished(), "不用它们的程序照常跑");
        LuaSandbox.Outcome boom = run(sandbox, "local a = 1\nlib.boom()\n");
        assertEquals(LuaSandbox.Ending.ERROR, boom.ending());
        assertEquals(2, boom.line());
        assertTrue(boom.message().startsWith("lib:4:"), boom.message());
        LuaSandbox.Outcome broken = run(sandbox, "local ok, err = pcall(function() return broken.x end)\n"
                + "assert(not ok and err:find('module broken does not compile'), err)\nreturn bare.x");
        assertEquals(LuaSandbox.Ending.ERROR, broken.ending());
        assertTrue(broken.message().contains("module bare returned nil, not a table"), broken.message());
        LuaSandbox.Outcome typo = run(sandbox, "return lbi.boom()");
        assertTrue(typo.message().contains("no module named lbi; there are: bare, broken, lib"), typo.message());
    }

    @Test
    void aModuleOnAPathIsUsedUnderItsPathAndThePathIsFixed() throws InterruptedException {
        Shelf shelf = new Shelf().with("my.lumber", "return {chop = function(n) return 'chopped ' .. n end}");
        LuaSandbox sandbox = LuaSandbox.builder(ROOMY).modules(shelf)
                .unknown((name, present) -> new LuaSandbox.ScriptError("no module named " + name))
                .missing((table, key, present) -> new LuaSandbox.ScriptError("no " + table + "." + key + "; there are: "
                        + String.join(", ", present)))
                .print(printed::add).build();
        assertTrue(run(sandbox, "print(my.lumber.chop(3))").finished());
        assertEquals(List.of("chopped 3"), printed);
        assertTrue(run(sandbox, "return my.lumbr").message().contains("no my.lumbr; there are: lumber"));
        assertEquals(LuaSandbox.Ending.ERROR, run(sandbox, "my = {}").ending(), "外层的表定死");
        assertTrue(run(sandbox, "return lumber").message().contains("no module named lumber"),
                "路径上的模块不占顶层名字");
    }

    @Test
    void hostFunctionsLiveOnTablePathsAndAModuleOnTheSamePathAddsToThem() throws InterruptedException {
        Shelf shelf = new Shelf().with("numen.work", "return {twice = function() return numen.work.dig() * 2 end}")
                .with("numen.shape", "return {box = function() return 'a box' end}");
        LuaSandbox sandbox = LuaSandbox.builder(ROOMY)
                .function("numen.work", "dig", args -> 2L)
                .function("numen.scan", "blocks", args -> 3L)
                .modules(shelf)
                .missing((table, key, present) -> new LuaSandbox.ScriptError("no " + table + "." + key + "; there are: "
                        + String.join(", ", present)))
                .redefined((table, key) -> new LuaSandbox.ScriptError((table == null ? "" : table + ".") + key
                        + " is fixed"))
                .build();
        assertEquals(4L, run(sandbox, "return numen.work.twice()").value());
        assertEquals("a box", run(sandbox, "return numen.shape.box()").value());
        assertEquals(3L, run(sandbox, "return numen.scan.blocks()").value());
        assertTrue(run(sandbox, "return numen.wrok").message().contains("no numen.wrok; there are: scan, shape, work"));
        assertTrue(run(sandbox, "return numen.work.dgi").message().contains("no numen.work.dgi; there are: dig"));
        assertTrue(run(sandbox, "numen.work = {}").message().contains("numen.work is fixed"));
        assertTrue(run(sandbox, "numen = {}").message().contains("numen is fixed"));
        assertTrue(run(sandbox, "function numen.work.dig() end").message().contains("numen.work.dig is fixed"));
    }

    @Test
    void aModuleCannotReplaceTheHostsFunctionsOfItsGroup() throws InterruptedException {
        Shelf shelf = new Shelf().with("move", "return {go = function() return 'mine' end}");
        LuaSandbox sandbox = LuaSandbox.builder(ROOMY).function("move", "go", args -> "went").modules(shelf)
                .preload("move").build();
        LuaSandbox.Outcome o = run(sandbox, "return 1");
        assertEquals(LuaSandbox.Ending.ERROR, o.ending());
        assertTrue(o.message().contains("move.go is a host function"), o.message());
    }

    @Test
    void theHostsNamesCannotBeReplacedOrShadowedButItsTablesTakeNewOnes() throws InterruptedException {
        LuaSandbox sandbox = LuaSandbox.builder(ROOMY)
                .function("move", "go", args -> "went")
                .function("raise", args -> null)
                .redefined((table, key) -> new LuaSandbox.ScriptError(Map.of("kind", "fixed",
                        "message", (table == null ? "" : table + ".") + key)))
                .modules(new Shelf().with("move", """
                        local M = {}
                        function M.twice() return move.go() .. move.go() end
                        return M
                        """))
                .build();
        LuaSandbox.Outcome o = run(sandbox, """
                local function refused(f)
                  local ok, err = pcall(f)
                  assert(not ok and err.kind == "fixed", tostring(err))
                  return err.message
                end
                assert(refused(function() function move.go() return "mine" end end) == "move.go")
                assert(refused(function() move.go = nil end) == "move.go")
                assert(refused(function() rawset(move, "go", print) end) == "move.go")
                assert(refused(function() move = {} end) == "move")
                assert(refused(function() rawset(_G, "move", {}) end) == "move")
                assert(refused(function() raise = print end) == "raise")
                move.extra = function() return "extra" end
                assert(move.go() == "went" and move.twice() == "wentwent" and move.extra() == "extra")
                """);
        assertTrue(o.finished(), String.valueOf(o));
    }

    /** 中文的字符串字面量原样进出:print 的、error 的一句话、错误值表里的、return 的,都不截成乱码。 */
    @Test
    void chineseStringLiteralsComeOutWhole() throws InterruptedException {
        assertTrue(run("print(\"砍了 3 棵\", #\"砍\")").finished());
        assertEquals(List.of("砍了 3 棵\t3"), printed, "一个汉字是三个 UTF-8 字节");
        LuaSandbox.Outcome said = run("local x = 1\nerror(\"手边没有合成台\", 0)");
        assertEquals("手边没有合成台", said.message());
        LuaSandbox.Outcome table = run("error({kind = \"failed\", message = \"箱子里没有钻石\"})");
        assertEquals("箱子里没有钻石", table.error().get("message"));
        assertEquals("铁镐", run("return \"铁\" .. \"镐\"").value());
    }
}
