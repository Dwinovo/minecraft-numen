package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.FakeWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 面与放置:贴着哪一面放、点在面上哪一点、没指名时点哪一面({@link Faces}),方块真正落在哪一格({@link Replaceable})。放的是圆石。 */
class PlacementTest {

    private static final Block COBBLE = Blocks.COBBLESTONE;
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll
    static void boot() {
        assertTrue(FakeWorld.boot(), "Minecraft 引导不可用");
    }

    /** 只有下面那一格是 {@code below} 时,往目标格放圆石贴着点的那一面。 */
    private static Faces.Face face(BlockState below) {
        return Faces.against(new FakeWorld().set(TARGET.below(), below), TARGET, COBBLE);
    }

    private static final Faces.Face TOP_OF_BELOW = new Faces.Face(TARGET.below(), Direction.UP);

    // ==================== 贴面 ====================

    @Test
    void anyBlockWithAnOutlineOffersAFace() {
        for (BlockState state : new BlockState[] {Blocks.STONE.defaultBlockState(), Blocks.GLASS.defaultBlockState(),
                Blocks.OAK_SLAB.defaultBlockState(), Blocks.OAK_STAIRS.defaultBlockState(),
                Blocks.OAK_FENCE.defaultBlockState(), Blocks.GLASS_PANE.defaultBlockState(),
                Blocks.OAK_TRAPDOOR.defaultBlockState(), Blocks.TORCH.defaultBlockState(), Blocks.CHEST.defaultBlockState()}) {
            assertEquals(TOP_OF_BELOW, face(state), state + " 有轮廓,能贴");
        }
    }

    @Test
    void airAndFluidsOfferNoFace() {
        assertNull(face(Blocks.AIR.defaultBlockState()));
        assertNull(face(Blocks.WATER.defaultBlockState()));
        assertNull(face(Blocks.LAVA.defaultBlockState()));
        assertFalse(Faces.anyAgainst(new FakeWorld(), TARGET, COBBLE), "悬空:没有面可贴");
    }

    @Test
    void aReplaceableNeighbourOffersNoFaceBecauseTheBlockWouldGoIntoIt() {
        assertNull(face(Blocks.SHORT_GRASS.defaultBlockState()), "点矮草会放进矮草那一格");
        assertNull(face(Blocks.SNOW.defaultBlockState()), "点单层雪会放进雪那一格");
        assertEquals(TOP_OF_BELOW, face(Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 2)),
                "两层雪不能被顶替,能贴");
    }

    /** 四周都能贴时先贴下面那一格的顶面;只有一侧能贴就贴那一侧朝着目标的面。 */
    @Test
    void theTopOfTheCellBelowComesFirst() {
        FakeWorld world = new FakeWorld();
        for (Direction dir : Direction.values()) {
            world.set(TARGET.relative(dir), Blocks.STONE.defaultBlockState());
        }
        assertEquals(TOP_OF_BELOW, Faces.against(world, TARGET, COBBLE));
        FakeWorld north = new FakeWorld().set(TARGET.north(), Blocks.STONE.defaultBlockState());
        assertEquals(new Faces.Face(TARGET.north(), Direction.SOUTH), Faces.against(north, TARGET, COBBLE));
        assertTrue(Faces.anyAgainst(north, TARGET, COBBLE));
    }

    @Test
    void theClickPointIsOnTheOutlineFacingTheTarget() {
        FakeWorld stone = new FakeWorld().set(TARGET.below(), Blocks.STONE.defaultBlockState());
        assertEquals(new Vec3(0.5, 64, 0.5), Faces.point(stone, TARGET.below(), Direction.UP));
        FakeWorld slab = new FakeWorld().set(TARGET.below(),
                Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        assertEquals(new Vec3(0.5, 63.5, 0.5), Faces.point(slab, TARGET.below(), Direction.UP), "下半砖的顶面在半格高");
        FakeWorld side = new FakeWorld().set(TARGET.north(), Blocks.STONE.defaultBlockState());
        assertEquals(new Vec3(0.5, 64.5, 0), Faces.point(side, TARGET.north(), Direction.SOUTH));
    }

    /** 没有轮廓的格(空气、流体)按整格算:点在那一面的正中。 */
    @Test
    void aCellWithoutAnOutlineIsClickedAsAWholeCell() {
        assertEquals(new Vec3(0.5, 64, 0.5), Faces.point(new FakeWorld(), TARGET.below(), Direction.UP));
        FakeWorld water = new FakeWorld().set(TARGET, Blocks.WATER.defaultBlockState());
        assertEquals(new Vec3(0, 64.5, 0.5), Faces.point(water, TARGET, Direction.WEST));
    }

    // ==================== 没指名时点哪一面 ====================

    /** 站在地上点地面里的一格:侧面都贴着别的地面方块,露着的是顶面。 */
    @Test
    void aBlockInTheFloorIsClickedOnItsTop() {
        FakeWorld world = new FakeWorld().floor(-4, -4, 4, 4, 63);
        Vec3 eye = new Vec3(-2.5, 64 + 1.62, 0.5);
        assertEquals(Direction.UP, Faces.toward(world, eye, new BlockPos(0, 63, 0)));
    }

    /** 立在空地上的一格:几面都露着,取最正对着眼睛的那一面。 */
    @Test
    void aFreeStandingBlockIsClickedOnTheFaceSquarestToTheEye() {
        FakeWorld world = new FakeWorld().floor(-4, -4, 4, 4, 63).set(TARGET, Blocks.FURNACE.defaultBlockState());
        assertEquals(Direction.WEST, Faces.toward(world, new Vec3(-2.5, 64 + 1.62, 0.5), TARGET));
        assertEquals(Direction.SOUTH, Faces.toward(world, new Vec3(0.5, 64 + 1.62, 3.5), TARGET));
        assertEquals(Direction.UP, Faces.toward(world, new Vec3(0.5, 64 + 4.62, 1.2), TARGET), "从高处往下点");
    }

    /** 朝着眼睛的面都被整块方块贴着(隔着墙点):仍取最正对着眼睛的那一面。眼睛就在这一格里:取离眼睛最近的那一面。 */
    @Test
    void aBuriedBlockOrOneAroundTheEyeStillGetsAFace() {
        FakeWorld buried = new FakeWorld().set(TARGET, Blocks.CHEST.defaultBlockState());
        for (Direction dir : Direction.values()) {
            buried.set(TARGET.relative(dir), Blocks.STONE.defaultBlockState());
        }
        assertEquals(Direction.WEST, Faces.toward(buried, new Vec3(-2.5, 64.6, 0.5), TARGET));
        assertEquals(Direction.UP, Faces.toward(new FakeWorld(), new Vec3(0.5, 64.9, 0.5), TARGET));
    }

    // ==================== 真实落点 ====================

    @Test
    void clickingTallGrassOrASingleSnowLayerPlacesIntoThatCell() {
        BlockPos clicked = TARGET;
        FakeWorld grass = new FakeWorld().set(clicked, Blocks.TALL_GRASS.defaultBlockState());
        assertEquals(clicked, Replaceable.landing(grass, clicked, Direction.UP, COBBLE));
        FakeWorld snow = new FakeWorld().set(clicked, Blocks.SNOW.defaultBlockState());
        assertEquals(clicked, Replaceable.landing(snow, clicked, Direction.UP, COBBLE));
    }

    @Test
    void clickingASolidFacePlacesInFrontOfIt() {
        FakeWorld world = new FakeWorld().set(TARGET, Blocks.STONE.defaultBlockState());
        assertEquals(TARGET.above(), Replaceable.landing(world, TARGET, Direction.UP, COBBLE));
        assertEquals(TARGET.east(), Replaceable.landing(world, TARGET, Direction.EAST, COBBLE));
    }

    @Test
    void theCellInFrontMayHoldGrassOrWaterButNotASolidBlock() {
        FakeWorld world = new FakeWorld().set(TARGET, Blocks.STONE.defaultBlockState())
                .set(TARGET.above(), Blocks.WATER.defaultBlockState())
                .set(TARGET.east(), Blocks.SHORT_GRASS.defaultBlockState())
                .set(TARGET.west(), Blocks.DIRT.defaultBlockState());
        assertEquals(TARGET.above(), Replaceable.landing(world, TARGET, Direction.UP, COBBLE));
        assertEquals(TARGET.east(), Replaceable.landing(world, TARGET, Direction.EAST, COBBLE));
        assertNull(Replaceable.landing(world, TARGET, Direction.WEST, COBBLE), "前面那格是泥土,放不下");
    }

    @Test
    void vanillaReplaceabilityForPlacingADifferentBlock() {
        assertTrue(Replaceable.replaceableBy(Blocks.AIR.defaultBlockState(), COBBLE));
        assertTrue(Replaceable.replaceableBy(Blocks.FIRE.defaultBlockState(), COBBLE));
        assertTrue(Replaceable.replaceableBy(Blocks.GLOW_LICHEN.defaultBlockState(), COBBLE));
        assertTrue(Replaceable.replaceableBy(Blocks.SCULK_VEIN.defaultBlockState(), COBBLE));
        assertTrue(Replaceable.replaceableBy(Blocks.VINE.defaultBlockState().setValue(VineBlock.NORTH, true), COBBLE));
        BlockState fullVine = Blocks.VINE.defaultBlockState();
        for (var face : VineBlock.PROPERTY_BY_DIRECTION.values()) {
            fullVine = fullVine.setValue(face, true);
        }
        assertFalse(Replaceable.replaceableBy(fullVine, COBBLE), "五个面长满的藤蔓不能被顶替");
        assertFalse(Replaceable.replaceableBy(Blocks.PITCHER_CROP.defaultBlockState(), COBBLE));
        assertFalse(Replaceable.replaceableBy(Blocks.STONE.defaultBlockState(), COBBLE));
        assertFalse(Replaceable.replaceableBy(Blocks.SNOW.defaultBlockState(), Blocks.SNOW), "同种是叠放,不是顶替");
    }
}
