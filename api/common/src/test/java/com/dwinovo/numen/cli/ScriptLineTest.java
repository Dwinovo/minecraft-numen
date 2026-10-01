package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptRun;
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
 * 脚本里的函数由命令登记处生成:每个动作一个 {@code 组.动作};按顺序的对象给位置参数、最后一个位置参数收下余下的全部,列表是
 * 命令行上几个词的一个值,选项表是标志;写成和她亲手写的一样的一行,对象只经 {@link ArgType} 一处读。声明了返回项的动作在目录里
 * 带着它;名字被改写或直接返回值的,帮助里说。
 */
class ScriptLineTest {

    static final Param<List<String>> ITEMS = Param.required("items", ArgType.list(ArgType.word()), "Which items.");
    static final Param<String> FROM = Param.optional("from", ArgType.word(), "Where to take them from.")
            .whenOmitted("from the ground");
    static final Param<Integer> COUNT = Param.optional("count", ArgType.integer(1, 64), "How many.")
            .whenOmitted("all of them");
    static final Param<net.minecraft.core.BlockPos> CELL = Param.required("cell", ArgType.cell(), "Which cell.");
    static final Param<String> BLOCK = Param.optional("block", ArgType.word(), "Which block.")
            .whenOmitted("the one in your hand");
    static final Param<String> TEXT = Param.required("text", ArgType.text(), "What to say.");
    static final Param<String> NAME = Param.required("name", ArgType.word(), "The thing.");

    @BeforeAll
    static void register() {
        door().registerCommands("gt_script", "A group the script bindings read.", g -> {
            g.server("take", "Take some items.", (src, args) -> src.reply(TaskResult.ok("took").toJson()),
                    ITEMS, FROM, COUNT).example("gt_script take apple pear --from chest");
            g.server("at", "Put a block at a cell.", (src, args) -> src.reply(TaskResult.ok("put").toJson()),
                    CELL, BLOCK).example("gt_script at 1 2 3 --block stone");
            g.server("say", "Say something.", (src, args) -> src.reply(TaskResult.ok("said").toJson()), TEXT)
                    .example("gt_script say hello there");
            g.server("has", "Whether a thing exists.", (src, args) -> src.reply(TaskResult.ok("yes").toJson()), NAME)
                    .example("gt_script has apple")
                    .returns("has");
            g.server("goto", "Go to a thing.", (src, args) -> src.reply(TaskResult.ok("went").toJson()), NAME)
                    .example("gt_script goto apple");
        });
    }

    private static ScriptRun.Call call(String verb, List<Object> args, Map<String, Object> options) {
        return new ScriptRun.Call(1, "gt_script", verb, args, options);
    }

    @Test
    void theLastPositionalTakesEveryObjectLeftAndTheTableIsTheFlags() {
        assertEquals("gt_script take apple pear --from chest --count 3",
                NumenCli.scriptLine(call("take", List.of("apple", "pear"), Map.of("from", "chest", "count", 3L))));
        assertEquals("gt_script take apple", NumenCli.scriptLine(call("take", List.of("apple"), Map.of())));
    }

    @Test
    void aListIsTheSeveralWordsOfOneValue() {
        assertEquals("gt_script at 1 2 3 --block stone",
                NumenCli.scriptLine(call("at", List.of(List.of(1L, 2L, 3L)), Map.of("block", "stone"))));
        assertEquals("gt_script at 1 2 3", NumenCli.scriptLine(call("at", List.of(1L, 2L, 3L), Map.of())));
    }

    @Test
    void theRestOfTheLineTakesEveryObjectLeft() {
        assertEquals("gt_script say hello there 3",
                NumenCli.scriptLine(call("say", List.of("hello", "there", 3L), Map.of())));
    }

    @Test
    void flagNamesTakeUnderscoresForDashes() {
        assertEquals("gt_script take apple --count 2",
                NumenCli.scriptLine(call("take", List.of("apple"), Map.of("count", 2L))));
    }

    @Test
    void mistakesSayWhatIsWrongAndGiveTheUsage() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> NumenCli.scriptLine(call("take", List.of(), Map.of())));
        assertTrue(missing.getMessage().contains("missing required argument: items"), missing.getMessage());
        assertTrue(missing.getMessage().contains("usage: gt_script take <items...>"), missing.getMessage());

        IllegalArgumentException option = assertThrows(IllegalArgumentException.class,
                () -> NumenCli.scriptLine(call("take", List.of("apple"), Map.of("form", "chest"))));
        assertTrue(option.getMessage().contains("unknown argument 'form'"), option.getMessage());

        IllegalArgumentException none = assertThrows(IllegalArgumentException.class,
                () -> NumenCli.scriptLine(new ScriptRun.Call(1, "gt_script", "fly", List.of(), Map.of())));
        assertEquals("there is no command gt_script fly", none.getMessage());
    }

    @Test
    void theCatalogHasEveryActionAndWhatItReturns() {
        ScriptCatalog catalog = NumenCli.scriptCatalog();
        assertNull(catalog.verb("gt_script", "take").returns());
        assertEquals("has", catalog.verb("gt_script", "has").returns());
        assertNull(catalog.verb("gt_script", "fly"));
    }

    @Test
    void theHelpSaysWhatAFunctionIsCalledAndWhatItReturns() {
        assertTrue(CliFixture.onServer("gt_script has --help").message().contains(
                "In a script: gt_script.has(...) returns data.has, whether the command succeeds or not."));
        assertTrue(CliFixture.onServer("gt_script goto --help").message().contains(
                "In a script: gt_script.goto_(...)."));
        assertTrue(!CliFixture.onServer("gt_script take --help").message().contains("In a script"),
                "名字照原样、返回照常的不多写一行");
    }
}
