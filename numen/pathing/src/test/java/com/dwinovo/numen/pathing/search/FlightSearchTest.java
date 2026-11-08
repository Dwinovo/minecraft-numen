package com.dwinovo.numen.pathing.search;

import java.util.EnumSet;
import java.util.Set;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.pathing.Fixtures.search;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 许飞的身体搜出来的路线:走不过去的空隙飞过去、悬在半空的一格飞到停住、近处还是走(价钱定)、已经在飞就从空中出发;
 * 不许飞的身体面对同一道空隙没有路。
 */
class FlightSearchTest {

    private static final int Y = 64;
    private static final BlockPos START = new BlockPos(0, Y, 0);

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    private static TestWorld field() {
        return new TestWorld().floor(-4, -12, 40, 12, Y - 1);
    }

    private static TestWorld canyon() {
        return field().fill(3, Y - 1, -12, 20, Y - 1, 12, Blocks.AIR.defaultBlockState());
    }

    private static Set<MoveKind> kinds(Route route) {
        Set<MoveKind> out = EnumSet.noneOf(MoveKind.class);
        route.legs().forEach(leg -> out.add(leg.maneuver().kind()));
        return out;
    }

    private static CostModel flier(boolean flying) {
        return Fixtures.model(RouteSpec.defaults(), Fixtures.flier(flying));
    }

    @Test
    void aFlierCrossesACanyonThatCannotBeWalkedAndLandsOnTheFarSide() {
        BlockPos far = new BlockPos(24, Y, 0);
        SearchResult walked = search(canyon(), Fixtures.model(RouteSpec.defaults()), START, Goals.at(far));
        assertFalse(walked.arrived(), "不会飞的身体过不了这道空隙");
        SearchResult result = search(canyon(), flier(false), START, Goals.at(far));
        assertTrue(result.arrived(), "会飞的身体飞得过去:" + result.stop());
        Route route = result.route();
        assertEquals(far, route.end());
        assertTrue(route.endStance().grounded(), "降落在对岸");
        Set<MoveKind> used = kinds(route);
        assertTrue(used.contains(MoveKind.TAKEOFF) && used.contains(MoveKind.FLY) && used.contains(MoveKind.LAND), "用到的走法:" + used);
        assertTrue(kinds(route).contains(MoveKind.TAKEOFF), "走到峡谷边上起飞");
        int takeoff = indexOf(route, MoveKind.TAKEOFF);
        int land = indexOf(route, MoveKind.LAND);
        assertTrue(takeoff < land, "先起飞后降落");
        for (int i = takeoff + 1; i < land; i++) {
            assertEquals(MoveKind.FLY, route.legs().get(i).maneuver().kind(), "起降之间都在飞");
        }
    }

    private static int indexOf(Route route, MoveKind kind) {
        for (int i = 0; i < route.legs().size(); i++) {
            if (route.legs().get(i).maneuver().kind() == kind) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void aShortTripOnFlatGroundIsWalkedBecausePriceDecides() {
        SearchResult near = search(field(), flier(false), START, Goals.at(new BlockPos(5, Y, 0)));
        assertTrue(near.arrived());
        assertEquals(EnumSet.of(MoveKind.WALK), kinds(near.route()), "五格走过去,不起飞");
        // 价钱定:起飞与刹车的固定价钱摊到一路上。短途走,够长了飞比走划算(估价的下界与走路的真价差一大截,加权搜索未必挑到,见 AStar.WEIGHT)
        double fixed = com.dwinovo.numen.pathing.world.Kinematics.takeoffTicks(com.dwinovo.numen.pathing.plan.Mobility.VANILLA_FLYING_SPEED)
                + com.dwinovo.numen.pathing.world.Kinematics.flyStopLoss(com.dwinovo.numen.pathing.plan.Mobility.VANILLA_FLYING_SPEED);
        SearchResult far = search(field(), flier(false), START, Goals.at(new BlockPos(38, Y, 0)));
        assertTrue(far.arrived());
        assertTrue(far.route().cost() <= 38 * Fixtures.PACE.sprint() + 1e-6, "不比一路走过去贵:" + far.route().cost() + " 固定价钱 " + fixed);
    }

    @Test
    void aCellInMidAirIsAGoalThatTheFlierReachesAndStopsIn() {
        BlockPos air = new BlockPos(6, Y + 5, 2);
        SearchResult result = search(field(), flier(false), START, Goals.at(air));
        assertTrue(result.arrived(), "半空的一格飞得到:" + result.stop());
        assertEquals(air, result.route().end());
        assertEquals(Stance.Kind.FLYING, result.route().endStance().kind(), "收在空中,不是落地");
        assertFalse(kinds(result.route()).contains(MoveKind.LAND));
        SearchResult walker = search(field(), Fixtures.model(RouteSpec.defaults()), START, Goals.at(air));
        assertFalse(walker.arrived(), "不会飞的身体到不了半空");
    }

    @Test
    void aBodyAlreadyFlyingStartsFromTheAirWithoutTakingOff() {
        BlockPos from = new BlockPos(2, Y + 3, 0);
        SearchResult result = search(canyon(), flier(true), from, Goals.at(new BlockPos(24, Y, 0)));
        assertTrue(result.arrived(), "在飞的身体:" + result.stop());
        Route route = result.route();
        assertEquals(Stance.Kind.FLYING, route.startStance().kind());
        assertFalse(kinds(route).contains(MoveKind.TAKEOFF), "已经在飞,不再起飞");
        assertTrue(kinds(route).contains(MoveKind.LAND), "降落到对岸");
    }

    @Test
    void aFlierFlyingOverAWallLandsOnTopOfIt() {
        TestWorld wall = field().fill(6, Y, -12, 7, Y + 11, 12, Blocks.STONE.defaultBlockState());
        BlockPos top = Goals.standingOn(wall, Vanilla.SURVIVAL, new BlockPos(7, Y + 11, 0));
        SearchResult result = search(wall, flier(false), START, Goals.at(top));
        assertTrue(result.arrived(), "飞上墙顶:" + result.stop());
        assertTrue(kinds(result.route()).contains(MoveKind.FLY));
        assertTrue(kinds(result.route()).contains(MoveKind.LAND));
        assertEquals(top, result.route().end());
    }
}
