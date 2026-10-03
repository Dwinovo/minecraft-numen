package com.dwinovo.numen.cli;

import com.dwinovo.numen.task.TaskResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dwinovo.numen.cli.CliFixture.door;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一行命令只读不执行:读成动作路径与参数,和执行时处理函数拿到的是同一份;读好的参数写回一行,再读一遍还是同一份。
 * 文字里写着的命令按约定认出来(反引号或代码块里、以一级命令或 {@code /} 打头),读不通的指出在哪、为什么。
 */
class ReadingTest {

    static final Param<String> NAME = Param.required("name", ArgType.string(), "A name.");
    static final Param<List<String>> ROWS = Param.required("rows", ArgType.list(ArgType.string()), "Rows.");
    static final Param<Integer> COUNT = Param.optional("count", ArgType.integer(1, 9), "How many.")
            .whenOmitted("put one");
    static final Param<ResourceLocation> ITEM = Param.optional("item", ArgType.id(), "An item.")
            .whenOmitted("put what you hold");
    static final Param<Double> SPEED = Param.optional("speed", ArgType.number(0, 2), "How fast.")
            .whenOmitted("go at 1");
    static final Param<String> MODE = Param.optional("mode", ArgType.oneOf("walk", "run"), "How.")
            .whenOmitted("walk");
    static final Param<String> NOTE = Param.required("note", ArgType.text(), "Free text.");

    static final List<Param<?>> PUT = List.of(NAME, ROWS, COUNT, ITEM, SPEED, MODE);

    @BeforeAll
    static void register() {
        door().registerCommands("gt_read", "A group the reader reads.", g -> {
            g.server("put", "Put things.", (src, args) -> src.reply(TaskResult.ok("put").toJson()),
                            PUT.toArray(Param<?>[]::new))
                    .example("gt_read.put(\"two words\", \"###\", \"#.#\", {count = 3, item = \"minecraft:stone\"})");
            g.client("say", "Say something.", (src, args) -> src.reply(TaskResult.ok("said").toJson()), NOTE)
                    .example("gt_read.say(\"hello there\")");
        });
    }

    @Test
    void aWholeLineReadsIntoItsActionAndTheSameArgumentsExecutionGets() {
        NumenCli.Reading r = NumenCli.read("gt_read put \"two words\" ### #.# --speed 1.5 --item stone --count 3");
        assertEquals("gt_read put", r.path());
        assertTrue(r.runnable());
        assertEquals(3, r.args().get(COUNT));
        assertEquals("two words", r.args().get(NAME));
        assertEquals(List.of("###", "#.#"), r.args().get(ROWS));
        assertEquals(ResourceLocation.parse("minecraft:stone"), r.args().get(ITEM));
        assertEquals(1.5, r.args().get(SPEED));
        assertNull(r.args().get(MODE));
        NumenCli.Reading client = NumenCli.read("gt_read say hello there");
        assertEquals("hello there", client.args().get(NOTE), "主人客户端的动作也读得出参数");
    }

    @Test
    void writtenBackTheArgumentsReadTheSame() {
        for (String line : List.of(
                "gt_read put \"two words\" ### #.# --speed 1.5 --item stone --count 3",
                "gt_read put \"\" \" leading space\" \"--looks like a flag\" '#' \"say \\\"hi\\\"\" --mode run --count 1",
                "gt_read put 中文名 a --mode walk --count 9")) {
            CommandArgs args = NumenCli.read(line).args();
            String written = args.write("gt_read put", PUT);
            assertEquals(args, NumenCli.read(written).args(), "写回的一行读回来是同一份: " + written);
        }
        assertEquals("gt_read put \"two words\" ### #.# --count 3 --item minecraft:stone --speed 1.5",
                NumenCli.read("gt_read put \"two words\" ### #.# --speed 1.5 --item stone --count 3").args()
                        .write("gt_read put", PUT),
                "位置参数按声明顺序,标志按声明顺序,id 写全命名空间");
        assertEquals("gt_read put x y --count 3",
                NumenCli.read("gt_read put x y --item stone --count 3").args()
                        .write("gt_read put", List.of(NAME, ROWS, COUNT)),
                "没列进参数表的标志不写");
    }

    @Test
    void aMentionOfAGroupOrAnActionReadsButDoesNotRun() {
        assertEquals(new NumenCli.Reading("gt_read put", false, null), NumenCli.read("gt_read put"));
        assertEquals(new NumenCli.Reading("gt_read", false, null), NumenCli.read("gt_read"));
        NumenCli.Reading help = NumenCli.read("gt_read put --help");
        assertTrue(help.runnable() && help.args() == null && help.path().equals("gt_read put --help"));
        assertTrue(NumenCli.read("help").runnable());
    }

    @Test
    void aLineThatStopsHalfwayOrSaysTooMuchDoesNotRead() {
        IllegalArgumentException half = assertThrows(IllegalArgumentException.class,
                () -> NumenCli.read("gt_read put x"));
        assertTrue(half.getMessage().contains("usage: gt_read.put(name, rows..., {"), "附那个动作的用法: "
                + half.getMessage());
        IllegalArgumentException typo = assertThrows(IllegalArgumentException.class,
                () -> NumenCli.read("gt_read pot x y"));
        assertTrue(typo.getMessage().contains("hint: Did you mean: put?"), typo.getMessage());
        assertThrows(IllegalArgumentException.class, () -> NumenCli.read("gt_read put x y --nope 1"));
        assertThrows(IllegalArgumentException.class, () -> NumenCli.read(""));
    }

    @Test
    void callsInProseAreTheBackquotedAndFencedOnesThatStartWithAGroupsFunction() {
        String text = """
                Open it with `gt_read.put`, then `gt_read.say("hi")`. Blocks like `oak_log` and names like
                `skill_load` are not calls; neither is `gt_reader.x`. The old ways `gt_read say hi` and `/help` are
                picked up so they can be pointed at.
                ```
                gt_read.put("x", "y")
                floor ### (a drawing, not a call)
                ```
                ```lua
                local n = 1
                gt_read.say("one " .. n)
                ```
                """;
        assertEquals(List.of("gt_read.put(\"x\", \"y\")", "local n = 1\ngt_read.say(\"one \" .. n)",
                "gt_read.put", "gt_read.say(\"hi\")", "gt_read say hi", "/help"), WrittenCommands.in(text));
    }

    @Test
    void checkingPointsAtEachCallThatDoesNotRead() {
        CommandDispatcher<Object> mc = new CommandDispatcher<>();
        mc.register(LiteralArgumentBuilder.literal("give").then(
                RequiredArgumentBuilder.argument("count", IntegerArgumentType.integer()).executes(c -> 1)));
        WrittenCommands.NativeReader reader = line -> WrittenCommands.nativeProblem(mc, line, new Object());
        List<WrittenCommands.Wrong> wrong = WrittenCommands.check(List.of(
                new WrittenCommands.Text("a", "`gt_read.put(\"x\", \"y\")` then `gt_read.pot` and "
                        + "`mc.run(\"give 2\")`, `mc.run(\"give x\")`, `gt_read put x y`, `/give 2`"),
                new WrittenCommands.Text("b", "```lua\ngt_read.put(\"x\")\n```")), reader);
        assertEquals(List.of("a", "a", "a", "a", "b"), wrong.stream().map(WrittenCommands.Wrong::where).toList(),
                wrong::toString);
        assertEquals(List.of("gt_read.pot", "mc.run(\"give x\")", "gt_read put x y", "/give 2", "gt_read.put(\"x\")"),
                wrong.stream().map(WrittenCommands.Wrong::line).toList());
        assertNull(WrittenCommands.problem("mc.run(\"give\")", reader), "只点名一条原版指令读得通");
        assertNull(WrittenCommands.problem("""
                for _, p in ipairs(gt_read.say("x")) do gt_read.say(p) end""", reader),
                "拿返回值往下算的写法:停在那一处之前调到的都读得通就算读得通");
    }
}
