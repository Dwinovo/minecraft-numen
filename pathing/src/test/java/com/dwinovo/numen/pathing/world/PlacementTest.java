package com.dwinovo.numen.pathing.world;

import java.util.Set;

import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 放置:能贴哪些面、准星点在哪({@link Faces}),方块真正落在哪一格({@link Replaceable})。放的是圆石。 */
class PlacementTest {

    private static final Block COBBLE = Blocks.COBBLESTONE;
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    private static Set<Direction> faces(BlockState below) {
        return Faces.against(new TestWorld().set(TARGET.below(), below), TARGET, COBBLE);
    }

    // ==================== 贴面 ====================

    @Test
    void anyBlockWithAnOutlineOffersAFace() {
        for (BlockState state : new BlockState[] {Blocks.STONE.defaultBlockState(), Blocks.GLASS.defaultBlockState(),
                Blocks.OAK_SLAB.defaultBlockState(), Blocks.OAK_STAIRS.defaultBlockState(),
                Blocks.OAK_FENCE.defaultBlockState(), Blocks.GLASS_PANE.defaultBlockState(),
                Blocks.OAK_TRAPDOOR.defaultBlockState(), Blocks.TORCH.defaultBlockState(), Blocks.CHEST.defaultBlockState()}) {
            assertEquals(Set.of(Direction.DOWN), faces(state), state + " 点得中,能贴");
        }
    }

    @Test
    void airAndFluidsOfferNoFace() {
        assertTrue(faces(Blocks.AIR.defaultBlockState()).isEmpty());
        assertTrue(faces(Blocks.WATER.defaultBlockState()).isEmpty());
        assertTrue(faces(Blocks.LAVA.defaultBlockState()).isEmpty());
    }

    @Test
    void aReplaceableNeighbourOffersNoFaceBecauseTheBlockWouldGoIntoIt() {
        assertTrue(faces(Blocks.SHORT_GRASS.defaultBlockState()).isEmpty(), "点矮草会放进矮草那一格");
        assertTrue(faces(Blocks.SNOW.defaultBlockState()).isEmpty(), "点单层雪会放进雪那一格");
        assertEquals(Set.of(Direction.DOWN), faces(Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 2)),
                "两层雪不能被顶替,能贴");
    }

    @Test
    void facesAreFoundOnEverySide() {
        TestWorld world = new TestWorld();
        for (Direction dir : Direction.values()) {
            world.set(TARGET.relative(dir), Blocks.STONE.defaultBlockState());
        }
        assertEquals(Set.of(Direction.values()), Faces.against(world, TARGET, COBBLE));
    }

    @Test
    void theAimPointIsOnTheOutlineFacingTheTarget() {
        TestWorld stone = new TestWorld().set(TARGET.below(), Blocks.STONE.defaultBlockState());
        assertEquals(new Vec3(0.5, 64, 0.5), Faces.hitPoint(stone, TARGET, Direction.DOWN));
        TestWorld slab = new TestWorld().set(TARGET.below(),
                Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        assertEquals(new Vec3(0.5, 63.5, 0.5), Faces.hitPoint(slab, TARGET, Direction.DOWN), "下半砖的顶面在半格高");
        TestWorld side = new TestWorld().set(TARGET.north(), Blocks.STONE.defaultBlockState());
        assertEquals(new Vec3(0.5, 64.5, 0), Faces.hitPoint(side, TARGET, Direction.NORTH));
    }

    @Test
    void aimingAtAirIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Faces.hitPoint(new TestWorld(), TARGET, Direction.DOWN));
    }

    // ==================== 真实落点 ====================

    @Test
    void clickingTallGrassOrASingleSnowLayerPlacesIntoThatCell() {
        BlockPos clicked = TARGET;
        TestWorld grass = new TestWorld().set(clicked, Blocks.TALL_GRASS.defaultBlockState());
        assertEquals(clicked, Replaceable.landing(grass, clicked, Direction.UP, COBBLE));
        TestWorld snow = new TestWorld().set(clicked, Blocks.SNOW.defaultBlockState());
        assertEquals(clicked, Replaceable.landing(snow, clicked, Direction.UP, COBBLE));
    }

    @Test
    void clickingASolidFacePlacesInFrontOfIt() {
        TestWorld world = new TestWorld().set(TARGET, Blocks.STONE.defaultBlockState());
        assertEquals(TARGET.above(), Replaceable.landing(world, TARGET, Direction.UP, COBBLE));
        assertEquals(TARGET.east(), Replaceable.landing(world, TARGET, Direction.EAST, COBBLE));
    }

    @Test
    void theCellInFrontMayHoldGrassOrWaterButNotASolidBlock() {
        TestWorld world = new TestWorld().set(TARGET, Blocks.STONE.defaultBlockState())
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
