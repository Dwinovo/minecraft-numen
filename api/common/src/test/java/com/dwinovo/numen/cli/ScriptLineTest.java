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
 * 脚本里的函数由命令登记处生成:每个动作一个 {@code 组.动作},按顺序的对象是必填参数、选项表是可选参数,写成和她亲手写的一样的
 * 一行;声明了返回项的动作在目录里带着它。
 */
class ScriptLineTest {

    static final Param<Integer> COUNT = Param.required("count", ArgType.integer(1, 64), "How many.");
    static final Param<String> ITEM = Param.required("item", ArgType.word(), "Which item.");
    static final Param<String> FROM = Param.optional("from", ArgType.word(), "Where to take them from.");
    static final Param<List<String>> TAGS = Param.optional("tags", ArgType.list(ArgType.word()), "Tags.");
    static final Param<String> TEXT = Param.required("text", ArgType.text(), "What to say.");
    static final Param<String> NAME = Param.required("name", ArgType.word(), "The thing.");

    @BeforeAll
    static void register() {
        door().registerCommands("gt_script", "A group the Lua bindings read.", g -> {
            g.server("take", "Take some items.", (src, args) -> src.reply(TaskResult.ok("took").toJson()),
                    COUNT, ITEM, FROM, TAGS).example("gt_script take 3 apple --from chest");
            g.server("say", "Say something.", (src, args) -> src.reply(TaskResult.ok("said").toJson()), TEXT)
                    .example("gt_script say hello there");
            g.server("has", "Whether a thing exists.", (src, args) -> src.reply(TaskResult.ok("yes").toJson()), NAME)
                    .example("gt_script has apple")
                    .returns("has");
        });
    }

    private static ScriptRun.Call call(String verb, List<Object> args, Map<String, Object> options) {
        return new ScriptRun.Call(1, "gt_script", verb, args, options);
    }

    @Test
    void objectsAreTheRequiredArgumentsAndTheTableTheFlags() {
        assertEquals("gt_script take 3 apple --from chest --tags red ripe",
                NumenCli.scriptLine(call("take", List.of(3L, "apple"),
                        Map.of("from", "chest", "tags", List.of("red", "ripe")))));
        assertEquals("gt_script take 3 apple", NumenCli.scriptLine(call("take", List.of(3L, "apple"), Map.of())));
    }

    @Test
    void theRestOfTheLineTakesEveryObjectLeft() {
        assertEquals("gt_script say hello there 3", NumenCli.scriptLine(call("say", List.of("hello", "there", 3L), Map.of())));
    }

    @Test
    void mistakesSayWhatIsWrongAndGiveTheUsage() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> NumenCli.scriptLine(call("take", List.of(3L), Map.of())));
        assertTrue(missing.getMessage().contains("missing required argument: item"), missing.getMessage());
        assertTrue(missing.getMessage().endsWith("usage: gt_script take <count> <item> [--from <word>] [--tags <word...>]"),
                missing.getMessage());

        IllegalArgumentException extra = assertThrows(IllegalArgumentException.class,
                () -> NumenCli.scriptLine(call("take", List.of(3L, "apple", "pear"), Map.of())));
        assertTrue(extra.getMessage().startsWith("takes 2 objects, got 3"), extra.getMessage());

        IllegalArgumentException option = assertThrows(IllegalArgumentException.class,
                () -> NumenCli.scriptLine(call("take", List.of(3L, "apple"), Map.of("form", "chest"))));
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
    void anActionThatReturnsAValueSaysSoInItsHelp() {
        CliFixture.Outcome help = CliFixture.onServer("gt_script has --help");
        assertTrue(help.message().contains("In a script: gt_script.has(...) returns data.has directly, and fails at the "
                + "call when the command fails."), help.message());
    }
}
