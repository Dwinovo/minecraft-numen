package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.onClient;
import static com.dwinovo.numen.cli.CliFixture.onServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一串值当标志、当后面还跟着标志的位置参数,一串整数、一串 id,以及方块或坐标格:命令行上怎么读、写错了说什么、
 * 快捷工具的 JSON 读出来是否同一个值、schema 与帮助里写成什么。
 */
class ListFlagArgTypeTest {

    static final Param<List<String>> BLOCKS = Param.required("blocks", ArgType.list(ArgType.idOrTag()),
            "Which blocks.");
    static final Param<List<Integer>> IDS = Param.optional("ids", ArgType.list(ArgType.integer()), "Which ones.");
    static final Param<List<String>> KEEP = Param.optional("keep", ArgType.list(ArgType.blockOrCells()),
            "What to leave standing.");
    static final Param<List<ResourceLocation>> ITEMS = Param.optional("items", ArgType.list(ArgType.id()),
            "What to take.");
    static final Param<Integer> COUNT = Param.optional("count", ArgType.integer(1, 64), "How many.");
    static final List<Param<?>> PARAMS = List.of(BLOCKS, IDS, KEEP, ITEMS, COUNT);

    static final AtomicReference<CommandArgs> LAST = new AtomicReference<>();

    @BeforeAll
    static void register() {
        door().registerCommands("gt_flags", "A group whose action takes lists as flags.", g ->
                g.server("pick", "Pick some things.", (src, args) -> {
                    LAST.set(args);
                    src.reply(TaskResult.ok("picked").toJson());
                }, BLOCKS, IDS, KEEP, ITEMS, COUNT)
                        .example("gt_flags pick iron_ore #minecraft:logs --ids 3 -4 --keep 1,2,3..4,5,6 chest --count 2")
                        .promote("gt_flags_pick", "Pick some things, as a tool."));
    }

    private static CommandArgs ran(String line) {
        LAST.set(null);
        CliFixture.Outcome out = onServer(line);
        assertTrue(out.success(), out.message());
        return LAST.get();
    }

    private static String failed(String line) {
        LAST.set(null);
        CliFixture.Outcome out = onServer(line);
        assertFalse(out.success(), line + " should fail");
        assertNull(LAST.get(), "处理函数不该被调到");
        return out.message();
    }

    @Test
    void aListStopsAtTheNextFlag() {
        CommandArgs args = ran("gt_flags pick iron_ore deepslate_iron_ore --ids 184 -2 --count 5 "
                + "--keep minecraft:chest 12,60,8 -1,-2,-3..4,5,6 #minecraft:beds --items iron_ingot raw_iron");
        assertEquals(List.of("iron_ore", "deepslate_iron_ore"), args.get(BLOCKS), "位置上的一串读到第一个标志为止");
        assertEquals(List.of(184, -2), args.get(IDS), "负数只有一个 -,不是标志");
        assertEquals(5, args.get(COUNT));
        assertEquals(List.of("minecraft:chest", "12,60,8", "-1,-2,-3..4,5,6", "#minecraft:beds"), args.get(KEEP));
        assertEquals(List.of(ResourceLocation.withDefaultNamespace("iron_ingot"),
                ResourceLocation.withDefaultNamespace("raw_iron")), args.get(ITEMS), "标志里的一串一直读到行尾");
        assertEquals(List.of("stone"), ran("gt_flags pick stone").get(BLOCKS), "一个也是一串");
    }

    @Test
    void aBadItemSaysWhatWasExpected() {
        assertTrue(failed("gt_flags pick stone --keep 1,2").startsWith("expected a cell x,y,z or a box"));
        assertTrue(failed("gt_flags pick stone --keep 1,two,3").startsWith("expected a cell x,y,z or a box"));
        assertTrue(failed("gt_flags pick stone --keep 1,2,3..4,5").startsWith("expected a cell x,y,z or a box"));
        assertTrue(failed("gt_flags pick stone --ids 1.5").startsWith("Invalid integer '1.5'"));
        assertTrue(failed("gt_flags pick stone --ids").startsWith("--ids needs a value"));
        assertTrue(failed("gt_flags pick stone --ids 3 --ids 4").startsWith("--ids is given twice"),
                "一串值写在一个标志里,不靠重复标志");
    }

    @Test
    void theShortcutReadsTheSameValuesFromJsonArrays() {
        CommandArgs viaLine = ran("gt_flags pick iron_ore #minecraft:logs --ids 3 4 --keep 1,2,3..4,5,6 chest "
                + "--items iron_ingot --count 2");
        CommandArgs viaJson = CommandArgs.fromJson(PARAMS, JsonParser.parseString("""
                {"blocks": ["iron_ore", "#minecraft:logs"], "ids": [3, 4], "keep": ["1,2,3..4,5,6", "chest"],
                 "items": ["iron_ingot"], "count": 2}""").getAsJsonObject());
        assertEquals(viaLine, viaJson);
        IllegalArgumentException notInts = assertThrows(IllegalArgumentException.class,
                () -> CommandArgs.fromJson(PARAMS, JsonParser.parseString("{\"blocks\": [\"stone\"], \"ids\": [\"x\"]}")
                        .getAsJsonObject()));
        assertTrue(notInts.getMessage().startsWith("argument 'ids': Expected integer"), notInts.getMessage());
    }

    @Test
    void aListInThePositionsMustComeLast() {
        IllegalArgumentException early = assertThrows(IllegalArgumentException.class, () ->
                door().registerCommands("gt_flags_bad", "A group whose list is not last.", g ->
                        g.server("pick", "Pick.", (src, args) -> { }, BLOCKS, Param.required("n", ArgType.integer(), "N."))
                                .example("gt_flags_bad pick stone 1")));
        assertTrue(early.getMessage().contains("是一串值"), early.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ArgType.list(ArgType.bool()));
        assertThrows(IllegalArgumentException.class, () -> ArgType.list(ArgType.number(0, 1)));
        assertThrows(IllegalArgumentException.class, () -> ArgType.list(ArgType.list(ArgType.word())));
    }

    @Test
    void theSchemaAndTheHelpNameEachType() {
        NumenTool tool = ToolRegistry.get("gt_flags_pick");
        assertEquals(new Gson().toJson(Schema.object()
                .stringArray("blocks", "Which blocks.", 1)
                .optionalIntArray("ids", "Which ones.", 0, 0)
                .optionalStringArray("keep", "What to leave standing.")
                .optionalStringArray("items", "What to take.")
                .optionalInteger("count", "How many.", 1, 64)
                .build()), new Gson().toJson(tool.parameterSchema()));
        assertEquals("""
                gt_flags pick <blocks...> [--ids <integer...>] [--keep <block|cell...>] [--items <id...>] [--count <integer>]
                  Pick some things.
                  <blocks...> (id or #tag, e.g. minecraft:oak_log or #minecraft:logs; one or more, separated by spaces) — Which blocks.
                  --ids <integer...> (integer; one or more, separated by spaces; optional) — Which ones.
                  --keep <block|cell...> (block id, #tag, cell x,y,z or box x1,y1,z1..x2,y2,z2; one or more, separated by spaces; optional) — What to leave standing.
                  --items <id...> (id, e.g. minecraft:oak_log; one or more, separated by spaces; optional) — What to take.
                  --count <integer> (integer 1-64; optional) — How many.
                  Examples:
                    gt_flags pick iron_ore #minecraft:logs --ids 3 -4 --keep 1,2,3..4,5,6 chest --count 2
                  Shortcut tool: gt_flags_pick.""", onClient("gt_flags pick --help").message());
    }
}
