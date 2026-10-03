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
                    .returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING)
                    .example("gt_script.take(\"apple\", \"pear\", {from = \"chest\"})");
            g.server("at", "Put a block at a cell.", (src, args) -> src.reply(TaskResult.ok("put " + args.get(BLOCK)
                            + " at " + args.get(CELL).toShortString() + " and " + args.get(CELLS)).toJson()),
                            CELL, BLOCK, CELLS)
                    .returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING)
                    .example("gt_script.at({x = 1, y = 2, z = 3}, {block = \"stone\"})");
            g.server("say", "Say something.", (src, args) -> src.reply(TaskResult.ok("said " + args.get(TEXT))
                    .toJson()), TEXT)
                    .returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING)
                    .example("gt_script.say(\"hello there\")");
            g.server("has", "Whether a thing exists.", (src, args) -> src.reply(TaskResult.ok("yes",
                    Map.of("has", !args.get(NAME).equals("nothing"))).toJson()), NAME)
                    .example("gt_script.has(\"apple\")")
                    .returns("has", com.dwinovo.numen.agent.script.ScriptType.BOOLEAN);
            g.server("goto", "Go to a thing.", (src, args) -> src.reply(TaskResult.ok("went").toJson()), NAME)
                    .returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING)
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

    /** 脚本的值原样换成 JSON:一个 Pos 是一个对象,一串值写成一张列表就是这一串,一串格子是一串 Pos。 */
    @Test
    void aScriptsValuesAreHandedOnAsTheyAre() {
        Map<String, Object> pos = new java.util.LinkedHashMap<>();
        pos.put("x", 1L);
        pos.put("y", 2L);
        pos.put("z", 3L);
        assertEquals("{\"cell\":{\"x\":1,\"y\":2,\"z\":3},\"block\":\"stone\"}",
                json(NumenCli.invocation(call("at", List.of(pos), Map.of("block", "stone")))));
        assertEquals("{\"items\":[\"apple\",\"pear\"]}",
                json(NumenCli.invocation(call("take", List.of(List.of("apple", "pear")), Map.of()))),
                "一串值写成一张表就是这一串");
        assertEquals("{\"cell\":{\"x\":1,\"y\":2,\"z\":3},\"cells\":[{\"x\":1,\"y\":2,\"z\":3}]}",
                json(NumenCli.invocation(call("at", List.of(pos), Map.of("cells", pos)))),
                "一串格子收到一个 Pos:那是一格");
    }

    /** 写在最后的那张名字表:键全是选项名才是选项表;一个 Pos、一个方块是一个对象。 */
    @Test
    void theLastTableIsTheOptionsOnlyWhenItsKeysAreOptionNames() {
        CliFixture.Outcome run = lua("""
                gt_script.at({x = 1, y = 2, z = 3})
                gt_script.at({name = "stone", pos = {x = 4, y = 5, z = 6}}, {block = "dirt"})
                """);
        assertTrue(run.success(), run.message());
        assertEquals("put null at 1, 2, 3 and null", run.call(0).get("message").getAsString());
        assertEquals("put dirt at 4, 5, 6 and null", run.call(1).get("message").getAsString());
    }

    @Test
    void theRestTakesEveryObjectLeft() {
        assertEquals("{\"text\":\"hello there 3\"}",
                json(NumenCli.invocation(call("say", List.of("hello", "there", 3L), Map.of()))));
    }

    @Test
    void mistakesSayWhatIsWrongWithTheUsageAndAreNotSent() {
        com.dwinovo.numen.agent.script.ApiError missing = assertThrows(com.dwinovo.numen.agent.script.ApiError.class,
                () -> NumenCli.invocation(call("take", List.of(), Map.of())));
        assertEquals(com.dwinovo.numen.agent.script.ErrorKind.BAD_ARGUMENT, missing.kind());
        assertEquals("argument 'items' is missing (or nil)\n"
                + "usage: gt_script.take(items..., {from=…, count=…})\n"
                + "  e.g. gt_script.take(\"apple\", \"pear\", {from = \"chest\"})", missing.getMessage());
        assertEquals("print(api.help(\"gt_script.take\"))", missing.hint());

        com.dwinovo.numen.agent.script.ApiError option = assertThrows(com.dwinovo.numen.agent.script.ApiError.class,
                () -> NumenCli.invocation(call("take", List.of("apple"), Map.of("form", "chest"))));
        assertTrue(option.getMessage().startsWith("unknown argument 'form'"), option.getMessage());

        com.dwinovo.numen.agent.script.ApiError cell = assertThrows(com.dwinovo.numen.agent.script.ApiError.class,
                () -> NumenCli.invocation(call("at", List.of("1 2 3"), Map.of())));
        assertTrue(cell.getMessage().startsWith("argument 'cell': a cell is a Pos with named fields; got \"1 2 3\""),
                cell.getMessage());
        assertEquals("gt_script.at({x = 1, y = 2, z = 3})", cell.hint(), "照新写法改好的那一行");

        com.dwinovo.numen.agent.script.ApiError none = assertThrows(com.dwinovo.numen.agent.script.ApiError.class,
                () -> NumenCli.invocation(new ScriptRun.Call(1, "gt_script", "fly", List.of(), Map.of())));
        assertEquals(com.dwinovo.numen.agent.script.ErrorKind.NO_FUNCTION, none.kind());
        assertTrue(none.getMessage().startsWith("there is no API function gt_script.fly"), none.getMessage());
    }

    @Test
    void theCatalogHasEveryActionAndWhatItReturns() {
        ScriptCatalog catalog = NumenCli.scriptCatalog(com.dwinovo.numen.script.Modules.builtin());
        assertNull(catalog.verb("gt_script", "take").returns());
        assertEquals("has", catalog.verb("gt_script", "has").returns());
        assertEquals(false, catalog.verb("gt_script", "has").sample(), "只读不跑时它返回的样子照声明的类型造");
        assertEquals(java.util.Set.of("from", "count"), catalog.verb("gt_script", "take").options());
        assertNull(catalog.verb("gt_script", "fly"));
    }

    @Test
    void aScriptCallReachesTheActionWithTypedValues() {
        CliFixture.Outcome run = lua("""
                gt_script.take({"apple", "pear"}, {count = 2})
                gt_script.at({x = 1, y = 2, z = 3}, {block = "stone", cells = {{x = 4, y = 5, z = 6}, {x = 7, y = 8, z = 9}}})
                local yes, no = gt_script.has("apple"), gt_script.has("nothing")
                return {yes, no, gt_script.goto_("apple")}
                """);
        assertTrue(run.success(), run.message());
        assertEquals("took [apple, pear] from null x2", run.call(0).get("message").getAsString());
        assertEquals("put stone at 1, 2, 3 and [BlockPos{x=4, y=5, z=6}, BlockPos{x=7, y=8, z=9}]",
                run.call(1).get("message").getAsString());
        assertEquals(JsonParser.parseString("[true, false]"), run.json().getAsJsonObject("data").get("returned"),
                "声明了返回项的返回那一项;没有数据的返回 nil");
    }

    @Test
    void aWrongCallFailsAtItsLineWithTheUsageAndNothingIsSent() {
        CliFixture.Outcome run = lua("""
                local ok, err = pcall(gt_script.take, "apple", {count = "lots"})
                print(err)
                gt_script.at("1 2")
                """);
        assertFalse(run.success());
        assertTrue(run.message().startsWith("The script stopped at line 3 after 0 calls: gt_script.at: bad_argument — "
                + "argument 'cell': expected a Pos {x = …, y = …, z = …} or anything with a pos (a Block, an Entity, "
                + "an Item); got \"1 2\""), run.message());
        assertTrue(run.message().contains("usage: gt_script.at(cell, {block=…, cells=…})"), run.message());
        assertTrue(run.message().contains("line 1 gt_script.take: bad_argument — argument 'count'"), run.message());
        assertEquals("bad_argument", run.json().getAsJsonObject("data").getAsJsonObject("error").get("kind")
                .getAsString(), "回执数据里的错误值带着种类");
    }

    @Test
    void theHelpIsWrittenTheWayAScriptCallsIt() {
        String help = CliFixture.help("gt_script.has");
        assertTrue(help.startsWith("---Whether a thing exists.\n---@param name string The thing.\n"
                + "---@return boolean\nfunction gt_script.has(name) end"), help);
        String renamed = CliFixture.help("gt_script.goto_");
        assertTrue(renamed.contains("function gt_script.goto_(name) end"), renamed);
        CliFixture.Outcome nope = lua("return api.help(\"gt_script.nope\")");
        assertFalse(nope.success());
        assertTrue(nope.message().contains("api.help: not_found — there is no function, group or module named gt_script.nope"),
                nope.message());
    }
}
