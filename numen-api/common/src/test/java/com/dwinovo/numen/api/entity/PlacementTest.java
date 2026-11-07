package com.dwinovo.numen.api.entity;

import java.util.Set;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 放置:能贴哪些面、准星点在哪({@link Faces}),方块真正落在哪一格({@link Replaceable})。放的是圆石。 */
class PlacementTest {

    private static final Block COBBLE = Blocks.COBBLESTONE;
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll
    static void boot() {
        assertTrue(FakeWorld.boot(), "Minecraft 引导不可用");
    }

    private static Set<Direction> faces(BlockState below) {
        return Faces.against(new FakeWorld().set(TARGET.below(), below), TARGET, COBBLE);
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
        FakeWorld world = new FakeWorld();
        for (Direction dir : Direction.values()) {
            world.set(TARGET.relative(dir), Blocks.STONE.defaultBlockState());
        }
        assertEquals(Set.of(Direction.values()), Faces.against(world, TARGET, COBBLE));
    }

    @Test
    void theAimPointIsOnTheOutlineFacingTheTarget() {
        FakeWorld stone = new FakeWorld().set(TARGET.below(), Blocks.STONE.defaultBlockState());
        assertEquals(new Vec3(0.5, 64, 0.5), Faces.point(stone, TARGET.below(), Direction.UP));
        FakeWorld slab = new FakeWorld().set(TARGET.below(),
                Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        assertEquals(new Vec3(0.5, 63.5, 0.5), Faces.point(slab, TARGET.below(), Direction.UP), "下半砖的顶面在半格高");
        FakeWorld side = new FakeWorld().set(TARGET.north(), Blocks.STONE.defaultBlockState());
        assertEquals(new Vec3(0.5, 64.5, 0), Faces.point(side, TARGET.north(), Direction.SOUTH));
    }

    @Test
    void aimingAtAirIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Faces.point(new FakeWorld(), TARGET.below(), Direction.UP));
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

    // ==================== 点得中 ====================

    /**
     * 能贴的面不等于点得中:目标格只有西边一根栅栏可贴。眼睛在目标格北边隔着两格高的圆石,不管瞄栅栏朝东那一面的哪一点,视线都先撞上
     * 那堵圆石——点不中;拿掉圆石就点得中。日式小屋里上一级垫台阶的那一步就停在这里:规划只问了能贴的面,执行瞄不中。
     */
    @Test
    void aFaceTheEyeCannotReachIsNotInSight() {
        net.minecraft.world.level.block.state.BlockState fence = Blocks.SPRUCE_FENCE.defaultBlockState();
        Vec3 eye = new Vec3(0.5, TARGET.getY() + 1.62, -1.5);
        FakeWorld hidden = new FakeWorld().set(TARGET.west(), fence)
                .set(TARGET.north(), Blocks.COBBLESTONE.defaultBlockState())
                .set(TARGET.north().above(), Blocks.COBBLESTONE.defaultBlockState());
        assertEquals(Set.of(Direction.WEST, Direction.NORTH), Faces.against(hidden, TARGET, COBBLE), "栅栏与圆石都能贴");
        assertNull(Faces.inSight(hidden, eye, 4.5, TARGET, COBBLE), "没有一点瞄得到:圆石挡着");
        assertTrue(Faces.inSightAll(hidden, eye, 4.5, TARGET, COBBLE).isEmpty());
        FakeWorld open = new FakeWorld().set(TARGET.west(), fence);
        Faces.Face face = Faces.inSight(open, eye, 4.5, TARGET, COBBLE);
        assertEquals(TARGET.west(), face == null ? null : face.clicked(), "没有挡着的就点得中栅栏");
    }

    /** 站位的"够得着"就是手点得着:没有任何能点的面的悬空格(放的一方直接写)算够得着,有面却点不着的不算。 */
    @Test
    void handReachableMeansAClickExistsOrNothingCanBeClicked() {
        Vec3 eye = new Vec3(0.5, TARGET.getY() + 1.62, -1.5);
        assertTrue(Faces.handReachable(new FakeWorld(), eye, 4.5, TARGET), "悬空:没有面可点,直接写,站哪儿一样");
        FakeWorld floor = new FakeWorld().set(TARGET.below(), Blocks.STONE.defaultBlockState());
        assertTrue(Faces.handReachable(floor, eye, 4.5, TARGET), "脚下那块的顶面点得着");
        assertFalse(Faces.handReachable(floor, new Vec3(0.5, TARGET.getY() + 1.62, -6.5), 4.5, TARGET), "太远,点不着");
        FakeWorld walled = new FakeWorld().set(TARGET.below(), Blocks.STONE.defaultBlockState())
                .set(TARGET.north(), Blocks.COBBLESTONE.defaultBlockState())
                .set(TARGET.north().above(), Blocks.COBBLESTONE.defaultBlockState());
        assertFalse(Faces.handReachable(walled, eye, 4.5, TARGET), "有面可点,可都被圆石挡着:点不着");
    }
}
