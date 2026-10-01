package com.dwinovo.numen.cli;

import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.onServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对象的写法全局一种、只在 {@link ArgType} 一处读:一格坐标是三个数(也收 {@code x,y,z} 一个词),一处是一到三个数或一块区域,
 * 实体是运行期编号;快捷工具 JSON 里写成一整串的 {@code "120 64 -35"} 与三个数的数组同样按坐标认。可以不写的位置参数
 * ({@code fight attack [<entity>...]} 这种)写不写都读得通。
 */
class ObjectArgTest {

    static final Param<BlockPos> CELL = Param.required("cell", ArgType.cell(), "The cell.");
    static final Param<List<Place>> PLACES = Param.required("place", ArgType.list(ArgType.place()), "Where.");
    static final Param<Place> TO = Param.optional("to", ArgType.place(), "Where to.").whenOmitted("stay");
    static final Param<List<EntityRef>> WHO = Param.optionalPositional("entity", ArgType.list(ArgType.entity()),
            "Who.").whenOmitted("everyone");
    static final Param<Integer> TIMES = Param.optional("times", ArgType.integer(1, 9), "How often.")
            .whenOmitted("once");

    static final AtomicReference<CommandArgs> LAST = new AtomicReference<>();

    @BeforeAll
    static void register() {
        door().registerCommands("gt_obj", "A group whose actions take the object types.", g -> {
            g.server("look", "Look at a cell.", ObjectArgTest::remember, CELL).example("gt_obj look 1 2 3")
                    .promote("Look at a cell, as a tool.");
            g.server("dig", "Dig places.", ObjectArgTest::remember, PLACES, TO).example("gt_obj dig ores 1 2 3")
                    .promote("Dig places, as a tool.");
            g.server("hit", "Hit someone, or everyone.", ObjectArgTest::remember, WHO, TIMES)
                    .example("gt_obj hit 27 26").example("gt_obj hit");
        });
    }

    private static void remember(ServerSource src, CommandArgs args) {
        LAST.set(args);
        src.reply(TaskResult.ok("done").toJson());
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
    void aCellIsThreeNumbersOrOneWordWithCommas() {
        assertEquals(new BlockPos(120, 64, -35), ran("gt_obj look 120 64 -35").get(CELL));
        assertEquals(new BlockPos(120, 64, -35), ran("gt_obj look 120,64,-35").get(CELL));
        assertTrue(failed("gt_obj look 120 64").startsWith("error: expected a cell: three whole numbers x y z, or x,y,z"));
        assertTrue(failed("gt_obj look 120.5 64 -35").startsWith("error: expected a cell"));
        assertTrue(failed("gt_obj look 1,2 3").startsWith("error: expected a cell"), "一种写法里不混着两种分隔");
    }

    @Test
    void aPlaceIsOneToThreeNumbersOrAnArea() {
        List<Place> places = ran("gt_obj dig ores/g3 120 64 -35 1 2 3 7,8,9 farm").get(PLACES);
        assertEquals(List.of(Place.area(AreaRef.parse("ores/g3")), Place.cell(new BlockPos(120, 64, -35)),
                Place.cell(new BlockPos(1, 2, 3)), Place.cell(new BlockPos(7, 8, 9)), Place.area(AreaRef.parse("farm"))),
                places, "连着的数三个一组是一格");
        assertEquals(List.of(new Place(120, null, -35, null)), ran("gt_obj dig 120 -35").get(PLACES), "两个数是一列");
        assertEquals(List.of(new Place(null, 16, null, null)), ran("gt_obj dig 16").get(PLACES), "一个数是一个高度");
        assertEquals(Place.cell(new BlockPos(1, 2, 3)), ran("gt_obj dig ores --to 1 2 3").get(TO), "标志的值也照同一种读");
        assertTrue(failed("gt_obj dig Ores").startsWith("error: area names are lowercase letters"));
    }

    /** 快捷工具 JSON:一个字符串就是它在命令行上的样子,一串值的数组各项接成一行再读——同一个读法。 */
    @Test
    void theShortcutReadsTheSamePlacesFromAStringOrNumbers() {
        CommandArgs viaLine = ran("gt_obj dig 120 64 -35 ores");
        List<Param<?>> params = List.of(PLACES, TO);
        for (String json : List.of("{\"place\": [\"120 64 -35\", \"ores\"]}", "{\"place\": [120, 64, -35, \"ores\"]}",
                "{\"place\": [\"120,64,-35\", \"ores\"]}")) {
            assertEquals(viaLine, CommandArgs.fromJson(params, JsonParser.parseString(json).getAsJsonObject()), json);
        }
        CommandArgs cell = CommandArgs.fromJson(List.of(CELL), JsonParser.parseString("{\"cell\": \"1 2 3\"}")
                .getAsJsonObject());
        assertEquals(new BlockPos(1, 2, 3), cell.get(CELL));
        assertEquals(new BlockPos(1, 2, 3), CommandArgs.fromJson(List.of(CELL), JsonParser.parseString(
                "{\"cell\": [1, 2, 3]}").getAsJsonObject()).get(CELL), "三个数的数组也是一格");
    }

    @Test
    void anOptionalPositionalReadsWrittenOrNot() {
        assertEquals(List.of(EntityRef.id(27), EntityRef.id(26)), ran("gt_obj hit 27 26").get(WHO));
        assertNull(ran("gt_obj hit").get(WHO));
        CommandArgs flagged = ran("gt_obj hit --times 2");
        assertNull(flagged.get(WHO));
        assertEquals(2, flagged.get(TIMES));
        assertEquals(List.of(EntityRef.id(5)), ran("gt_obj hit 5 --times 3").get(WHO));
        assertEquals("gt_obj hit 5 --times 3", ran("gt_obj hit 5 --times 3").write("gt_obj hit", List.of(WHO, TIMES)));
        assertEquals("gt_obj hit", ran("gt_obj hit").write("gt_obj hit", List.of(WHO, TIMES)));
        assertTrue(failed("gt_obj hit fox").startsWith("error: expected an entity id as scan entities lists it"));
    }
}
