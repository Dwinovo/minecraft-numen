package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.script.Invocation;
import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptRun;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.lua;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脚本这个前端:每个动作一个函数 {@code 组.动作};按顺序的对象给位置参数、最后一个位置参数收下余下的全部,一张全是数的表是一格这样
 * 几个词的一个值,一串值也可以写成一张表,选项表按名字给标志。换成参数名到值的 JSON,当场按参数类型读一遍——读不成就在调用处报
 * error/usage/hint,不派出去;读得成的交给动作,处理函数拿到的是同一种类型化的值。不拼命令行。
 */
class LuaFrontendTest {

    static final Param<List<String>> ITEMS = Param.required("items", ArgType.list(ArgType.word()), "Which items.");
    static final Param<String> FROM = Param.optional("from", ArgType.word(), "Where to take them from.")
            .whenOmitted("from the ground");
    static final Param<Integer> COUNT = Param.optional("count", ArgType.integer(1, 64), "How many.")
            .whenOmitted("all of them");
    static final Param<net.minecraft.core.BlockPos> CELL = Param.required("cell", ArgType.cell(), "Which cell.");
    static final Param<List<net.minecraft.core.BlockPos>> CELLS = Param.optional("cells",
            ArgType.list(ArgType.cell()), "Which cells.").whenOmitted("none");
    static final Param<String> BLOCK = Param.optional("block", ArgType.word(), "Which block.")
            .whenOmitted("the one in your hand");
    static final Param<String> TEXT = Param.required("text", ArgType.text(), "What to say.");
    static final Param<String> NAME = Param.required("name", ArgType.word(), "The thing.");

    @BeforeAll
    static void register() {
        door().registerCommands("gt_script", "A group the script bindings read.", g -> {
            g.server("take", "Take some items.", (src, args) -> src.reply(TaskResult.ok("took " + args.get(ITEMS)
                            + " from " + args.get(FROM) + " x" + args.get(COUNT)).toJson()), ITEMS, FROM, COUNT)
                    .example("gt_script.take(\"apple\", \"pear\", {from = \"chest\"})");
            g.server("at", "Put a block at a cell.", (src, args) -> src.reply(TaskResult.ok("put " + args.get(BLOCK)
                            + " at " + args.get(CELL).toShortString() + " and " + args.get(CELLS)).toJson()),
                            CELL, BLOCK, CELLS)
                    .example("gt_script.at({1, 2, 3}, {block = \"stone\"})");
            g.server("say", "Say something.", (src, args) -> src.reply(TaskResult.ok("said " + args.get(TEXT))
                    .toJson()), TEXT)
                    .example("gt_script.say(\"hello there\")");
            g.server("has", "Whether a thing exists.", (src, args) -> src.reply(TaskResult.ok("yes",
                    Map.of("has", !args.get(NAME).equals("nothing"))).toJson()), NAME)
                    .example("gt_script.has(\"apple\")")
                    .returns("has");
            g.server("goto", "Go to a thing.", (src, args) -> src.reply(TaskResult.ok("went").toJson()), NAME)
                    .example("gt_script.goto_(\"apple\")");
        });
    }

    private static ScriptRun.Call call(String verb, List<Object> args, Map<String, Object> options) {
        return new ScriptRun.Call(1, "gt_script", verb, args, options);
    }

    private static String json(Invocation invocation) {
        return invocation.args().toString();
    }

    @Test
    void theLastPositionalTakesEveryObjectLeftAndTheTableIsTheOptions() {
        Invocation take = NumenCli.invocation(call("take", List.of("apple", "pear"), Map.of("from", "chest",
                "count", 3L)));
        assertEquals("gt_script.take", take.function());
        assertEquals(JsonParser.parseString("{\"items\":[\"apple\",\"pear\"],\"from\":\"chest\",\"count\":3}"),
                take.args());
        assertEquals("{\"items\":[\"apple\"]}", json(NumenCli.invocation(call("take", List.of("apple"), Map.of()))));
    }

    @Test
    void aTableOfNumbersIsOneValueOfSeveralWordsAndAListCanBeOneTable() {
        assertEquals("{\"cell\":\"1 2 3\",\"block\":\"stone\"}",
                json(NumenCli.invocation(call("at", List.of(List.of(1L, 2L, 3L)), Map.of("block", "stone")))));
        assertEquals("{\"cell\":\"1 2 3\"}", json(NumenCli.invocation(call("at", List.of(1L, 2L, 3L), Map.of()))));
        assertEquals("{\"items\":[\"apple\",\"pear\"]}",
                json(NumenCli.invocation(call("take", List.of(List.of("apple", "pear")), Map.of()))),
                "一串值写成一张表就是这一串");
        assertEquals("{\"cell\":\"1 2 3\",\"cells\":[\"4 5 6\"]}",
                json(NumenCli.invocation(call("at", List.of(List.of(1L, 2L, 3L)),
                        Map.of("cells", List.of(4L, 5L, 6L))))),
                "一串格子收到一张全是数的表:那是一格");
    }

    @Test
    void theRestTakesEveryObjectLeft() {
        assertEquals("{\"text\":\"hello there 3\"}",
                json(NumenCli.invocation(call("say", List.of("hello", "there", 3L), Map.of()))));
    }

    @Test
    void mistakesSayWhatIsWrongWithTheUsageAndAreNotSent() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> NumenCli.invocation(call("take", List.of(), Map.of())));
        assertTrue(missing.getMessage().startsWith("error: missing required argument: items\n"
                + "usage: gt_script.take(items..., {from=…, count=…})\n"
                + "  e.g. gt_script.take(\"apple\", \"pear\", {from = \"chest\"})\n"
                + "hint: `api.help(\"gt_script.take\")` explains every argument."), missing.getMessage());

        IllegalArgumentException option = assertThrows(IllegalArgumentException.class,
                () -> NumenCli.invocation(call("take", List.of("apple"), Map.of("form", "chest"))));
        assertTrue(option.getMessage().startsWith("error: unknown argument 'form'"), option.getMessage());

        IllegalArgumentException cell = assertThrows(IllegalArgumentException.class,
                () -> NumenCli.invocation(call("at", List.of("1 2"), Map.of())));
        assertTrue(cell.getMessage().startsWith("error: argument 'cell': expected a cell"), cell.getMessage());

        IllegalArgumentException none = assertThrows(IllegalArgumentException.class,
                () -> NumenCli.invocation(new ScriptRun.Call(1, "gt_script", "fly", List.of(), Map.of())));
        assertTrue(none.getMessage().startsWith("error: there is no API function gt_script.fly"), none.getMessage());
    }

    @Test
    void theCatalogHasEveryActionAndWhatItReturns() {
        ScriptCatalog catalog = NumenCli.scriptCatalog();
        assertNull(catalog.verb("gt_script", "take").returns());
        assertEquals("has", catalog.verb("gt_script", "has").returns());
        assertNull(catalog.verb("gt_script", "fly"));
    }

    @Test
    void aScriptCallReachesTheActionWithTypedValues() {
        CliFixture.Outcome run = lua("""
                local took = gt_script.take({"apple", "pear"}, {count = 2})
                local put = gt_script.at(1, 2, 3, {block = "stone", cells = {{4, 5, 6}, {7, 8, 9}}})
                local yes, no = gt_script.has("apple"), gt_script.has("nothing")
                return {took, put, yes, no, gt_script.goto_("apple")}
                """);
        assertTrue(run.success(), run.message());
        assertEquals(JsonParser.parseString("[\"took [apple, pear] from null x2\", \"put stone at 1, 2, 3 and "
                + "[BlockPos{x=4, y=5, z=6}, BlockPos{x=7, y=8, z=9}]\", true, false, \"went\"]"),
                run.json().getAsJsonObject("data").get("returned"));
    }

    @Test
    void aWrongCallFailsAtItsLineWithTheUsageAndNothingIsSent() {
        CliFixture.Outcome run = lua("""
                local ok, err = pcall(gt_script.take, "apple", {count = "lots"})
                print(err)
                gt_script.at("1 2")
                """);
        assertFalse(run.success());
        assertTrue(run.message().startsWith("The script stopped at line 3 after 0 calls: lua:3: gt_script.at: error: "
                + "argument 'cell': expected a cell"), run.message());
        assertTrue(run.message().contains("usage: gt_script.at(cell, {block=…, cells=…})"), run.message());
        assertTrue(run.message().contains("line 1 gt_script.take: failed — error: argument 'count'"), run.message());
    }

    @Test
    void theHelpIsWrittenTheWayAScriptCallsIt() {
        CliFixture.Outcome has = lua("return api.help(\"gt_script.has\")");
        String help = has.json().getAsJsonObject("data").get("returned").getAsString();
        assertTrue(help.startsWith("gt_script.has(name)\n  Whether a thing exists."), help);
        assertTrue(help.contains("Returns data.has, whether it succeeds or not."), help);
        String renamed = lua("return api.help(\"gt_script.goto_\")").json().getAsJsonObject("data").get("returned")
                .getAsString();
        assertTrue(renamed.startsWith("gt_script.goto_(name)"), renamed);
        assertFalse(lua("return api.help(\"gt_script.nope\")").success());
    }
}
