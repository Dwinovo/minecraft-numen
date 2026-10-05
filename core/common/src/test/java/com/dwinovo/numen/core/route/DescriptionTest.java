package com.dwinovo.numen.core.route;

import com.dwinovo.numen.core.CoreApiFixture;
import com.dwinovo.numen.pathing.spec.BlockBans;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Semantics.Kind;
import com.dwinovo.numen.sdk.ApiRegistry;
import com.dwinovo.numen.sdk.ApiTester;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.LuaCodecs;
import com.dwinovo.numen.sdk.ServerCall;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路线描述({@code numen.route.plan} 收的那张表)读成 {@link Description}、再翻成寻路规格:每个旋钮落到规格的哪一项,避开的格、盒子、
 * 一堆格子进位置表(盒子整片交给寻路、不逐格展开),途经点与到达方式的形状,以及每种写错都报教学式的参数错。表从脚本的入口进来,
 * 和她写的一样;方块 id 那几条需要 MC 注册表。
 */
@Tag("mc")
class DescriptionTest {

    private static final AtomicReference<Description> LAST = new AtomicReference<>();

    /** 夹具组:只把描述读出来,不规划。 */
    public static final class Reader {

        private Reader() {}

        @Fn("Read the description.")
        @Example("numen.gt_route.plan({to = {x = 1, y = 2, z = 3}, costs = {dig = true}})")
        public static void plan(ServerCall call, Description.Directions directions) {
            LAST.set(Description.of(directions));
        }
    }

    @BeforeAll
    static void boot() {
        CoreApiFixture.install();
        ApiRegistry.register("numen", "gt_route", "Test fixture: a route description read.", Reader.class);
    }

    /** 描述交给这一组的函数,和她写的一样;跑完返回读出的描述。 */
    private static Description read(String options) {
        ApiTester.Run run = run(options);
        assertTrue(run.ok(), run.message());
        return LAST.get();
    }

    private static RouteSpec spec(String options) {
        return read(options).spec(RouteSpec.defaults());
    }

    /** 描述写错了:程序停在那一行,返回那次调用的报错。 */
    private static String error(String options) {
        ApiTester.Run run = run(options);
        assertFalse(run.ok(), options + " should fail");
        String message = run.message();
        String error = message.substring(message.indexOf("numen.gt_route.plan: ") + "numen.gt_route.plan: ".length());
        assertTrue(error.startsWith("bad_argument — "), "描述写错是参数错: " + error);
        return error.substring("bad_argument — ".length());
    }

    private static ApiTester.Run run(String options) {
        LAST.set(null);
        return ApiTester.run(null, UUID.randomUUID(), "numen.gt_route.plan({" + options + "})");
    }

    private static final String TO = "to = {x = 1, y = 2, z = 3}";

    /** 底子的禁令描述放不开:没写的保持底子,写了的叠上去。 */
    @Test
    void aDescriptionLaysOverTheBase() {
        RouteSpec base = RouteSpec.defaults().edit().bans(new BlockBans(Set.of(Blocks.CHEST), Set.of(), Set.of()))
                .build();
        Description d = read(TO + ", avoid_break = {{x = 1, y = 2, z = 3}, \"minecraft:oak_log\"}");
        RouteSpec s = d.spec(base);
        assertTrue(s.positions().forbids(Use.DIG, new BlockPos(1, 2, 3).asLong()));
        assertTrue(s.bans().breaking().contains(Blocks.CHEST));
        assertTrue(s.bans().breaking().contains(Blocks.OAK_LOG));
    }

    /** 不写旋钮:一格不改;许挖许放时,要问主人的格照出厂的倍数算贵、算能走。 */
    @Test
    void withoutCostsTheWalkChangesNothing() {
        RouteSpec s = spec(TO);
        assertFalse(s.dig());
        assertFalse(s.place());
        assertTrue(s.consent());
        assertEquals(RouteSpec.CONSENT_MULTIPLIER, s.consentMultiplier());
        assertTrue(s.positions().isEmpty());
        assertTrue(s.bans().isEmpty());
    }

    @Test
    void everyKnobLandsOnItsField() {
        RouteSpec s = spec(TO + ", costs = {dig = 7.5, place = 5, consent = 4, jump = 9, swim = 0, fall = 6, "
                + "parkour = true, max_changes = 4}, avoid = {\"water\", \"door\"}, allow = {\"trigger\"}");
        assertTrue(s.dig() && s.place() && s.consent());
        assertEquals(7.5, s.breakPenalty());
        assertEquals(5.0, s.placeCost());
        assertEquals(4.0, s.consentMultiplier());
        assertEquals(9.0, s.jumpPenalty());
        assertEquals(0.0, s.wadePenalty());
        assertTrue(s.parkour());
        assertEquals(6, s.maxFallHeightNoWater());
        assertEquals(4, s.alterBudget());
        assertTrue(s.excludes(Kind.WATER));
        assertTrue(s.excludes(Kind.DOOR));
        assertFalse(s.excludes(Kind.CLIMBABLE));
        assertFalse(s.excludes(Kind.TRIGGER), "放开了的出厂避开");
        assertTrue(s.excludes(Kind.FRAGILE), "没放开的出厂避开照旧");
    }

    /** 挖、放是开关也是价钱:true 是按出厂罚分许它,false 是不许;consent = false 是把要问的格当墙。 */
    @Test
    void digPlaceAndConsentAreSwitchesWithPrices() {
        RouteSpec dig = spec(TO + ", costs = {dig = true}");
        assertTrue(dig.dig());
        assertFalse(dig.place());
        assertEquals(RouteSpec.defaults().breakPenalty(), dig.breakPenalty());
        RouteSpec walls = spec(TO + ", costs = {dig = true, place = true, consent = false}");
        assertTrue(walls.changes());
        assertFalse(walls.consent());
        assertFalse(spec(TO + ", costs = {dig = false, place = false}").changes());
    }

    @Test
    void cellsBoxesClustersAndCellsGoIntoThePositionTable() {
        RouteSpec s = spec(TO + ", avoid_break = {x = 1, y = 2, z = 3}, avoid_place = {{{x = 10, y = 64, z = 10}, "
                + "{x = 14, y = 68, z = 14}}}, avoid_step = {blocks = {{name = \"minecraft:stone\", pos = {x = -5, "
                + "y = 60, z = -7}}, {name = \"minecraft:stone\", pos = {x = -6, y = 60, z = -7}}}, count = 2}");
        assertTrue(s.positions().forbids(Use.DIG, new BlockPos(1, 2, 3).asLong()));
        assertFalse(s.positions().forbids(Use.PLACE, new BlockPos(1, 2, 3).asLong()));
        assertTrue(s.positions().forbids(Use.PLACE, new BlockPos(12, 66, 11).asLong()));
        assertTrue(s.positions().forbids(Use.PLACE, new BlockPos(14, 68, 14).asLong()), "盒子含两头");
        assertFalse(s.positions().forbids(Use.PLACE, new BlockPos(15, 64, 10).asLong()));
        assertFalse(s.positions().forbids(Use.DIG, new BlockPos(12, 64, 12).asLong()), "只进写了它的那一栏");
        assertTrue(s.positions().forbids(Use.STAND, new BlockPos(-6, 60, -7).asLong()), "一团的每一格");
        assertTrue(s.bans().isEmpty());
        RouteSpec cells = spec(TO + ", avoid = {{x = 0, y = 60, z = 0}, {x = 0, y = 61, z = 0}, {x = 5, y = 60, z = 5}}");
        assertTrue(cells.positions().forbids(Use.PASS, new BlockPos(5, 60, 5).asLong()), "一串格(Cells)逐格进去");
        assertFalse(cells.positions().forbids(Use.PASS, new BlockPos(2, 60, 2).asLong()), "三格不是盒子");
        RouteSpec blocks = spec(TO + ", avoid = {{name = \"minecraft:stone\", pos = {x = 0, y = 60, z = 0}}, "
                + "{name = \"minecraft:stone\", pos = {x = 4, y = 60, z = 4}}}");
        assertFalse(blocks.positions().forbids(Use.PASS, new BlockPos(2, 60, 2).asLong()), "两个 Block 是两格,不是盒子");
        assertTrue(blocks.positions().forbids(Use.PASS, new BlockPos(4, 60, 4).asLong()));
    }

    /** avoid 里的一格、一个盒子是不进入:身体不占它、脚下不踩它;格子种类照旧并列写在同一串里。 */
    @Test
    void avoidingAPlaceKeepsTheBodyOutOfItAndOffIt() {
        RouteSpec s = spec(TO + ", avoid = {\"water\", {{x = -8, y = 63, z = -8}, {x = -1, y = 63, z = -1}}}");
        assertTrue(s.excludes(Kind.WATER));
        long inFarm = new BlockPos(-3, 63, -3).asLong();
        assertTrue(s.positions().forbids(Use.PASS, inFarm));
        assertTrue(s.positions().forbids(Use.STAND, inFarm));
        assertFalse(s.positions().forbids(Use.DIG, inFarm), "不进入不等于不挖");
        assertFalse(s.positions().forbids(Use.PASS, new BlockPos(-3, 64, -3).asLong()), "盒子上面一格不在盒子里");
        RouteSpec one = spec(TO + ", avoid = {x = 4, y = 5, z = 6}");
        assertTrue(one.positions().forbids(Use.PASS, new BlockPos(4, 5, 6).asLong()), "一格也可以单独写");
    }

    /** 四百万格的盒子整片交给寻路:当场读完,判定一格照样对。 */
    @Test
    void aFourMillionCellBoxIsHandedOverWholeNotCellByCell() {
        long start = System.nanoTime();
        RouteSpec s = spec(TO + ", avoid = {{{x = -80, y = -40, z = -80}, {x = 79, y = 119, z = 79}}}");
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(s.positions().forbids(Use.PASS, new BlockPos(79, 119, 79).asLong()));
        assertTrue(s.positions().forbids(Use.STAND, new BlockPos(-80, -40, -80).asLong()));
        assertFalse(s.positions().forbids(Use.PASS, new BlockPos(80, 0, 0).asLong()));
        assertTrue(millis < 1000, "读一个四百万格的盒子用了 " + millis + " ms");
    }

    /** 方块 id 是按种类的禁令;格子与它们写在同一串里。 */
    @Test
    void blockIdsBecomeKindBans() {
        RouteSpec s = spec(TO + ", avoid_break = {\"minecraft:chest\", \"oak_log\"}, avoid_place = \"water\", "
                + "avoid_step = {\"minecraft:farmland\", {x = 4, y = 5, z = 6}}");
        assertTrue(s.bans().breaking().contains(Blocks.CHEST));
        assertTrue(s.bans().breaking().contains(Blocks.OAK_LOG));
        assertFalse(s.bans().breaking().contains(Blocks.STONE));
        assertTrue(s.bans().placingInto().contains(Blocks.WATER));
        assertTrue(s.bans().standingOn().contains(Blocks.FARMLAND));
        assertTrue(s.positions().forbids(Use.STAND, new BlockPos(4, 5, 6).asLong()));
    }

    /** 终点在最后、总要停下;途经点默认路过;一站的形状写错了是参数错。 */
    @Test
    void stopsComeBeforeTheDestinationAndPassByDefault() {
        Description d = read(TO + ", arrive = \"near\", stops = {{to = {x = 9, z = 9}}, {to = {x = 5, y = 6, z = 7}, "
                + "type = \"stop\", arrive = \"away\", range = 12}}");
        assertEquals(3, d.stops().size());
        Stop first = d.stops().get(0);
        assertInstanceOf(Target.Column.class, first.to());
        assertTrue(first.through(), "途经点默认路过");
        Stop second = d.stops().get(1);
        assertFalse(second.through());
        assertEquals(Stop.Arrive.AWAY, second.arrive());
        assertEquals(12, second.range());
        Stop last = d.stops().get(2);
        assertEquals(Stop.Arrive.NEAR, last.arrive());
        assertEquals(Stop.DEFAULT_NEAR, last.range());
        assertFalse(last.through(), "终点总要停下");
        Description onlyStops = read("stops = {{to = {x = 1, y = 2, z = 3}}}");
        assertFalse(onlyStops.stops().get(0).through(), "没写 to 时最后一个途经点就是终点");
        assertSame(Description.Mode.WALK, onlyStops.mode());
    }

    /** 去处收 Pos、带 pos 的表、实体、一堆格子、一列、一个高度。 */
    @Test
    void aDestinationIsAnyPlaceShape() {
        assertInstanceOf(Target.Cell.class, read("to = {name = \"minecraft:stone\", pos = {x = 1, y = 2, z = 3}}")
                .stops().get(0).to());
        assertInstanceOf(Target.Mob.class, read("to = {id = 184, type = \"minecraft:zombie\", pos = {x = 1, y = 2, "
                + "z = 3}}").stops().get(0).to());
        Target cluster = read("to = {blocks = {{name = \"minecraft:iron_ore\", pos = {x = 1, y = 2, z = 3}}, "
                + "{name = \"minecraft:iron_ore\", pos = {x = 1, y = 3, z = 3}}}, count = 2}, arrive = \"dig\"")
                .stops().get(0).to();
        assertEquals(2, assertInstanceOf(Target.Cells.class, cluster).cells().size(), "一团(Cluster)");
        Target cells = read("to = {{x = 1, y = 2, z = 3}, {x = 1, y = 3, z = 3}, {x = 2, y = 3, z = 3}}")
                .stops().get(0).to();
        assertEquals(3, assertInstanceOf(Target.Cells.class, cells).cells().size(), "一串格(Cells)");
        assertInstanceOf(Target.Height.class, read("to = {y = 30}").stops().get(0).to());
    }

    @Test
    void mistakesAreTaught() {
        assertTrue(error(TO + ", costs = {dig = \"yes\"}").contains("costs.dig is a number, true or false"));
        assertTrue(error(TO + ", costs = {digs = true}").contains("'digs' is not one of them"));
        assertTrue(error(TO + ", costs = {consent = 0.5}").contains("costs.consent must be 1 to"));
        assertTrue(error(TO + ", costs = {fall = -2}").contains("costs.fall must be 0 to"));
        assertTrue(error(TO + ", costs = {parkour = \"yes\"}").contains("costs.parkour is true or false"));
        assertTrue(error(TO + ", avoid = \"swamp\"").contains("'swamp' is not a cell type"));
        assertTrue(error(TO + ", allow = {\"lava\"}").contains("expected one of flowing_water, trigger, fragile"),
                "伤身的种类放不开");
        assertTrue(error(TO + ", range = 3").contains("range = 3 only goes with arrive = \"near\""));
        assertTrue(error("to = {y = 30}, arrive = \"use\"").contains("a height {y = …} is a level"));
        assertTrue(error("to = {x = 1, z = 2}, arrive = \"dig\"").contains("names one block"));
        assertTrue(error("costs = {dig = true}").contains("give to = the destination"));
        assertTrue(error("arrive = \"near\"").contains("arrive and range go with to"));
        assertTrue(error("to = \"ores\"").contains("a place is a table"), "区域名不是去处");
        assertTrue(error(TO + ", stops = {{at = {x = 1, y = 2, z = 3}}}").contains("'at' is not one of them"));
        assertTrue(error(TO + ", mode = \"boat\", arrive = \"use\"").contains("mode = \"boat\" sails"));
        assertTrue(error(TO + ", materials = {\"minecraft:diamond\"}").contains("'minecraft:diamond' is not a block"));
    }

    @Test
    void unknownBlocksAndEmptyTagsAreErrors() {
        assertTrue(error(TO + ", avoid_break = \"minecraft:no_such_block\"").contains("unknown block"));
        assertTrue(error(TO + ", avoid_step = \"#minecraft:no_such_tag\"").contains("tag '#minecraft:no_such_tag' "
                + "has no blocks"));
    }

    /** 带回给她的描述是她写的那张表。 */
    @Test
    void theDescriptionIsHandedBackAsWritten() {
        Description d = read(TO + ", costs = {dig = true}, avoid = {\"water\"}");
        assertEquals(Set.of("to", "costs", "avoid"), ((Map<?, ?>) LuaCodecs.encode(d.written())).keySet());
    }
}
