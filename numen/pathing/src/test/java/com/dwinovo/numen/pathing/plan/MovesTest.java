package com.dwinovo.numen.pathing.plan;

import java.util.List;
import java.util.Set;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.spec.BlockBans;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.pathing.Fixtures.natural;
import static com.dwinovo.numen.pathing.Fixtures.withCobble;
import static com.dwinovo.numen.pathing.plan.Steps.fails;
import static com.dwinovo.numen.pathing.plan.Steps.holds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每种走法的前提成立与不成立。场景摆在 {@code Y - 1} 的石头地板上,空地上脚在 {@code Y};起点一律是 {@link #AT}。
 */
class MovesTest {

    private static final int Y = 64;
    private static final BlockPos AT = new BlockPos(0, Y, 0);
    private static final Heading EAST = new Heading(1, 0, 0);
    private static final Heading NORTH_EAST = new Heading(1, 0, -1);

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    private static TestWorld ground() {
        return new TestWorld().floor(-4, -4, 6, 4, Y - 1);
    }

    private static CostModel defaults() {
        return Fixtures.model(RouteSpec.defaults());
    }

    private static BlockState door(net.minecraft.world.level.block.Block block, DoubleBlockHalf half) {
        return block.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.HALF, half);
    }

    private static TestWorld withDoor(TestWorld world, BlockPos lower, net.minecraft.world.level.block.Block block) {
        return world.set(lower, door(block, DoubleBlockHalf.LOWER)).set(lower.above(), door(block, DoubleBlockHalf.UPPER));
    }

    private static long doors(Maneuver m) {
        return m.edits().stream().filter(e -> e instanceof Edit.Door).count();
    }

    // ==================== 平走 ====================

    @Test
    void walkingAcrossFlatGroundChangesNothingAndMaySprint() {
        Maneuver m = holds(MoveKind.WALK, defaults(), ground(), AT, EAST);
        assertEquals(AT.east(), m.to());
        assertTrue(m.edits().isEmpty());
        assertTrue(m.sprint());
        assertFalse(m.jump());
    }

    /** 规格不许疾跑时,平地上走一格不疾跑,比许疾跑时贵。 */
    @Test
    void aSpecThatForbidsSprintingWalksAndPaysForIt() {
        CostModel walking = Fixtures.model(RouteSpec.defaults().edit().sprint(false).build());
        Maneuver walked = holds(MoveKind.WALK, walking, ground(), AT, EAST);
        assertFalse(walked.sprint());
        assertTrue(Steps.cost(walking, walked) > Steps.cost(defaults(), holds(MoveKind.WALK, defaults(), ground(), AT, EAST)));
    }

    /**
     * 规格按位置禁站、禁穿过的格与按种类禁站的方块,平走一步踩上、穿过它们都不成,原因是"规格禁止",点出的是那一格;
     * 同一步没有这些禁令时照常成立。
     */
    @Test
    void cellsAndBlocksTheSpecForbidsAreNeitherStoodOnNorPassedThrough() {
        BlockPos support = AT.east().below();
        BlockPos head = AT.east().above();
        TestWorld planks = ground().set(support, Blocks.OAK_PLANKS.defaultBlockState());
        holds(MoveKind.WALK, defaults(), planks, AT, EAST);

        RouteSpec noStand = RouteSpec.defaults().edit()
                .positions(PositionCosts.builder().forbid(Use.STAND, support.asLong()).build()).build();
        Premise.Fails stand = fails(MoveKind.WALK, Fixtures.model(noStand), planks, AT, EAST);
        assertEquals(Reason.FORBIDDEN, stand.reason());
        assertEquals(support, stand.cell());

        RouteSpec noPass = RouteSpec.defaults().edit()
                .positions(PositionCosts.builder().forbid(Use.PASS, head.asLong()).build()).build();
        Premise.Fails pass = fails(MoveKind.WALK, Fixtures.model(noPass), planks, AT, EAST);
        assertEquals(Reason.FORBIDDEN, pass.reason());
        assertEquals(head, pass.cell(), "头顶经过的那一格");

        RouteSpec noPlanks = RouteSpec.defaults().edit()
                .bans(new BlockBans(Set.of(), Set.of(), Set.of(Blocks.OAK_PLANKS))).build();
        Premise.Fails banned = fails(MoveKind.WALK, Fixtures.model(noPlanks), planks, AT, EAST);
        assertEquals(Reason.FORBIDDEN, banned.reason());
        assertEquals(support, banned.cell());
    }

    @Test
    void aWallIsDugOnlyWhenTheSpecMayDig() {
        TestWorld world = ground().fill(1, Y, -4, 1, Y + 1, 4, Blocks.STONE.defaultBlockState());
        assertEquals(Reason.NO_DIGGING, fails(MoveKind.WALK, defaults(), world, AT, EAST).reason());
        Maneuver m = holds(MoveKind.WALK, Fixtures.model(natural()), world, AT, EAST);
        assertEquals(2, m.alterations());
        assertTrue(m.edits().stream().allMatch(e -> e instanceof Edit.Dig));
        assertFalse(m.sprint(), "边挖边走不疾跑");
    }

    @Test
    void aGapIsBridgedAndNotPlacingIsToldApartFromHavingNoBlocks() {
        TestWorld world = new TestWorld().floor(-4, -4, 0, 4, Y - 1);
        assertEquals(Reason.NO_PLACING, fails(MoveKind.WALK, withCobble(RouteSpec.defaults()), world, AT, EAST).reason(),
                "有料但规格不许改地形");
        assertEquals(Reason.NO_MATERIALS, fails(MoveKind.WALK, Fixtures.model(natural()), world, AT, EAST).reason(),
                "许改地形但身上没料");
        Maneuver m = holds(MoveKind.WALK, withCobble(natural()), world, AT, EAST);
        Edit.Place place = assertInstanceOf(Edit.Place.class, m.edits().get(0));
        assertEquals(AT.east().below(), place.pos());
        assertEquals(Blocks.COBBLESTONE, place.block());
        assertTrue(m.sneak(), "只剩脚下那块的侧面可贴,要潜行探出去放");
    }

    @Test
    void aWallThatWouldLetWaterInIsNotDug() {
        TestWorld world = ground().fill(1, Y, -4, 1, Y + 1, 4, Blocks.STONE.defaultBlockState())
                .set(2, Y, 0, Blocks.WATER.defaultBlockState());
        assertEquals(Reason.WOULD_FLOOD, fails(MoveKind.WALK, Fixtures.model(natural()), world, AT, EAST).reason());
    }

    @Test
    void aWallUnderSandIsNotDug() {
        TestWorld world = ground().fill(1, Y, -4, 1, Y + 1, 4, Blocks.STONE.defaultBlockState())
                .set(1, Y + 2, 0, Blocks.SAND.defaultBlockState());
        assertEquals(Reason.WOULD_COLLAPSE, fails(MoveKind.WALK, Fixtures.model(natural()), world, AT, EAST).reason());
    }

    // ==================== 门 ====================

    @Test
    void aClosedWoodenDoorIsOpenedOnTheWayEvenWhenTheRouteMayNotDig() {
        TestWorld world = withDoor(ground(), AT.east(), Blocks.OAK_DOOR);
        Maneuver m = holds(MoveKind.WALK, defaults(), world, AT, EAST);
        assertEquals(1, doors(m));
        assertEquals(0, m.alterations(), "开门不算改地形");
    }

    @Test
    void aClosedIronDoorWithoutRedstoneIsAWallButAnOpenOneIsPassable() {
        TestWorld closed = withDoor(ground(), AT.east(), Blocks.IRON_DOOR);
        assertEquals(Reason.NO_DIGGING, fails(MoveKind.WALK, defaults(), closed, AT, EAST).reason());
        TestWorld powered = ground()
                .set(AT.east(), door(Blocks.IRON_DOOR, DoubleBlockHalf.LOWER).setValue(DoorBlock.OPEN, true))
                .set(AT.east().above(), door(Blocks.IRON_DOOR, DoubleBlockHalf.UPPER).setValue(DoorBlock.OPEN, true));
        Maneuver m = holds(MoveKind.WALK, defaults(), powered, AT, EAST);
        assertTrue(m.edits().isEmpty());
    }

    @Test
    void everyKindOfStepThroughADoorCarriesOpeningIt() {
        // 上一级:台阶上面是门
        TestWorld up = withDoor(ground().set(AT.east(), Blocks.STONE.defaultBlockState()), AT.east().above(), Blocks.OAK_DOOR);
        assertEquals(1, doors(holds(MoveKind.ASCEND, defaults(), up, AT, EAST)));
        // 下一级:门在低一级的那一列里
        TestWorld down = withDoor(new TestWorld().floor(-4, -4, 0, 4, Y - 1).floor(1, -4, 4, 4, Y - 2),
                AT.east().below(), Blocks.OAK_DOOR);
        down.set(AT.east().above(), Blocks.AIR.defaultBlockState());
        assertEquals(1, doors(holds(MoveKind.DESCEND, defaults(), down, AT, EAST)));
        // 斜走:门在斜对角那一列
        TestWorld diagonal = withDoor(ground(), new BlockPos(1, Y, -1), Blocks.OAK_DOOR);
        assertEquals(1, doors(holds(MoveKind.DIAGONAL, defaults(), diagonal, AT, NORTH_EAST)));
        // 攀爬:梯子顶上是关着的活板门
        TestWorld hatch = ladderShaft(3).set(0, Y + 3, 0, Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.FACING, Direction.WEST).setValue(TrapDoorBlock.HALF, Half.BOTTOM));
        assertEquals(1, doors(holds(MoveKind.CLIMB, defaults(), hatch, AT.above(), Heading.UP)));
    }

    // ==================== 上一级与楼梯 ====================

    private static BlockState stair(Direction facing) {
        return Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, Half.BOTTOM);
    }

    @Test
    void aStairIsWalkedUpFromTheFrontButJumpedFromTheBack() {
        // 朝东的楼梯高的那半在东边:从西边(正面)进是走上去
        TestWorld front = ground().set(AT.east(), stair(Direction.EAST));
        Maneuver walkUp = holds(MoveKind.ASCEND, defaults(), front, AT, EAST);
        assertFalse(walkUp.jump(), "楼梯从正面走上去");
        TestWorld back = ground().set(AT.east(), stair(Direction.WEST));
        Maneuver jumpUp = holds(MoveKind.ASCEND, defaults(), back, AT, EAST);
        assertTrue(jumpUp.jump(), "楼梯从背面要跳");
        assertTrue(Steps.cost(defaults(), walkUp) < Steps.cost(defaults(), jumpUp), "走上楼梯比跳上去便宜");
    }

    @Test
    void aFullBlockStepIsJumpedAndAMissingStepIsPlacedWhenAllowed() {
        TestWorld block = ground().set(AT.east(), Blocks.STONE.defaultBlockState());
        assertTrue(holds(MoveKind.ASCEND, defaults(), block, AT, EAST).jump());
        TestWorld nothing = ground();
        assertEquals(Reason.NO_PLACING, fails(MoveKind.ASCEND, withCobble(RouteSpec.defaults()), nothing, AT, EAST).reason());
        Maneuver placed = holds(MoveKind.ASCEND, withCobble(natural()), nothing, AT, EAST);
        assertEquals(AT.east(), assertInstanceOf(Edit.Place.class, placed.edits().get(0)).pos());
    }

    @Test
    void aStepUnderALowCeilingNeedsTheCeilingDug() {
        TestWorld world = ground().set(AT.east(), Blocks.STONE.defaultBlockState()).set(0, Y + 2, 0, Blocks.STONE.defaultBlockState());
        assertEquals(Reason.NO_DIGGING, fails(MoveKind.ASCEND, defaults(), world, AT, EAST).reason(), "起跳会撞头");
        Maneuver m = holds(MoveKind.ASCEND, Fixtures.model(natural()), world, AT, EAST);
        assertTrue(m.edits().stream().anyMatch(e -> e.pos().equals(new BlockPos(0, Y + 2, 0))));
    }

    // ==================== 斜走 ====================

    @Test
    void aDiagonalCutsNoCorners() {
        assertTrue(holds(MoveKind.DIAGONAL, defaults(), ground(), AT, NORTH_EAST).sprint());
        TestWorld corner = ground().fill(1, Y, 0, 1, Y + 1, 0, Blocks.STONE.defaultBlockState());
        assertEquals(Reason.NO_CLEARANCE, fails(MoveKind.DIAGONAL, Fixtures.model(natural()), corner, AT, NORTH_EAST).reason(),
                "斜穿切角不许,也不为斜走挖");
    }

    @Test
    void diagonalClimbsAreOffByDefault() {
        TestWorld world = ground().set(1, Y, -1, Blocks.STONE.defaultBlockState());
        assertEquals(Reason.DISABLED, fails(MoveKind.DIAGONAL, defaults(), world, AT, NORTH_EAST.withDy(1)).reason());
        RouteSpec on = RouteSpec.defaults().edit().diagonalAscend(true).build();
        assertEquals(new BlockPos(1, Y + 1, -1), holds(MoveKind.DIAGONAL, Fixtures.model(on), world, AT, NORTH_EAST.withDy(1)).to());
    }

    // ==================== 下一级与下落 ====================

    private static TestWorld ledge(int depth) {
        return new TestWorld().floor(-4, -4, 0, 4, Y - 1).floor(1, -4, 6, 4, Y - 1 - depth);
    }

    @Test
    void oneStepDownIsADescentAndDeeperIsAFall() {
        assertEquals(AT.east().below(), holds(MoveKind.DESCEND, defaults(), ledge(1), AT, EAST).to());
        assertEquals(Reason.WRONG_DROP, fails(MoveKind.FALL, defaults(), ledge(1), AT, EAST).reason());
        assertEquals(Reason.WRONG_DROP, fails(MoveKind.DESCEND, defaults(), ledge(3), AT, EAST).reason());
        Maneuver fall = holds(MoveKind.FALL, defaults(), ledge(3), AT, EAST);
        assertEquals(new BlockPos(1, Y - 3, 0), fall.to());
        assertEquals(3, fall.drop(), 1e-9);
    }

    @Test
    void aFallDeeperThanTheLimitIsRefusedUnlessWaterCatchesTheBody() {
        assertEquals(Reason.TOO_FAR_TO_FALL, fails(MoveKind.FALL, defaults(), ledge(5), AT, EAST).reason());
        TestWorld pool = ledge(12);
        pool.fill(1, Y - 12, -4, 6, Y - 10, 4, Blocks.WATER.defaultBlockState());
        Maneuver m = holds(MoveKind.FALL, defaults(), pool, AT, EAST);
        assertTrue(m.wading());
        assertEquals(Stance.Kind.SWIMMING, m.landing().kind());
    }

    @Test
    void aFallTooDeepIsCaughtWithWaterOnlyWhenABucketIsCarriedAndTheSpecMayPlace() {
        TestWorld cliff = ledge(12);
        BodySnapshot bucket = Fixtures.carrying(0, new ItemStack(net.minecraft.world.item.Items.WATER_BUCKET));
        CostModel natural = CostModel.of(natural(), bucket, TerrainPolicy.ALLOW_ALL, Materials.NONE, Threats.NONE);
        Maneuver m = holds(MoveKind.FALL, natural, cliff, AT, EAST);
        assertTrue(m.wading(), "落进自己倒下的水里");
        Edit.Catch caught = assertInstanceOf(Edit.Catch.class, m.edits().get(m.edits().size() - 1));
        assertEquals(m.to(), caught.pos(), "水倒在落点那一格");
        // 没带水桶,还是摔不起
        assertEquals(Reason.TOO_FAR_TO_FALL, fails(MoveKind.FALL, Fixtures.model(natural()), cliff, AT, EAST).reason());
        // 带着水桶,但规格不许改地形
        CostModel none = CostModel.of(RouteSpec.defaults(), bucket, TerrainPolicy.ALLOW_ALL, Materials.NONE, Threats.NONE);
        assertEquals(Reason.NO_PLACING, fails(MoveKind.FALL, none, cliff, AT, EAST).reason());
    }

    @Test
    void theFallLimitTightensWithHealthAndTheSpecCanOnlyTightenIt() {
        RouteSpec loose = RouteSpec.defaults().edit().maxFallHeightNoWater(10).build();
        CostModel healthy = CostModel.of(loose, Fixtures.body(20), TerrainPolicy.ALLOW_ALL, Materials.NONE, Threats.NONE);
        CostModel hurt = CostModel.of(loose, Fixtures.body(8), TerrainPolicy.ALLOW_ALL, Materials.NONE, Threats.NONE);
        holds(MoveKind.FALL, healthy, ledge(8), AT, EAST);
        assertEquals(Reason.TOO_FAR_TO_FALL, fails(MoveKind.FALL, hurt, ledge(8), AT, EAST).reason(), "血少了摔不起");
        assertEquals(Reason.TOO_FAR_TO_FALL, fails(MoveKind.FALL, healthy, ledge(12), AT, EAST).reason(), "规格只到 10 格");
        assertTrue(Steps.cost(healthy, holds(MoveKind.FALL, healthy, ledge(8), AT, EAST))
                > Steps.cost(healthy, holds(MoveKind.FALL, healthy, ledge(3), AT, EAST)) + 5 * ActionCosts.FALL_DAMAGE_PER_POINT - 1,
                "摔疼的按掉血折价");
    }

    @Test
    void whatTheFallLandsOnDecidesWhetherItIsBearable() {
        CostModel loose = Fixtures.model(RouteSpec.defaults().edit().maxFallHeightNoWater(30).build());
        // 同一道十格的崖:落在石头上掉 7 点;落在朝上的滴水石锥尖上(顶面 11/16)按原版掉 17 点,满血也摔不起
        assertEquals(7, holds(MoveKind.FALL, loose, ledge(10), AT, EAST).fallDamage());
        TestWorld spike = ledge(10).set(1, Y - 10, 0, net.minecraft.world.level.block.Blocks.POINTED_DRIPSTONE.defaultBlockState());
        assertEquals(Reason.TOO_FAR_TO_FALL, fails(MoveKind.FALL, loose, spike, AT, EAST).reason());
        // 十八格落在石头上摔不起,落在干草块上只掉 3 点
        assertEquals(Reason.TOO_FAR_TO_FALL, fails(MoveKind.FALL, loose, ledge(18), AT, EAST).reason());
        TestWorld hay = ledge(18).set(1, Y - 19, 0, net.minecraft.world.level.block.Blocks.HAY_BLOCK.defaultBlockState());
        Maneuver soft = holds(MoveKind.FALL, loose, hay, AT, EAST);
        assertEquals(3, soft.fallDamage());
    }

    @Test
    void landingOnFarmlandFromAHeightTramplesItEvenWhenTheRouteMayStepOnIt() {
        RouteSpec farm = RouteSpec.defaults().edit().allow(com.dwinovo.numen.pathing.world.Semantics.Kind.FRAGILE).build();
        TestWorld world = ledge(2);
        world.set(1, Y - 3, 0, Blocks.FARMLAND.defaultBlockState());
        assertEquals(Reason.TRAMPLES, fails(MoveKind.FALL, Fixtures.model(farm), world, AT, EAST).reason());
    }

    // ==================== 跑酷 ====================

    private static TestWorld gap(int width) {
        return new TestWorld().floor(-4, -4, 0, 4, Y - 1).floor(width + 1, -4, width + 6, 4, Y - 1);
    }

    @Test
    void parkourIsOffByDefaultAndOnlyJumpsRealGaps() {
        assertEquals(Reason.DISABLED, fails(MoveKind.PARKOUR, defaults(), gap(2), AT, EAST).reason());
        CostModel on = Fixtures.model(RouteSpec.defaults().edit().parkour(true).build());
        Maneuver m = holds(MoveKind.PARKOUR, on, gap(2), AT, EAST);
        assertEquals(new BlockPos(3, Y, 0), m.to());
        assertEquals(3, m.span());
        assertEquals(Reason.NO_GAP, fails(MoveKind.PARKOUR, on, ground(), AT, EAST).reason(), "走得过去就不跳");
    }

    @Test
    void theLongestJumpNeedsASprintAndAHungryBodyCannotSprint() {
        RouteSpec spec = RouteSpec.defaults().edit().parkour(true).build();
        assertTrue(holds(MoveKind.PARKOUR, Fixtures.model(spec), gap(3), AT, EAST).sprint());
        BodySnapshot hungry = new BodySnapshot(Vanilla.SURVIVAL, GameType.SURVIVAL, 20, 3, 1, 6, 0, List.of(),
                BodySnapshot.Mining.VANILLA, Breath.VANILLA);
        CostModel model = CostModel.of(spec, hungry, TerrainPolicy.ALLOW_ALL, Materials.NONE, Threats.NONE);
        assertEquals(Reason.NO_SPRINT, fails(MoveKind.PARKOUR, model, gap(3), AT, EAST).reason());
    }

    // ==================== 垫柱与向下挖 ====================

    @Test
    void pillaringNeedsBothPermissionAndBlocksAndNotWater() {
        assertEquals(Reason.NO_PLACING, fails(MoveKind.PILLAR, withCobble(RouteSpec.defaults()), ground(), AT, Heading.UP).reason());
        assertEquals(Reason.NO_MATERIALS, fails(MoveKind.PILLAR, Fixtures.model(natural()), ground(), AT, Heading.UP).reason());
        Maneuver m = holds(MoveKind.PILLAR, withCobble(natural()), ground(), AT, Heading.UP);
        assertEquals(AT.above(), m.to());
        assertEquals(AT, assertInstanceOf(Edit.Place.class, m.edits().get(0)).pos());
        TestWorld wet = ground().set(AT, Blocks.WATER.defaultBlockState());
        assertEquals(Reason.WRONG_STANCE, fails(MoveKind.PILLAR, withCobble(natural()), wet, AT, Heading.UP).reason(),
                "泡在水里不垫柱");
    }

    /** 头顶一格压着石头时垫柱:先挖开头顶那一格,再往脚下垫块;不许改地形时这一步不成。 */
    @Test
    void pillaringUnderABlockDigsItFirst() {
        BlockPos overhead = new BlockPos(0, Y + 2, 0);
        TestWorld world = ground().set(overhead, Blocks.STONE.defaultBlockState());
        Maneuver m = holds(MoveKind.PILLAR, withCobble(natural()), world, AT, Heading.UP);
        List<Edit> edits = m.edits();
        int dig = -1;
        int place = -1;
        for (int i = 0; i < edits.size(); i++) {
            if (edits.get(i) instanceof Edit.Dig d && d.pos().equals(overhead)) {
                dig = i;
            }
            if (edits.get(i) instanceof Edit.Place p && p.pos().equals(AT)) {
                place = i;
            }
        }
        assertTrue(dig >= 0, "挖开头顶那一格:" + edits);
        assertTrue(place > dig, "挖开之后才垫块:" + edits);
        assertEquals(Reason.NO_DIGGING, fails(MoveKind.PILLAR, withCobble(RouteSpec.defaults()), world, AT, Heading.UP).reason());
    }

    @Test
    void diggingDownLandsOneStepLowerOrNotAtAll() {
        TestWorld world = ground().set(0, Y - 3, 0, Blocks.STONE.defaultBlockState());
        world.set(0, Y - 2, 0, Blocks.STONE.defaultBlockState());
        assertEquals(Reason.NO_DIGGING, fails(MoveKind.DOWNWARD, defaults(), world, AT, Heading.DOWN).reason());
        Maneuver m = holds(MoveKind.DOWNWARD, Fixtures.model(natural()), world, AT, Heading.DOWN);
        assertEquals(AT.below(), m.to());
        assertEquals(AT.below(), m.edits().get(0).pos());
        TestWorld hollow = ground();
        assertEquals(Reason.NO_FOOTING, fails(MoveKind.DOWNWARD, Fixtures.model(natural()), hollow, AT, Heading.DOWN).reason());
    }

    // ==================== 攀爬 ====================

    /** 原点这一列靠东墙挂着 {@code height} 格梯子。 */
    private static TestWorld ladderShaft(int height) {
        TestWorld world = ground().fill(1, Y, -1, 1, Y + height + 2, 1, Blocks.STONE.defaultBlockState());
        for (int y = Y; y < Y + height; y++) {
            world.set(0, y, 0, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST));
        }
        return world;
    }

    @Test
    void ladderClimbingGoesUpAndDownButNotPastTheTop() {
        TestWorld world = ladderShaft(3);
        assertEquals(AT.above(), holds(MoveKind.CLIMB, defaults(), world, AT, Heading.UP).to());
        assertEquals(AT.above(), holds(MoveKind.CLIMB, defaults(), world, AT.above(2), Heading.DOWN).to());
        assertEquals(Reason.NO_FOOTING, fails(MoveKind.CLIMB, defaults(), world, AT.above(2), Heading.UP).reason());
    }

    @Test
    void aLadderUnderARoofStairIsNotClimbedThrough() {
        // 梯子口头顶压着屋顶楼梯(上半楼梯):身体在最上面那格梯子上放不下
        TestWorld world = ladderShaft(3).set(0, Y + 3, 0, Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.EAST).setValue(StairBlock.HALF, Half.TOP));
        Premise.Fails f = fails(MoveKind.CLIMB, defaults(), world, AT.above(), Heading.UP);
        assertEquals(new BlockPos(0, Y + 3, 0), f.cell(), "卡在屋顶楼梯那一格");
        assertEquals(Reason.NO_DIGGING, f.reason());
    }

    @Test
    void aHangingVineIsClimbedLikeALadder() {
        TestWorld world = ground();
        for (int y = Y; y < Y + 3; y++) {
            world.set(0, y, 0, Blocks.VINE.defaultBlockState().setValue(VineBlock.UP, true));
        }
        assertEquals(AT.above(), holds(MoveKind.CLIMB, defaults(), world, AT, Heading.UP).to());
    }

    // ==================== 水 ====================

    /** 以原点为中心、深 {@code depth} 的一池静水,水面在 {@code Y - 1} 那一层。 */
    private static TestWorld pool(int depth) {
        TestWorld world = new TestWorld().floor(-6, -6, 6, 6, Y - 1 - depth);
        world.fill(-6, Y - depth, -6, 6, Y - 1, 6, Blocks.STONE.defaultBlockState());
        return world.fill(-4, Y - depth, -4, 4, Y - 1, 4, Blocks.WATER.defaultBlockState());
    }

    @Test
    void stillWaterIsSwumAcrossUpAndDown() {
        TestWorld world = pool(3);
        BlockPos in = new BlockPos(0, Y - 2, 0);
        Maneuver across = holds(MoveKind.SWIM, defaults(), world, in, EAST);
        assertTrue(across.wading());
        assertEquals(in.above(), holds(MoveKind.SWIM, defaults(), world, in, Heading.UP).to());
        assertEquals(in.below(), holds(MoveKind.SWIM, defaults(), world, in, Heading.DOWN).to());
    }

    @Test
    void flowingWaterIsKeptOutOfByDefaultAndSwumWhenAllowed() {
        // 一格源头往东流:源头旁边那一格是流动的水,身体会被推走
        TestWorld world = new TestWorld().floor(-4, -4, 6, 4, Y - 1)
                .set(0, Y, 0, Blocks.WATER.defaultBlockState())
                .set(1, Y, 0, Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 1));
        assertEquals(Reason.EXCLUDED, fails(MoveKind.SWIM, defaults(), world, AT, EAST).reason());
        RouteSpec wet = RouteSpec.defaults().edit().allow(com.dwinovo.numen.pathing.world.Semantics.Kind.FLOWING_WATER).build();
        assertEquals(AT.east(), holds(MoveKind.SWIM, Fixtures.model(wet), world, AT, EAST).to());
    }

    @Test
    void aFreeFallingWaterColumnHasNoPushAndIsSwumUp() {
        // 悬空、四周没有依托的下落水柱,原版算出的水流为零:当静水游上去
        TestWorld world = new TestWorld().floor(-2, -2, 2, 2, Y - 1);
        for (int y = Y; y < Y + 4; y++) {
            world.set(0, y, 0, Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 8));
        }
        assertEquals(AT.above(), holds(MoveKind.SWIM, defaults(), world, AT, Heading.UP).to());
    }

    @Test
    void aStepBesideLavaCostsMoreThanTheSameStepAwayFromIt() {
        TestWorld safe = ground();
        TestWorld beside = ground().set(1, Y, 1, Blocks.LAVA.defaultBlockState());
        Maneuver away = holds(MoveKind.WALK, defaults(), safe, AT, EAST);
        Maneuver near = holds(MoveKind.WALK, defaults(), beside, AT, EAST);
        assertEquals(0, away.exposure());
        assertEquals(1, near.exposure(), "落点那一列南边紧挨着一格岩浆");
        assertTrue(Steps.cost(defaults(), near) > Steps.cost(defaults(), away));
    }

    @Test
    void aSwimmerClimbsOutOntoTheShoreWithoutJumping() {
        // 两格深的池子,水面那一层的格与岸边地板齐平:浮在水面上,朝岸边上一级
        TestWorld world = pool(2);
        BlockPos surface = new BlockPos(4, Y - 1, 0);
        Maneuver m = holds(MoveKind.ASCEND, defaults(), world, surface, EAST);
        assertEquals(new BlockPos(5, Y, 0), m.to());
        assertFalse(m.jump(), "是游上岸的,不起跳");
    }

    @Test
    void aClimberStepsSidewaysOffTheLadderOntoABlock() {
        // 梯子贴在西墙上,东边一列是一格高的平台:攀在第二格梯子上,平着挪上平台
        TestWorld world = ground().fill(-1, Y, -1, -1, Y + 4, 1, Blocks.STONE.defaultBlockState())
                .set(1, Y, 0, Blocks.STONE.defaultBlockState());
        for (int y = Y; y < Y + 3; y++) {
            world.set(0, y, 0, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.EAST));
        }
        BlockPos rung = AT.above();
        Maneuver m = holds(MoveKind.WALK, defaults(), world, rung, EAST);
        assertEquals(rung.east(), m.to());
        assertTrue(m.landing().grounded());
    }

    @Test
    void aBodyThatCannotJumpInWaterStillWadesOutOnTheBottom() {
        TestWorld world = ground().set(AT, Blocks.WATER.defaultBlockState()).set(AT.east(), Blocks.WATER.defaultBlockState());
        Maneuver m = holds(MoveKind.WALK, defaults(), world, AT, EAST);
        assertTrue(m.wading());
        assertFalse(m.sprint(), "水里不疾跑");
    }

    @Test
    void aFrostWalkerWalksOverStillWaterThatHasAirAbove() {
        // 一池两格深的静水,水面与岸齐平:穿冰霜行者的身体踩着水面走过去,脚在水面那一层之上
        TestWorld world = pool(2);
        BodySnapshot frost = new BodySnapshot(Vanilla.FROST_WALKER, GameType.SURVIVAL, 20, 3, 1, 20, 0, List.of(),
                BodySnapshot.Mining.VANILLA, Breath.VANILLA);
        CostModel model = CostModel.of(RouteSpec.defaults(), frost, TerrainPolicy.ALLOW_ALL, Materials.NONE, Threats.NONE);
        BlockPos shore = new BlockPos(-5, Y, 0);
        Maneuver m = holds(MoveKind.WALK, model, world, shore, EAST);
        assertTrue(m.landing().grounded(), "站在冻住的水面上");
        assertEquals(Y, m.landing().feetY(), 1e-9);
        // 不穿的身体在同一处平走过去就得在水面上搭桥
        assertEquals(Reason.NO_PLACING, fails(MoveKind.WALK, defaults(), world, shore, EAST).reason());
    }

    // ==================== 细雪 ====================

    @Test
    void powderSnowHoldsOnlyABodyInLeatherBoots() {
        TestWorld world = ground().set(1, Y - 1, 0, Blocks.POWDER_SNOW.defaultBlockState());
        // 没穿皮靴,细雪托不住脚,这一步走不成
        fails(MoveKind.WALK, defaults(), world, AT, EAST);
        BodySnapshot boots = new BodySnapshot(Vanilla.LEATHER_BOOTS, GameType.SURVIVAL, 20, 3, 1, 20, 0,
                List.of(new ItemStack(net.minecraft.world.item.Items.LEATHER_BOOTS)), BodySnapshot.Mining.VANILLA,
                Breath.VANILLA);
        CostModel model = CostModel.of(RouteSpec.defaults(), boots, TerrainPolicy.ALLOW_ALL, Materials.NONE, Threats.NONE);
        Maneuver m = holds(MoveKind.WALK, model, world, AT, EAST);
        assertEquals(Y, m.landing().feetY(), 1e-9, "站在细雪顶上");
    }
}
