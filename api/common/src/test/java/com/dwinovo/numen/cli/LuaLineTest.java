package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.lua.LuaCatalog;
import com.dwinovo.numen.agent.lua.LuaRun;
import com.dwinovo.numen.task.TaskResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.dwinovo.numen.cli.CliFixture.door;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脚本里的函数由命令登记处生成:每个动作一个 {@code 组.动作},按顺序的对象是必填参数、选项表是可选参数,写成和她亲手写的一样的
 * 一行;声明了返回项的动作在目录里带着它。
 */
class LuaLineTest {

    static final Param<Integer> COUNT = Param.required("count", ArgType.integer(1, 64), "How many.");
    static final Param<String> ITEM = Param.required("item", ArgType.word(), "Which item.");
    static final Param<String> FROM = Param.optional("from", ArgType.word(), "Where to take them from.");
    static final Param<List<String>> TAGS = Param.optional("tags", ArgType.list(ArgType.word()), "Tags.");
    static final Param<String> TEXT = Param.required("text", ArgType.text(), "What to say.");
    static final Param<String> NAME = Param.required("name", ArgType.word(), "The thing.");

    @BeforeAll
    static void register() {
        door().registerCommands("gt_lua", "A group the Lua bindings read.", g -> {
            g.server("take", "Take some items.", (src, args) -> src.reply(TaskResult.ok("took").toJson()),
                    COUNT, ITEM, FROM, TAGS).example("gt_lua take 3 apple --from chest");
            g.server("say", "Say something.", (src, args) -> src.reply(TaskResult.ok("said").toJson()), TEXT)
                    .example("gt_lua say hello there");
            g.server("has", "Whether a thing exists.", (src, args) -> src.reply(TaskResult.ok("yes").toJson()), NAME)
                    .example("gt_lua has apple")
                    .returns("has");
        });
    }

    private static LuaRun.Call call(String verb, List<Object> args, Map<String, Object> options) {
        return new LuaRun.Call(1, "gt_lua", verb, args, options);
    }

    @Test
    void objectsAreTheRequiredArgumentsAndTheTableTheFlags() {
        assertEquals("gt_lua take 3 apple --from chest --tags red ripe",
                NumenCli.luaLine(call("take", List.of(3L, "apple"),
                        Map.of("from", "chest", "tags", List.of("red", "ripe")))));
        assertEquals("gt_lua take 3 apple", NumenCli.luaLine(call("take", List.of(3L, "apple"), Map.of())));
    }

    @Test
    void theRestOfTheLineTakesEveryObjectLeft() {
        assertEquals("gt_lua say hello there 3", NumenCli.luaLine(call("say", List.of("hello", "there", 3L), Map.of())));
    }

    @Test
    void mistakesSayWhatIsWrongAndGiveTheUsage() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> NumenCli.luaLine(call("take", List.of(3L), Map.of())));
        assertTrue(missing.getMessage().contains("missing required argument: item"), missing.getMessage());
        assertTrue(missing.getMessage().endsWith("usage: gt_lua take <count> <item> [--from <word>] [--tags <word...>]"),
                missing.getMessage());

        IllegalArgumentException extra = assertThrows(IllegalArgumentException.class,
                () -> NumenCli.luaLine(call("take", List.of(3L, "apple", "pear"), Map.of())));
        assertTrue(extra.getMessage().startsWith("takes 2 objects, got 3"), extra.getMessage());

        IllegalArgumentException option = assertThrows(IllegalArgumentException.class,
                () -> NumenCli.luaLine(call("take", List.of(3L, "apple"), Map.of("form", "chest"))));
        assertTrue(option.getMessage().contains("unknown argument 'form'"), option.getMessage());

        IllegalArgumentException none = assertThrows(IllegalArgumentException.class,
                () -> NumenCli.luaLine(new LuaRun.Call(1, "gt_lua", "fly", List.of(), Map.of())));
        assertEquals("there is no command gt_lua fly", none.getMessage());
    }

    @Test
    void theCatalogHasEveryActionAndWhatItReturns() {
        LuaCatalog catalog = NumenCli.luaCatalog();
        assertNull(catalog.verb("gt_lua", "take").returns());
        assertEquals("has", catalog.verb("gt_lua", "has").returns());
        assertNull(catalog.verb("gt_lua", "fly"));
    }

    @Test
    void anActionThatReturnsAValueSaysSoInItsHelp() {
        CliFixture.Outcome help = CliFixture.onServer("gt_lua has --help");
        assertTrue(help.message().contains("In Lua: gt_lua.has(...) returns data.has directly, and raises an error "
                + "when it fails."), help.message());
    }
}
