package com.dwinovo.numen.cli;

import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.onServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一串值当标志、当后面还跟着标志的位置参数,一串整数、一串 id,以及方块、坐标格或区域:一行命令上怎么读、写错了说什么、
 * 脚本里的调用读出来是否同一个值、帮助里写成什么。
 */
class ListFlagArgTypeTest {

    static final Param<List<String>> BLOCKS = Param.required("blocks", ArgType.list(ArgType.idOrTag()),
            "Which blocks.");
    static final Param<List<Integer>> IDS = Param.optional("ids", ArgType.list(ArgType.integer()), "Which ones.")
            .whenOmitted("take any");
    static final Param<List<BlockCellOrArea>> KEEP = Param.optional("keep", ArgType.list(ArgType.blockCellOrArea()),
            "What to leave standing.").whenOmitted("keep nothing");
    static final Param<List<ResourceLocation>> ITEMS = Param.optional("items", ArgType.list(ArgType.id()),
            "What to take.").whenOmitted("take everything");
    static final Param<Integer> COUNT = Param.optional("count", ArgType.integer(1, 64), "How many.")
            .whenOmitted("take one");
    static final List<Param<?>> PARAMS = List.of(BLOCKS, IDS, KEEP, ITEMS, COUNT);

    static final AtomicReference<CommandArgs> LAST = new AtomicReference<>();

    @BeforeAll
    static void register() {
        door().registerCommands("gt_flags", "A group whose action takes lists as flags.", g ->
                g.server("pick", "Pick some things.", (src, args) -> {
                    LAST.set(args);
                    src.reply(TaskResult.ok("picked").toJson());
                }, BLOCKS, IDS, KEEP, ITEMS, COUNT)
                        .example("gt_flags.pick(\"iron_ore\", \"#minecraft:logs\", {ids = {3, -4}, keep = {\"1,2,3\", "
                                + "\"area:house\", \"chest\"}, count = 2})"));
    }

    private static CommandArgs ran(String line) {
        LAST.set(null);
        CliFixture.Outcome out = onServer(line);
        assertTrue(out.success(), out.message());
        return LAST.get();
    }

    /** 写错的一行:处理函数不被调到,回执以 {@code error:} 起头;返回 {@code error:} 之后的那一句。 */
    private static String failed(String line) {
        LAST.set(null);
        CliFixture.Outcome out = onServer(line);
        assertFalse(out.success(), line + " should fail");
        assertNull(LAST.get(), "处理函数不该被调到");
        assertTrue(out.message().startsWith("error: "), out.message());
        return out.message().substring("error: ".length());
    }

    private static BlockCellOrArea block(String id) {
        return new BlockCellOrArea(id, null, null);
    }

    private static BlockCellOrArea cell(int x, int y, int z) {
        return new BlockCellOrArea(null, new net.minecraft.core.BlockPos(x, y, z), null);
    }

    private static BlockCellOrArea area(String ref) {
        return new BlockCellOrArea(null, null, com.dwinovo.numen.area.AreaRef.parse(ref));
    }

    @Test
    void aListStopsAtTheNextFlag() {
        CommandArgs args = ran("gt_flags pick iron_ore deepslate_iron_ore --ids 184 -2 --count 5 "
                + "--keep minecraft:chest 12,60,8 -1,-2,-3 area:ores/g3 #minecraft:beds --items iron_ingot raw_iron");
        assertEquals(List.of("iron_ore", "deepslate_iron_ore"), args.get(BLOCKS), "位置上的一串读到第一个标志为止");
        assertEquals(List.of(184, -2), args.get(IDS), "负数只有一个 -,不是标志");
        assertEquals(5, args.get(COUNT));
        assertEquals(List.of(block("minecraft:chest"), cell(12, 60, 8), cell(-1, -2, -3), area("ores/g3"),
                block("#minecraft:beds")), args.get(KEEP));
        assertEquals(List.of(cell(12, 60, 8), block("chest")), ran("gt_flags pick stone --keep 12 60 8 chest").get(KEEP),
                "一格坐标也收三个数空格隔开");
        assertEquals(List.of(ResourceLocation.withDefaultNamespace("iron_ingot"),
                ResourceLocation.withDefaultNamespace("raw_iron")), args.get(ITEMS), "标志里的一串一直读到行尾");
        assertEquals(List.of("stone"), ran("gt_flags pick stone").get(BLOCKS), "一个也是一串");
    }

    @Test
    void aBadItemSaysWhatWasExpected() {
        assertTrue(failed("gt_flags pick stone --keep 1,2").startsWith("expected a cell: three whole numbers, "
                + "{x, y, z} or \"x y z\""));
        assertTrue(failed("gt_flags pick stone --keep 1,two,3").startsWith("expected a cell: three whole numbers, "
                + "{x, y, z} or \"x y z\""));
        assertTrue(failed("gt_flags pick stone --keep 1,2,3..4,5,6").startsWith("a cell is one x y z; a box or any "
                + "other stretch of cells is an area"), "一片格子只有区域一种写法");
        assertTrue(failed("gt_flags pick stone --keep area:House").startsWith("area names are lowercase letters"));
        assertTrue(failed("gt_flags pick stone --keep area:ores/x3").startsWith("a part of an area is a letter"));
        assertTrue(failed("gt_flags pick stone --ids 1.5").startsWith("Invalid integer '1.5'"));
        assertTrue(failed("gt_flags pick stone --ids").startsWith("--ids needs a value"));
        assertTrue(failed("gt_flags pick stone --ids 3 --ids 4").startsWith("--ids is given twice"),
                "一串值写在一个标志里,不靠重复标志");
    }

    @Test
    void aScriptCallReadsTheSameValuesFromJsonArrays() {
        CommandArgs viaLine = ran("gt_flags pick iron_ore #minecraft:logs --ids 3 4 --keep area:house 1,2,3 chest "
                + "--items iron_ingot --count 2");
        CommandArgs viaJson = CommandArgs.fromJson(PARAMS, JsonParser.parseString("""
                {"blocks": ["iron_ore", "#minecraft:logs"], "ids": [3, 4], "keep": ["area:house", "1,2,3", "chest"],
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
                                .example("gt_flags_bad.pick(\"stone\", 1)")));
        assertTrue(early.getMessage().contains("是一串值"), early.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ArgType.list(ArgType.bool()));
        assertThrows(IllegalArgumentException.class, () -> ArgType.list(ArgType.number(0, 1)));
        assertThrows(IllegalArgumentException.class, () -> ArgType.list(ArgType.list(ArgType.word())));
    }

    /** 脚本里写成表的一串值,和一行命令上空格隔开的一串,处理函数拿到的是同一份。 */
    @Test
    void aScriptGivesListsAsTables() {
        CommandArgs viaLine = ran("gt_flags pick iron_ore #minecraft:logs --ids 3 -4 --keep 1,2,3 area:house chest");
        LAST.set(null);
        CliFixture.Outcome out = CliFixture.lua("gt_flags.pick({\"iron_ore\", \"#minecraft:logs\"}, "
                + "{ids = {3, -4}, keep = {\"1,2,3\", \"area:house\", \"chest\"}})");
        assertTrue(out.success(), out.message());
        assertEquals(viaLine, LAST.get());
    }

    @Test
    void theHelpNamesEachType() {
        assertEquals("""
                gt_flags.pick(blocks..., {ids=…, keep=…, items=…, count=…})
                  Pick some things.
                  blocks... (id or #tag, e.g. minecraft:oak_log or #minecraft:logs; one, or several as a list {a, b}) — Which blocks.
                  ids= (integer; one, or several as a list {a, b}; optional) — Which ones. Omit to take any.
                  keep= (block id, #tag, cell "x,y,z" or "area:<name>"; one, or several as a list {a, b}; optional) — What to leave standing. Omit to keep nothing.
                  items= (id, e.g. minecraft:oak_log (minecraft: may be left out); one, or several as a list {a, b}; optional) — What to take. Omit to take everything.
                  count= (integer 1-64; optional) — How many. Omit to take one.
                  Examples:
                    gt_flags.pick("iron_ore", "#minecraft:logs", {ids = {3, -4}, keep = {"1,2,3", "area:house", "chest"}, count = 2})""",
                CliFixture.help("gt_flags.pick"));
    }
}
