package com.dwinovo.numen.core.route;

import java.util.List;

import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.cli.Place;
import com.dwinovo.numen.core.task.move.Destination;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 路线这个名词:存盘往返、改路段、计划与承诺的比较、承诺写进规格。 */
class RouteDataTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final Destination.Stop HOME = new Destination.Stop(120, 64, -35, Destination.Arrive.AT, null);
    private static final Destination.Stop BRIDGE = new Destination.Stop(100, 70, -20, Destination.Arrive.NEAR, 3);
    private static final Destination.Stop HILL = new Destination.Stop(90, null, -10, Destination.Arrive.AT, null);
    private static final Destination.Stop ORES = new Destination.Stop(null, null, null, AreaRef.parse("ores/g3"),
            Destination.Arrive.NEAR, 3);

    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static Plan.Leg walkable(List<Plan.Cell> digs, List<Plan.Cell> places, List<Plan.Ask> asks) {
        return new Plan.Leg(Plan.Reach.WALKABLE, 12, 60, new BlockPos(1, 64, 1), digs, places, asks, "", List.of());
    }

    private static Plan.Cell dirt(int x) {
        return new Plan.Cell(new BlockPos(x, 64, 0), Blocks.DIRT);
    }

    /** 存下去再读回来是同一条:途经点(连同空着的坐标、区域的名字与到达方式)、每段的标志、计划的每一段(连同要潜的水)、走过的记录。 */
    @Test
    void aRouteReadsBackAsItWasSaved() {
        Plan plan = new Plan(new BlockPos(0, 64, 0), 1200, List.of(
                walkable(List.of(dirt(3)), List.of(new Plan.Cell(new BlockPos(4, 63, 0), Blocks.COBBLESTONE)),
                        List.of(new Plan.Ask(new BlockPos(3, 64, 0), "placed by a player"))),
                new Plan.Leg(Plan.Reach.PARTIAL, 40, 200, new BlockPos(50, 64, 0), List.of(), List.of(), List.of(),
                        "the search reached chunks that are not loaded",
                        List.of(new Plan.Dive(new BlockPos(10, 60, 0), new BlockPos(20, 60, 0), 180, 90))),
                Plan.Leg.unplanned()));
        Itinerary route = Itinerary.of("home", OVERWORLD, HOME, "--alter natural --avoid_break area:house")
                .via(BRIDGE, 1).via(ORES, 2).withLegFlags(2, "--avoid water area:farm").planned(plan)
                .walked(new Itinerary.Walk("Aria", 1300, 412, true, "arrived"));
        Routes routes = new Routes();
        routes.put(route);
        routes.put(Itinerary.of("mine", OVERWORLD, HILL, ""));
        CompoundTag saved = routes.save(new CompoundTag(), null);
        Routes loaded = Routes.load(saved, null);
        assertEquals(route, loaded.get("home"));
        assertEquals(List.of("home", "mine"), loaded.all().stream().map(Itinerary::name).toList());
        assertNull(loaded.get("mine").plan());
    }

    /** 插途经点、删途经点、改一段的标志:一段认的是它去哪儿,别的段的标志跟着它走;改意图丢掉计划。 */
    @Test
    void editingTheLegsKeepsEachLegsFlagsWithItsStopAndDropsThePlan() {
        Itinerary route = Itinerary.of("home", OVERWORLD, HOME, "").withLegFlags(1, "--avoid water")
                .planned(new Plan(BlockPos.ZERO, 0, List.of(Plan.Leg.unplanned())));
        Itinerary via = route.via(BRIDGE, 1);
        assertNull(via.plan());
        assertEquals(List.of(BRIDGE, HOME), via.legs().stream().map(Itinerary.Leg::to).toList());
        assertEquals(List.of("", "--avoid water"), via.legs().stream().map(Itinerary.Leg::flags).toList());
        Itinerary longer = via.via(HILL, 3);
        assertEquals(HILL, longer.destination(), "比途经点数多一就是新的终点");
        assertEquals(List.of(BRIDGE, HILL), longer.dropVia(2).legs().stream().map(Itinerary.Leg::to).toList());
        assertThrows(IllegalArgumentException.class, () -> via.dropVia(2), "终点删不掉");
        assertThrows(IllegalArgumentException.class, () -> via.via(HILL, 4));
        assertThrows(IllegalArgumentException.class, () -> via.withLegFlags(3, "--parkour true"));
        assertEquals(List.of(HOME), via.dropVia(1).legs().stream().map(Itinerary.Leg::to).toList());
    }

    /** 反着来一条:途经点倒过来,终点是上次规划的起点;每段的标志跟着它那一截换过去;没规划过就不知道起点。 */
    @Test
    void aReversedRouteRunsBackToWhereItWasPlannedFrom() {
        Itinerary route = Itinerary.of("mine", OVERWORLD, HOME, "--alter natural").via(BRIDGE, 1)
                .withLegFlags(1, "--avoid water").withLegFlags(2, "--parkour true");
        assertThrows(IllegalArgumentException.class, () -> route.reversed("back"));
        Itinerary planned = route.planned(new Plan(new BlockPos(5, 64, 6), 0,
                List.of(Plan.Leg.unplanned(), Plan.Leg.unplanned())));
        Itinerary back = planned.reversed("back");
        assertEquals("back", back.name());
        assertEquals(List.of(BRIDGE, new Destination.Stop(5, 64, 6, Destination.Arrive.AT, null)),
                back.legs().stream().map(Itinerary.Leg::to).toList());
        assertEquals(List.of("--parkour true", "--avoid water"), back.legs().stream().map(Itinerary.Leg::flags).toList());
        assertEquals("--alter natural", back.flags());
        assertNull(back.plan());
        assertTrue(back.walks().isEmpty());
    }

    /**
     * 一处({@link Place},写法在命令行一处读)加到达方式编成去处:坐标三个一格、两个一列、一个一个高度;区域名是一块区域。
     * {@code --arrive near} 不写 {@code --near} 停在 {@link Destination#DEFAULT_NEAR} 格内;形状不成立的当场说。
     */
    @Test
    void aPlaceAndAnArrivalMakeAStop() {
        assertEquals(HOME, Destination.Stop.of(new Place(120, 64, -35, null), null, null));
        assertEquals(HILL, Destination.Stop.of(new Place(90, null, -10, null), "at", null));
        assertEquals(new Destination.Stop(null, 12, null, Destination.Arrive.AT, null),
                Destination.Stop.of(new Place(null, 12, null, null), null, null));
        assertEquals(ORES, Destination.Stop.of(Place.area(AreaRef.parse("ores/g3")), "near", 3));
        assertEquals(new Place(120, 64, -35, null), HOME.place(), "去处写回的就是那一处");
        Destination.Stop chests = Destination.Stop.of(Place.area(AreaRef.parse("chests")), "use", null);
        assertEquals("area chests (to use one of its blocks)", chests.words(), "区域的 use 不要 y");
        assertEquals("area ores/g3 (within 3)", ORES.words());
        assertEquals(Destination.DEFAULT_NEAR, Destination.Stop.of(Place.area(AreaRef.parse("farm")), "near", null)
                .near(), "arrive near 不写 near 有默认");
        assertThrows(IllegalArgumentException.class, () -> new Destination.Stop(1, 2, 3, AreaRef.parse("farm"),
                Destination.Arrive.AT, null), "坐标与区域不能同时给");
        IllegalArgumentException nearAlone = assertThrows(IllegalArgumentException.class,
                () -> Destination.Stop.of(Place.area(AreaRef.parse("farm")), null, 2));
        assertTrue(nearAlone.getMessage().startsWith("near = 2 only goes with arrive = \"near\""),
                nearAlone.getMessage());
    }

    /** 走过的记录只留最近几条。 */
    @Test
    void onlyTheLatestWalksAreKept() {
        Itinerary route = Itinerary.of("home", OVERWORLD, HOME, "");
        for (int i = 0; i < Itinerary.WALKS_KEPT + 3; i++) {
            route = route.walked(new Itinerary.Walk("Aria", i, 10, true, "arrived"));
        }
        assertEquals(Itinerary.WALKS_KEPT, route.walks().size());
        assertEquals(3, route.walks().get(0).at());
    }

    /** 名字守名字的规矩;路线至少有终点。 */
    @Test
    void aRouteHasAProperNameAndADestination() {
        assertThrows(IllegalArgumentException.class, () -> Itinerary.of("Home Base", OVERWORLD, HOME, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new Itinerary("home", OVERWORLD, List.of(), "", null, List.of()));
        assertEquals("goto-aria_2", Itinerary.gotoOf("Aria_2"));
    }

    /** 比承诺:要挖、要放、要问的格各自比;少了不算超出,多了哪一格就报哪一格。 */
    @Test
    void aPlanGoesBeyondThePromiseOnlyByTheCellsItAdds() {
        Plan.Ask asked = new Plan.Ask(new BlockPos(3, 64, 0), "placed by a player");
        Plan promise = new Plan(BlockPos.ZERO, 0, List.of(walkable(List.of(dirt(3), dirt(4)), List.of(),
                List.of(asked))));
        Plan fewer = new Plan(new BlockPos(2, 64, 0), 10, List.of(walkable(List.of(dirt(4)), List.of(), List.of())));
        assertTrue(fewer.beyond(promise).isEmpty());
        Plan more = new Plan(BlockPos.ZERO, 10, List.of(walkable(List.of(dirt(4), dirt(9)),
                List.of(new Plan.Cell(new BlockPos(4, 63, 0), Blocks.COBBLESTONE)),
                List.of(asked, new Plan.Ask(new BlockPos(9, 64, 0), "placed by a player")))));
        Plan.Difference diff = more.beyond(promise);
        assertEquals(List.of(dirt(9)), diff.digs());
        assertEquals(1, diff.places().size());
        assertEquals(List.of(new BlockPos(9, 64, 0)), diff.asks().stream().map(Plan.Ask::pos).toList());
        assertFalse(diff.isEmpty());
        String said = RouteText.beyond(diff);
        assertTrue(said.contains("break 1 dirt (9,64,0)") && said.contains("place 1 cobblestone (4,63,0)")
                && said.contains("ask your owner about 1 cell(s) (9,64,0)"), said);
    }

    /** 承诺写进规格:只许挖、只许放计划里的格,别的格一律不许;一格都不改的计划就是一格都不许。 */
    @Test
    void thePromiseBindsDigsAndPlacesToThePlannedCells() {
        Plan plan = new Plan(BlockPos.ZERO, 0, List.of(walkable(List.of(dirt(3)),
                List.of(new Plan.Cell(new BlockPos(4, 63, 0), Blocks.COBBLESTONE)), List.of())));
        RouteSpec bound = plan.bind(RouteSpec.defaults().edit().changes(true).consent(false).build());
        assertFalse(bound.positions().forbids(Use.DIG, new BlockPos(3, 64, 0).asLong()));
        assertTrue(bound.positions().forbids(Use.DIG, new BlockPos(5, 64, 0).asLong()));
        assertFalse(bound.positions().forbids(Use.PLACE, new BlockPos(4, 63, 0).asLong()));
        assertTrue(bound.positions().forbids(Use.PLACE, new BlockPos(3, 64, 0).asLong()));
        assertFalse(bound.positions().forbids(Use.STAND, new BlockPos(5, 64, 0).asLong()), "站与经过不受承诺管");
        RouteSpec none = new Plan(BlockPos.ZERO, 0, List.of(walkable(List.of(), List.of(), List.of())))
                .bind(RouteSpec.defaults());
        assertTrue(none.positions().forbids(Use.DIG, new BlockPos(3, 64, 0).asLong()));
        assertTrue(none.positions().forbids(Use.PLACE, new BlockPos(3, 64, 0).asLong()));
    }

    /** 计划里第一段走不通的是哪一段;没看清的不算走不通。 */
    @Test
    void theFirstUnwalkableLegIsFound() {
        Plan partial = new Plan(BlockPos.ZERO, 0, List.of(walkable(List.of(), List.of(), List.of()),
                new Plan.Leg(Plan.Reach.PARTIAL, 3, 9, null, List.of(), List.of(), List.of(), "budget", List.of()),
                Plan.Leg.unplanned()));
        assertEquals(-1, partial.unreachable());
        Plan blocked = new Plan(BlockPos.ZERO, 0, List.of(walkable(List.of(), List.of(), List.of()),
                Plan.Leg.unreachable("no way"), Plan.Leg.unplanned()));
        assertEquals(1, blocked.unreachable());
    }
}
