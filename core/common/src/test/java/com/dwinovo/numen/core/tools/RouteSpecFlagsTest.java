package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.area.Area;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.core.CoreScripts;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.core.nav.NamedAreas;
import com.dwinovo.numen.pathing.spec.BlockBans;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Semantics.Kind;
import com.dwinovo.numen.task.TaskResult;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路线规格的标志到 {@link RouteSpec} 的翻译:每个标志落到规格的哪一项,坐标格与区域进位置表(区域整块交给寻路、不逐格展开),
 * 以及每种写错都报教学式错误——写法上的错由参数类型在解析时报,意思不成立的(方块不存在、区域不在)由翻译报。标志从
 * {@code command} 工具一整行进来,和她写的一样;方块 id 与标签那几条需要 MC 注册表。
 */
@Tag("mc")
class RouteSpecFlagsTest {

    /** 夹具动作的默认规格:翻译叠在它上面。 */
    private static final AtomicReference<RouteSpec> BASE = new AtomicReference<>(RouteSpec.defaults());
    private static final AtomicReference<RouteSpec> LAST = new AtomicReference<>();

    /** 主人名下的区域:一间房子(一个盒子,两部分)、一块农田、一片几百万格的营地,都在主世界;还有一块在下界的。 */
    private static final Area HOUSE = Area.of(Level.OVERWORLD, Area.Kind.BOX,
            Cells.box(new BlockPos(10, 64, 10), new BlockPos(14, 68, 14)))
            .with(Area.Kind.BOX, Cells.box(new BlockPos(12, 64, 9), new BlockPos(12, 65, 9)));
    private static final Area FARM = Area.of(Level.OVERWORLD, Area.Kind.BOX,
            Cells.box(new BlockPos(-8, 63, -8), new BlockPos(-1, 63, -1)));
    private static final Area CAMP = Area.of(Level.OVERWORLD, Area.Kind.BOX,
            Cells.box(new BlockPos(-80, -40, -80), new BlockPos(79, 119, 79)));
    private static final Area PORTAL = Area.of(Level.NETHER, Area.Kind.POINT, Cells.point(new BlockPos(0, 70, 0)));
    private static final NamedAreas AREAS = new NamedAreas(Level.OVERWORLD,
            Map.of("house", HOUSE, "farm", FARM, "camp", CAMP, "portal", PORTAL));

    @BeforeAll
    static void boot() {
        // 程序从她的入口跑,要整份登记处(各组、库、跑脚本的工具);这一组是装好之后加的测试组
        com.dwinovo.numen.core.CoreCommandsFixture.install();
        AtomicReference<NumenApi> door = new AtomicReference<>();
        NumenPlugins.register(door::set);
        door.get().registerCommands("gt_route", "Test fixture: route flags read into a spec.", g ->
                g.server("plan", "Read the route flags.", (src, args) -> {
                    LAST.set(RouteSpecFlags.parse(args, BASE.get(), AREAS));
                    src.reply(TaskResult.ok("read").toJson());
                }, RouteSpecFlags.PARAMS.toArray(Param<?>[]::new))
                        .returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING)
                        .example("gt_route.plan({alter = \"natural\", avoid = {\"water\"}})"));
    }

    /** 选项表交给这一组的函数,和她写的一样;跑完返回读出的规格。 */
    private static RouteSpec spec(String options) {
        CoreScripts.Run run = run(options);
        assertTrue(run.ok(), run.message());
        return LAST.get();
    }

    /** 选项写错了:程序停在那一行,返回那次调用的报错。 */
    private static String error(String options) {
        CoreScripts.Run run = run(options);
        assertFalse(run.ok(), options + " should fail");
        String message = run.message();
        String error = message.substring(message.indexOf("gt_route.plan: ") + "gt_route.plan: ".length());
        assertTrue(error.startsWith("bad_argument — "), "规格写错是参数错: " + error);
        return error.substring("bad_argument — ".length());
    }

    private static CoreScripts.Run run(String options) {
        LAST.set(null);
        return CoreScripts.run(UUID.randomUUID(), "gt_route.plan({" + options + "})");
    }

    /** mine 的规格叠在它自己的默认上:没写的保持默认,写了的覆盖,禁令并进默认已有的。 */
    @Test
    void flagsLayOverTheCallersDefault() {
        RouteSpec base = RouteSpec.defaults().edit().changes(true)
                .bans(new BlockBans(Set.of(Blocks.CHEST), Set.of(), Set.of())).build();
        BASE.set(base);
        try {
            assertSame(base, spec(""));
            RouteSpec kept = spec("avoid_break = {{x = 1, y = 2, z = 3}, \"minecraft:oak_log\"}");
            assertTrue(kept.changes() && kept.consent());
            assertTrue(kept.positions().forbids(Use.DIG, new BlockPos(1, 2, 3).asLong()));
            assertTrue(kept.bans().breaking().contains(Blocks.CHEST));
            assertTrue(kept.bans().breaking().contains(Blocks.OAK_LOG));
            RouteSpec natural = spec("alter = \"natural\"");
            assertTrue(natural.changes() && !natural.consent());
        } finally {
            BASE.set(RouteSpec.defaults());
        }
    }

    @Test
    void noFlagsIsTheFactorySpec() {
        RouteSpec s = spec("");
        assertSame(RouteSpec.defaults(), s);
        assertFalse(s.changes());
        assertTrue(s.positions().isEmpty());
        assertTrue(s.bans().isEmpty());
    }

    @Test
    void everyFlagLandsOnItsField() {
        RouteSpec s = spec("alter = \"natural\", avoid = {\"water\", \"door\"}, allow = \"trigger\", penalty_place = 5, penalty_break = 7.5, penalty_jump = 9, penalty_wade = 0, parkour = true, max_fall = 6, alter_budget = 4");
        assertTrue(s.changes() && !s.consent());
        RouteSpec any = spec("alter = \"any\"");
        assertTrue(any.changes() && any.consent());
        assertTrue(s.excludes(Kind.WATER));
        assertTrue(s.excludes(Kind.DOOR));
        assertFalse(s.excludes(Kind.CLIMBABLE));
        assertFalse(s.excludes(Kind.TRIGGER), "放开了的出厂排除");
        assertTrue(s.excludes(Kind.FRAGILE), "没放开的出厂排除照旧");
        assertEquals(5.0, s.placeCost());
        assertEquals(7.5, s.breakPenalty());
        assertEquals(9.0, s.jumpPenalty());
        assertEquals(0.0, s.wadePenalty());
        assertTrue(s.parkour());
        assertEquals(6, s.maxFallHeightNoWater());
        assertEquals(4, s.alterBudget());
    }

    @Test
    void cellsAndAreasGoIntoThePositionTable() {
        RouteSpec s = spec("avoid_break = {x = 1, y = 2, z = 3}, avoid_place = \"area:house\", avoid_step = {x = -5, y = 60, z = -7}");
        assertTrue(s.positions().forbids(Use.DIG, new BlockPos(1, 2, 3).asLong()));
        assertFalse(s.positions().forbids(Use.PLACE, new BlockPos(1, 2, 3).asLong()));
        HOUSE.cells().forEach((x, y, z, seen) -> assertTrue(s.positions().forbids(Use.PLACE, BlockPos.asLong(x, y, z)),
                x + "," + y + "," + z));
        assertFalse(s.positions().forbids(Use.PLACE, new BlockPos(15, 64, 10).asLong()));
        assertFalse(s.positions().forbids(Use.DIG, new BlockPos(12, 64, 12).asLong()), "区域只进写了它的那一栏");
        assertTrue(s.positions().forbids(Use.STAND, new BlockPos(-5, 60, -7).asLong()));
        assertTrue(s.bans().isEmpty());
    }

    /** 一部分只管那一部分:house/b2 是门口那两格。 */
    @Test
    void aPartOfAnAreaIsOnlyThatPart() {
        RouteSpec s = spec("avoid_break = \"area:house/b2\"");
        assertTrue(s.positions().forbids(Use.DIG, new BlockPos(12, 65, 9).asLong()));
        assertFalse(s.positions().forbids(Use.DIG, new BlockPos(12, 65, 10).asLong()));
    }

    /** --avoid area:farm 是不进入:身体不占它的格、脚下不踩它的格;格子种类照旧并列写在同一串里。 */
    @Test
    void avoidingAnAreaKeepsTheBodyOutOfItAndOffIt() {
        RouteSpec s = spec("avoid = {\"water\", \"area:farm\"}");
        assertTrue(s.excludes(Kind.WATER));
        long inFarm = new BlockPos(-3, 63, -3).asLong();
        assertTrue(s.positions().forbids(Use.PASS, inFarm));
        assertTrue(s.positions().forbids(Use.STAND, inFarm));
        assertFalse(s.positions().forbids(Use.DIG, inFarm), "不进入不等于不挖");
        assertFalse(s.positions().forbids(Use.PASS, new BlockPos(-3, 64, -3).asLong()), "区域上面一格不在区域里");
    }

    /**
     * 四百万格的营地整块交给寻路:翻译当场回来,判定一格照样对。逐格展开进集合的话光是装格子就要几百兆、好几秒。
     */
    @Test
    void aFourMillionCellAreaIsHandedOverWholeNotCellByCell() {
        long start = System.nanoTime();
        RouteSpec s = spec("avoid_break = \"area:camp\", avoid = \"area:camp\"");
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(s.positions().forbids(Use.DIG, new BlockPos(79, 119, 79).asLong()));
        assertTrue(s.positions().forbids(Use.PASS, new BlockPos(-80, -40, -80).asLong()));
        assertFalse(s.positions().forbids(Use.DIG, new BlockPos(80, 0, 0).asLong()));
        assertTrue(millis < 1000, "翻译一块四百万格的区域用了 " + millis + " ms");
    }

    /** 点名的区域不在、部分不在、在别的维度:当场说出事实与主人有哪些区域;写法不对由参数类型报。 */
    @Test
    void missingAreasAreNamedWithWhatThereIs() {
        String gone = error("avoid_break = \"area:shed\"");
        assertTrue(gone.contains("avoid_break area:shed: there is no area named shed; your owner's areas are camp, "
                + "farm, house, portal"), gone);
        String noPart = error("avoid = \"area:house/b7\"");
        assertTrue(noPart.contains("area house has no part b7; its parts are b1, b2"), noPart);
        String elsewhere = error("avoid_step = \"area:portal\"");
        assertTrue(elsewhere.contains("area portal lies in minecraft:the_nether, and I am in minecraft:overworld"),
                elsewhere);
        assertTrue(error("avoid_break = \"area:Shed\"").contains("area names are lowercase letters"));
        assertTrue(error("avoid_break = \"1,2,3..4,5,6\"").contains("a box or any other stretch of cells is an area"),
                "盒子写法没有了,报错指向区域");
    }

    @Test
    void mistakesAreTaught() {
        assertTrue(error("alter = \"maybe\"").startsWith("argument 'alter': expected one of none, natural, any"));
        assertTrue(error("avoid = \"swamp\"").contains("expected one of water, flowing_water, lava, climbable, door"));
        assertTrue(error("avoid = \"DOOR\"").contains("expected one of water, flowing_water, lava, climbable, door"),
                "类型名照帮助里写的小写");
        assertTrue(error("allow = \"lava\"").contains("expected one of flowing_water, trigger, fragile"),
                "伤身的种类放不开");
        assertTrue(error("penalty_jump = -1").contains("penalty_jump must be between 0 and 1000"));
        assertTrue(error("penalty_jump = \"high\"").startsWith("argument 'penalty_jump': Expected double"));
        assertTrue(error("avoid_break = \"1,2\"").startsWith("argument 'avoid_break': expected a cell: three whole "
                + "numbers"));
        assertTrue(error("avoid_step = \"1,two,3\"").contains("three whole numbers"));
        assertTrue(error("max_fall = -2").contains("max_fall must be 0 or more"));
        assertTrue(error("parkour = \"yes\"").contains("parkour"), "开关只收 true 或 false");
    }

    @Test
    void avoidAcceptsOnlyTypesAWalkCanKeepOutOf() {
        RouteSpec s = spec("avoid = {\"water\", \"flowing_water\"}");
        assertTrue(s.excludes(Kind.WATER));
        assertTrue(s.excludes(Kind.FLOWING_WATER));
        // 地面、空气、障碍不是"可以选择不走"的东西,写了就是规格写错了,报错列出能写的
        assertTrue(error("avoid = \"ground\"").contains("expected one of water, flowing_water, lava, climbable, door, "
                + "hazard, falling, trigger, fragile or area:<name>"));
        assertTrue(error("avoid = \"obstacle\"").contains("expected one of"));
        assertTrue(error("avoid = \"lake\"").contains("expected one of"));
    }

    /** 标签要等数据包绑定,无头引导下全空——标签展开只在真机验,这里只钉方块 id。 */
    @Test
    void blockIdsBecomeKindBans() {
        RouteSpec s = spec("avoid_break = {\"minecraft:chest\", \"oak_log\"}, avoid_place = \"water\", "
                + "avoid_step = {\"minecraft:farmland\", {x = 4, y = 5, z = 6}}");
        assertTrue(s.bans().breaking().contains(Blocks.CHEST));
        assertTrue(s.bans().breaking().contains(Blocks.OAK_LOG));
        assertFalse(s.bans().breaking().contains(Blocks.STONE));
        assertTrue(s.bans().placingInto().contains(Blocks.WATER));
        assertTrue(s.bans().standingOn().contains(Blocks.FARMLAND));
        assertTrue(s.positions().forbids(Use.STAND, new BlockPos(4, 5, 6).asLong()));
    }

    @Test
    void unknownBlocksAndEmptyTagsAreErrors() {
        assertTrue(error("avoid_break = \"minecraft:no_such_block\"").contains("avoid_break: unknown block"));
        assertTrue(error("avoid_step = \"#minecraft:no_such_tag\"").contains("avoid_step: tag '#minecraft:no_such_tag' "
                + "has no blocks"));
    }
}
