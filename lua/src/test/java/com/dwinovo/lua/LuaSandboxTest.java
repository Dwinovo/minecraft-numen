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

    @Test
    void namesLuaCannotSpellAreRefusedAtRegistration() {
        assertThrows(IllegalArgumentException.class,
                () -> LuaSandbox.builder(ROOMY).function("move", "goto", args -> null));
        assertThrows(IllegalArgumentException.class,
                () -> LuaSandbox.builder(ROOMY).function("string", "x", args -> null));
        assertTrue(LuaSandbox.KEYWORDS.contains("goto"));
    }
}
