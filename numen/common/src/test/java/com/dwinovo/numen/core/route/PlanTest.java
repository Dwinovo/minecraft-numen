package com.dwinovo.numen.core.route;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 一份计划:与承诺比较、承诺写进规格、第一段走不通的是哪一段、走不走得通。 */
class PlanTest {

    private static final Stop HOME = Stop.of(new Target.Cell(new BlockPos(120, 64, -35)), null, null, false);

    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** 一段走得到的路,要挖 {@code digs}、要放 {@code places},其中 {@code asks} 要问主人。 */
    private static Plan.Leg reached(Map<BlockPos, Block> digs, Map<BlockPos, Block> places, Map<BlockPos, String> asks) {
        return new Plan.Leg(HOME, null, RouteSpec.defaults(), Plan.Reach.REACHED, null, null,
                new NavText.Changes(digs, places, asks), "");
    }

    private static Plan.Leg reach(Plan.Reach reach, String why) {
        return new Plan.Leg(HOME, null, RouteSpec.defaults(), reach, null, null,
                new NavText.Changes(Map.of(), Map.of(), Map.of()), why);
    }

    private static Plan plan(BlockPos from, Plan.Leg... legs) {
        return new Plan("p1", null, from, List.of(legs));
    }

    private static Map<BlockPos, Block> dirt(int... xs) {
        Map<BlockPos, Block> out = new LinkedHashMap<>();
        for (int x : xs) {
            out.put(new BlockPos(x, 64, 0), Blocks.DIRT);
        }
        return out;
    }

    /** 比承诺:要挖、要放、要问的格各自比;少了不算超出,多了哪一格就报哪一格。 */
    @Test
    void aPlanGoesBeyondThePromiseOnlyByTheCellsItAdds() {
        BlockPos asked = new BlockPos(3, 64, 0);
        Plan promise = plan(BlockPos.ZERO, reached(dirt(3, 4), Map.of(), Map.of(asked, "placed by a player")));
        Plan fewer = plan(new BlockPos(2, 64, 0), reached(dirt(4), Map.of(), Map.of()));
        assertTrue(fewer.beyond(promise).isEmpty());
        Plan more = plan(BlockPos.ZERO, reached(dirt(4, 9), Map.of(new BlockPos(4, 63, 0), Blocks.COBBLESTONE),
                Map.of(asked, "placed by a player", new BlockPos(9, 64, 0), "placed by a player")));
        Plan.Difference diff = more.beyond(promise);
        assertEquals(List.of(new Plan.Cell(new BlockPos(9, 64, 0), Blocks.DIRT)), diff.digs());
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
        Plan plan = plan(BlockPos.ZERO, reached(dirt(3), Map.of(new BlockPos(4, 63, 0), Blocks.COBBLESTONE), Map.of()));
        RouteSpec bound = plan.bind(RouteSpec.defaults().edit().changes(true).consent(false).build());
        assertFalse(bound.positions().forbids(Use.DIG, new BlockPos(3, 64, 0).asLong()));
        assertTrue(bound.positions().forbids(Use.DIG, new BlockPos(5, 64, 0).asLong()));
        assertFalse(bound.positions().forbids(Use.PLACE, new BlockPos(4, 63, 0).asLong()));
        assertTrue(bound.positions().forbids(Use.PLACE, new BlockPos(3, 64, 0).asLong()));
        assertFalse(bound.positions().forbids(Use.STAND, new BlockPos(5, 64, 0).asLong()), "站与经过不受承诺管");
        RouteSpec none = plan(BlockPos.ZERO, reached(Map.of(), Map.of(), Map.of())).bind(RouteSpec.defaults());
        assertTrue(none.positions().forbids(Use.DIG, new BlockPos(3, 64, 0).asLong()));
        assertTrue(none.positions().forbids(Use.PLACE, new BlockPos(3, 64, 0).asLong()));
    }

    /** 计划里第一段走不通的是哪一段;没看清、没规划的不算走不通,计划照样 ok,走的时候边走边算。 */
    @Test
    void theFirstUnreachableLegIsFoundAndOnlyItMakesThePlanNotOk() {
        Plan partial = plan(BlockPos.ZERO, reached(Map.of(), Map.of(), Map.of()), reach(Plan.Reach.PARTIAL, "budget"),
                reach(Plan.Reach.UNPLANNED, ""));
        assertEquals(-1, partial.unreachable());
        assertTrue(partial.ok());
        assertEquals("", partial.why());
        Plan blocked = plan(BlockPos.ZERO, reached(Map.of(), Map.of(), Map.of()), reach(Plan.Reach.UNREACHABLE,
                "no way"), reach(Plan.Reach.UNPLANNED, ""));
        assertEquals(1, blocked.unreachable());
        assertFalse(blocked.ok());
        assertEquals("no way", blocked.why());
    }

    /** 一段程序里的计划:换了一段程序再规划,上一段的全作废;编号不重用;别的程序点名也拿不到。 */
    @Test
    void plansLastOnlyWithinTheProgramThatMadeThem() {
        assertEquals("abc", Plans.program("abc#3"));
        assertEquals("line", Plans.program("line"));
        Plans plans = new Plans();
        String first = plans.nextId("one");
        plans.put("one", new Plan(first, null, BlockPos.ZERO, List.of()));
        assertEquals(first, plans.get("one", first).id());
        assertNull(plans.get("two", first), "别的程序拿不到");
        String second = plans.nextId("two");
        assertNotEquals(first, second);
        assertNull(plans.get("one", first), "换了一段程序,上一段的作废");
        assertNull(plans.get("two", first));
    }
}
