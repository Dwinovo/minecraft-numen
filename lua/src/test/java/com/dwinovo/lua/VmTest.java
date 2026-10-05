package com.dwinovo.lua;

import com.dwinovo.lua.vm.Globals;
import com.dwinovo.lua.vm.LuaValue;
import com.dwinovo.lua.vm.compiler.LuaC;
import com.dwinovo.lua.vm.lib.BaseLib;
import com.dwinovo.lua.vm.lib.JseMathLib;
import com.dwinovo.lua.vm.lib.StringLib;
import com.dwinovo.lua.vm.lib.TableLib;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 虚拟机自己是一份完整的 Lua 5.2:基本库的 load、collectgarbage、print 都在,string 库每装一次一张表、改得动;挂点默认什么都不限。
 * 不装哪些、锁住哪些是沙箱的事(见 {@link LuaSandboxTest})。
 */
class VmTest {

    private static Globals globals() {
        Globals g = new Globals();
        g.load(new BaseLib());
        g.load(new TableLib());
        g.load(new StringLib());
        g.load(new JseMathLib());
        LuaC.install(g);
        return g;
    }

    @Test
    void theBaseLibraryIsWhole() {
        Globals g = globals();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        g.STDOUT = new PrintStream(out, true, StandardCharsets.UTF_8);
        LuaValue result = g.load("""
                local f = load("return 1 + 1")
                assert(type(collectgarbage("count")) == "number")
                print("hi", f())
                return f()
                """, "=vm").call();
        assertEquals(2, result.toint());
        assertEquals("hi\t2\n", out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void eachGlobalsGetsItsOwnStringTable() {
        Globals g = globals();
        g.load("string.shout = function(s) return s:upper() end", "=vm").call();
        assertTrue(g.get("string").get("shout").isfunction());
        assertTrue(globals().get("string").get("shout").isnil(), "string 库表每装一次一张");
    }
}
