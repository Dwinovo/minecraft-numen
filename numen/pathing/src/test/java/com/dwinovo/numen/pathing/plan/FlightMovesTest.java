package com.dwinovo.numen.pathing.plan;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Kinematics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 创造飞行的三种走法(起飞、飞、降落)的前提与价钱,以及走法集由身体给:许飞的身体多出这三种,不许飞的没有。场景摆在 {@code Y - 1} 的石头地板上,
 * 空地上脚在 {@code Y};飞着的节点是空中的格({@link Stance#afloat})。
 */
class FlightMovesTest {

    private static final int Y = 64;
    private static final BlockPos AT = new BlockPos(0, Y, 0);
    private static final Heading EAST = new Heading(1, 0, 0);

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    private static TestWorld ground() {
        return new TestWorld().floor(-6, -6, 12, 6, Y - 1);
    }

    private static CostModel flier() {
        return Fixtures.model(RouteSpec.defaults(), Fixtures.flier(false));
    }

    /** 身体飞着停在 {@code node} 这一格:世界里待不住、放得下身体的空中的格。 */
    private static Stance aloft(TestWorld world, CostModel model, BlockPos node) {
        Stance stance = Stance.afloat(world, model.body().stats(), node.getX(), node.getY(), node.getZ());
        assertNotNull(stance, node + " 不是空中待得住的格");
        return stance;
    }

    private static Premise land(TestWorld world, CostModel model, BlockPos from, Heading heading) {
        return Moves.of(MoveKind.LAND).premise(model, world, from, aloft(world, model, from), heading);
    }

    @Test
    void theMoveSetComesFromTheBody() {
        var walking = Moves.forBody(Fixtures.body());
        var flying = Moves.forBody(Fixtures.flier(false));
        assertFalse(walking.stream().anyMatch(move -> move.kind() == MoveKind.FLY), "不许飞的身体没有飞行的走法");
        assertTrue(flying.stream().map(Move::kind).toList().containsAll(
                java.util.List.of(MoveKind.TAKEOFF, MoveKind.FLY, MoveKind.LAND)));
        assertEquals(walking, flying.subList(0, walking.size()), "走路的走法在表里的位置不因许飞而变");
    }

    @Test
    void flyingMovesDepartFromTheAirAndTheOthersFromTheGround() {
        CostModel model = flier();
        Stance ground = Stance.at(ground(), model.body().stats(), AT);
        Stance air = aloft(ground(), model, AT.above(2));
        assertTrue(Moves.of(MoveKind.TAKEOFF).departsFrom(ground));
        assertTrue(Moves.of(MoveKind.WALK).departsFrom(ground));
        assertFalse(Moves.of(MoveKind.FLY).departsFrom(ground));
        assertFalse(Moves.of(MoveKind.LAND).departsFrom(ground));
        assertTrue(Moves.of(MoveKind.FLY).departsFrom(air));
        assertTrue(Moves.of(MoveKind.LAND).departsFrom(air));
        assertFalse(Moves.of(MoveKind.WALK).departsFrom(air), "飞着的身体不走路");
    }

    @Test
    void anAirCellIsOnlyHoldableByFlying() {
        TestWorld world = ground();
        CostModel model = flier();
        BlockPos up = AT.above(3);
        assertNull(Stance.at(world, model.body().stats(), up), "空中的格站不住");
        assertNull(Stance.at(world, model.body().stats(), up, false), "不在飞的身体待不住");
        assertEquals(Stance.Kind.FLYING, Stance.at(world, model.body().stats(), up, true).kind());
        assertNull(Stance.afloat(world, model.body().stats(), 0, Y - 1, 0), "石头里放不下身体");
        assertNull(Stance.afloat(world, model.body().stats(), 0, Y, 0), "有地托着脚的格是站着,不是飞着");
        assertNull(Stance.afloat(ground().set(AT.above(2), Blocks.STONE.defaultBlockState()), model.body().stats(), 0, Y + 1, 0),
                "头顶压着方块放不下");
    }

    @Test
    void takingOffFromTheGroundGoesOneUpAndPaysForTheTapsAndTheLaunch() {
        CostModel model = flier();
        TestWorld world = ground();
        Premise premise = Moves.of(MoveKind.TAKEOFF).premise(model, world, AT, Stance.at(world, model.body().stats(), AT), Heading.UP);
        Maneuver m = assertInstanceOf(Premise.Holds.class, premise).maneuver();
        assertEquals(AT.above(), m.to());
        assertEquals(Stance.Kind.FLYING, m.landing().kind());
        assertTrue(m.edits().isEmpty(), "飞行不改地形");
        double ticks = Kinematics.takeoffTicks(Mobility.VANILLA_FLYING_SPEED);
        assertEquals(ticks, Moves.of(MoveKind.TAKEOFF).ticks(model, m), 1e-12);
        assertEquals(ticks, Moves.of(MoveKind.TAKEOFF).cost(model, m), 1e-12);
        assertTrue(ticks > Kinematics.TAKEOFF_TAPS + 5, "起飞之后加速到巡航要损失十来刻:" + ticks);
    }

    @Test
    void takingOffNeedsHeadroomForTheBodyOneUp() {
        CostModel model = flier();
        TestWorld low = ground().set(AT.above(2), Blocks.STONE.defaultBlockState());
        Premise premise = Moves.of(MoveKind.TAKEOFF).premise(model, low, AT, Stance.at(low, model.body().stats(), AT), Heading.UP);
        assertInstanceOf(Premise.Fails.class, premise);
    }

    @Test
    void flyingOneStepHoldsInOpenAirAndCostsTheSteadySpeed() {
        CostModel model = flier();
        TestWorld world = ground();
        BlockPos from = AT.above(4);
        Move fly = Moves.of(MoveKind.FLY);
        Stance stance = aloft(world, model, from);
        Maneuver east = assertInstanceOf(Premise.Holds.class, fly.premise(model, world, from, stance, EAST)).maneuver();
        assertEquals(from.east(), east.to());
        assertEquals(Kinematics.flyTicksPerBlock(Mobility.VANILLA_FLYING_SPEED), fly.cost(model, east), 1e-12);
        Maneuver diagonal = assertInstanceOf(Premise.Holds.class, fly.premise(model, world, from, stance, new Heading(1, 0, 1))).maneuver();
        assertEquals(Math.sqrt(2) * Kinematics.flyTicksPerBlock(Mobility.VANILLA_FLYING_SPEED), fly.cost(model, diagonal), 1e-9);
        Maneuver up = assertInstanceOf(Premise.Holds.class, fly.premise(model, world, from, stance, Heading.UP)).maneuver();
        assertEquals(Kinematics.flyClimbTicksPerBlock(Mobility.VANILLA_FLYING_SPEED), fly.cost(model, up), 1e-12);
        Maneuver climbing = assertInstanceOf(Premise.Holds.class, fly.premise(model, world, from, stance, new Heading(1, 1, 0))).maneuver();
        assertEquals(Kinematics.flyClimbTicksPerBlock(Mobility.VANILLA_FLYING_SPEED), fly.cost(model, climbing), 1e-12,
                "边飞边升:水平与竖直同时在走,取长的");
    }

    @Test
    void flyingIsBlockedByABlockInTheWayAndTheGroundIsALandingNotAFlight() {
        CostModel model = flier();
        BlockPos from = AT.above(4);
        TestWorld wall = ground().fill(1, Y + 3, -2, 1, Y + 6, 2, Blocks.STONE.defaultBlockState());
        assertInstanceOf(Premise.Fails.class, Moves.of(MoveKind.FLY).premise(model, wall, from, aloft(wall, model, from), EAST));
        // 往下一格是地面(脚落下去就踩着地):降落,不是飞
        BlockPos low = AT.above();
        TestWorld world = ground();
        assertInstanceOf(Premise.Fails.class, Moves.of(MoveKind.FLY).premise(model, world, low, aloft(world, model, low), Heading.DOWN));
    }

    @Test
    void landingOntoSolidGroundHoldsAndPaysForTheBraking() {
        CostModel model = flier();
        TestWorld world = ground();
        BlockPos from = AT.above();
        Maneuver m = assertInstanceOf(Premise.Holds.class, land(world, model, from, Heading.DOWN)).maneuver();
        assertEquals(AT, m.to());
        assertTrue(m.landing().grounded());
        assertEquals(AT.below(), m.support());
        double brake = Kinematics.flyStopLoss(Mobility.VANILLA_FLYING_SPEED);
        assertTrue(brake > 5, "疾飞停下比一路巡航慢好几刻:" + brake);
        assertEquals(Moves.of(MoveKind.LAND).ticks(model, m), Moves.of(MoveKind.LAND).cost(model, m), 1e-12);
        assertTrue(Moves.of(MoveKind.LAND).ticks(model, m) >= brake);
        // 落点不是站着:空中不是降落
        assertInstanceOf(Premise.Fails.class, land(world, model, from.above(2), Heading.DOWN));
    }

    @Test
    void aShortHopCostsMoreToFlyThanToWalkAndALongOneLess() {
        CostModel model = flier();
        double walk = Kinematics.sprintTicksPerBlock(model.body().stats());
        double fixed = Kinematics.takeoffTicks(Mobility.VANILLA_FLYING_SPEED) + Kinematics.flyStopLoss(Mobility.VANILLA_FLYING_SPEED);
        double fly = Kinematics.flyTicksPerBlock(Mobility.VANILLA_FLYING_SPEED);
        assertTrue(fixed + 5 * fly > 5 * walk, "五格飞不如走:起飞与刹车的固定价钱让短途不值得飞");
        assertTrue(fixed + 40 * fly < 40 * walk, "四十格飞比走划算");
    }

    @Test
    void theEstimateFloorUsesTheFlightPaceOnlyForABodyThatMayFly() {
        var pace = Kinematics.pace(Vanilla.SURVIVAL);
        var flying = pace.withFlight(Mobility.VANILLA_FLYING_SPEED);
        assertEquals(pace.walk(), flying.walk());
        assertTrue(flying.sprint() < pace.sprint(), "疾飞比疾跑快");
    }
}
