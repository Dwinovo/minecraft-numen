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
 * 对象的写法只在 {@link ArgType} 一处读。一行命令上:一格坐标是三个数(也收 {@code x,y,z} 一个词),一处是一到三个数或一块区域,
 * 实体是运行期编号。脚本里:一格只有带键的表 Pos 一种写法,带 {@code pos} 的表(方块、实体)原样就是那一格,小数按 Minecraft 的定义
 * 向下取整;一列、一个高度也是带键的表;实体是编号或带 {@code id} 的表。旧的写法(三个数的列表、"x y z" 字符串、一个数当高度)
 * 拒绝,并给出照新写法改好的那一行。可以不写的位置参数({@code fight attack [<entity>...]} 这种)写不写都读得通。
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
            g.server("look", "Look at a cell.", ObjectArgTest::remember, CELL)
                    .returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING)
                    .example("gt_obj.look({x = 1, y = 2, z = 3})");
            g.server("dig", "Dig places.", ObjectArgTest::remember, PLACES, TO)
                    .returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING)
                    .example("gt_obj.dig(\"ores\", {x = 1, y = 2, z = 3})");
            g.server("hit", "Hit someone, or everyone.", ObjectArgTest::remember, WHO, TIMES)
                    .returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING)
                    .example("gt_obj.hit(27, 26)").example("gt_obj.hit()");
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
        assertTrue(failed("gt_obj look 120 64").startsWith("error: expected a cell: three whole numbers x y z"));
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

    /** 查询交出的位置(实体、掉落物的小数位置)原样交回来,读成的就是它所在的那一格:留两位时往下舍,不会入到上面一格。 */
    @Test
    void aPositionHandedBackReadsAsTheCellItIsIn() {
        com.google.gson.JsonObject pos = Shapes.pos(new net.minecraft.world.phys.Vec3(0.5, -72.004, 0.5));
        assertEquals(-72.01, pos.get("y").getAsDouble(), 1e-9);
        String literal = com.dwinovo.numen.agent.script.ScriptEngine.IN_USE.value(
                com.dwinovo.numen.agent.script.JsonValues.toJava(pos));
        assertEquals(new BlockPos(0, -73, 0), luaRan("gt_obj.look(" + literal + ")").get(CELL));
    }

    /** 脚本里一格只有带键的表一种写法;带 pos 的表(查到的方块、实体)原样就是那一格,和一行命令上读出来的是同一份。 */
    @Test
    void aScriptCallReadsPositionsAsKeyedTablesOrAnythingWithAPos() {
        CommandArgs viaLine = ran("gt_obj dig 120 64 -35 ores");
        List<Param<?>> params = List.of(PLACES, TO);
        assertEquals(viaLine, CommandArgs.fromJson(params, JsonParser.parseString(
                "{\"place\": [{\"x\": 120, \"y\": 64, \"z\": -35}, \"ores\"]}").getAsJsonObject()));
        for (String code : List.of("gt_obj.dig({x = 120, y = 64, z = -35}, \"ores\")",
                "gt_obj.dig({{x = 120, y = 64, z = -35}, \"ores\"})",
                "gt_obj.dig({name = \"iron_ore\", pos = {x = 120, y = 64, z = -35}}, \"ores\")",
                "local b = {pos = {x = 120.9, y = 64.5, z = -34.1}}\ngt_obj.dig(b, \"ores\")")) {
            assertEquals(viaLine, luaRan(code), code);
        }
        assertEquals(new BlockPos(120, 64, -35), luaRan("gt_obj.look({x = 120.7, y = 64.0, z = -34.2})").get(CELL),
                "小数是它所在的那一格:向下取整,和 Minecraft 的 BlockPos.containing 一样");
        assertEquals(new BlockPos(1, 2, 3), luaRan("gt_obj.look({pos = {x = 1, y = 2, z = 3}, id = 7})").get(CELL),
                "带 pos 的表(实体、掉落物)原样就是它所在的那一格");
        assertEquals(List.of(new Place(120, null, -35, null)), luaRan("gt_obj.dig({x = 120, z = -35})").get(PLACES),
                "只有 x、z 的表是一列");
        assertEquals(List.of(new Place(null, 16, null, null)), luaRan("gt_obj.dig({y = 16})").get(PLACES),
                "只有 y 的表是一个高度");
        assertEquals(List.of(EntityRef.id(27), EntityRef.id(26)),
                luaRan("gt_obj.hit({id = 27, type = \"minecraft:zombie\", pos = {x = 1, y = 2, z = 3}}, 26)").get(WHO),
                "实体是编号,或带 id 的那张表");
    }

    /** 旧的写法都拒绝,种类是 bad_argument,说出是哪个参数、要什么、给了什么,hint 是照新写法改好的那一行。 */
    @Test
    void theOldShapesAreRefusedWithTheLineRewritten() {
        CliFixture.Outcome list = CliFixture.lua("local ok, err = pcall(gt_obj.look, {120, 64, -35})\n"
                + "print(err.kind)\nprint(err.message)\nprint(err.hint)");
        String printed = list.message().substring(list.message().indexOf("printed:\n") + "printed:\n".length());
        assertEquals("""
                bad_argument
                argument 'cell': a cell is a Pos with named fields; got {120, 64, -35}
                usage: gt_obj.look(cell)
                  e.g. gt_obj.look({x = 1, y = 2, z = 3})
                gt_obj.look({x = 120, y = 64, z = -35})""", printed);

        CliFixture.Outcome text = CliFixture.lua("gt_obj.look(\"120 64 -35\")");
        assertFalse(text.success());
        assertTrue(text.message().contains("hint: gt_obj.look({x = 120, y = 64, z = -35})"), text.message());

        CliFixture.Outcome height = CliFixture.lua("gt_obj.dig(16)");
        assertFalse(height.success());
        assertTrue(height.message().contains("argument 'place': a place given by coordinates is a table with named "
                + "fields; got 16"), height.message());
        assertTrue(height.message().contains("hint: gt_obj.dig({y = 16})"), height.message());

        CliFixture.Outcome listed = CliFixture.lua("gt_obj.dig({120, 64, -35})");
        assertFalse(listed.success());
        assertTrue(listed.message().contains("hint: gt_obj.dig({x = 120, y = 64, z = -35})"),
                "三个数的列表交给一串值的参数,是写成旧样子的一处,不是三处: " + listed.message());

        CliFixture.Outcome among = CliFixture.lua("gt_obj.dig(\"ores\", \"120 64 -35\")");
        assertFalse(among.success());
        assertTrue(among.message().contains("hint: write each of place like {x = 120, y = 64, z = -35}."),
                among.message());

        CliFixture.Outcome spread = CliFixture.lua("gt_obj.look(120, 64, -35)");
        assertTrue(spread.message().contains("takes 1 object(s), got 3"), spread.message());

        CliFixture.Outcome nil = CliFixture.lua("gt_obj.look(nothing_here)");
        assertTrue(nil.message().contains("argument 'cell' is missing (or nil)"), nil.message());

        CliFixture.Outcome id = CliFixture.lua("gt_obj.hit(\"27\")");
        assertTrue(id.message().contains("argument 'entity': an entity id is a number; got \"27\""), id.message());
        assertTrue(id.message().contains("hint: gt_obj.hit(27)"), id.message());

        CliFixture.Outcome column = CliFixture.lua("gt_obj.look({x = 1, z = 3})");
        assertTrue(column.message().contains("argument 'cell': expected a Pos {x = …, y = …, z = …} or anything with "
                + "a pos (a Block, an Entity, an Item); got {x = 1, z = 3}, which has no y"), column.message());
    }

    private static CommandArgs luaRan(String code) {
        LAST.set(null);
        CliFixture.Outcome out = CliFixture.lua(code);
        assertTrue(out.success(), code + ": " + out.message());
        return LAST.get();
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
        assertTrue(failed("gt_obj hit fox").startsWith("error: expected an entity id as scan.entities lists it"));
    }
}
