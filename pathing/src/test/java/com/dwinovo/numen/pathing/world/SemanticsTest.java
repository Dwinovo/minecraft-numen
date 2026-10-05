package com.dwinovo.numen.pathing.world;

import java.util.Set;

import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.world.Semantics.Kind;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 语义:碰撞箱回答不了的那些事。 */
class SemanticsTest {

    private static final BlockPos AT = new BlockPos(0, 64, 0);

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    private static Set<Kind> kinds(BlockState state) {
        return Semantics.kinds(new TestWorld().set(AT, state), AT);
    }

    @Test
    void waterInTheMiddleOfAStillPoolIsStill() {
        TestWorld pool = new TestWorld();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                pool.set(x, 64, z, Blocks.WATER.defaultBlockState()).set(x, 63, z, Blocks.STONE.defaultBlockState());
            }
        }
        assertEquals(Set.of(Kind.WATER), Semantics.kinds(pool, AT));
    }

    @Test
    void waterThatPushesTheBodyIsFlowing() {
        BlockState lower = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3);
        // 一格源头旁边是流淌的水:原版算出的水流不为零,池边的源头也在流
        TestWorld edge = new TestWorld().set(AT, Blocks.WATER.defaultBlockState()).set(AT.east(), lower)
                .set(AT.below(), Blocks.STONE.defaultBlockState()).set(AT.east().below(), Blocks.STONE.defaultBlockState());
        assertEquals(Set.of(Kind.FLOWING_WATER), Semantics.kinds(edge, AT));
        assertTrue(Semantics.is(edge, AT.east(), Kind.FLOWING_WATER));
    }

    @Test
    void aBubbleColumnPushesTheBodySoItIsFlowingWater() {
        TestWorld column = new TestWorld().set(AT, Blocks.BUBBLE_COLUMN.defaultBlockState())
                .set(AT.below(), Blocks.MAGMA_BLOCK.defaultBlockState());
        assertTrue(Semantics.is(column, AT, Kind.FLOWING_WATER));
        assertFalse(Semantics.is(column, AT, Kind.WATER));
    }

    @Test
    void lavaIsLavaAndAHazard() {
        assertEquals(Set.of(Kind.LAVA, Kind.HAZARD), kinds(Blocks.LAVA.defaultBlockState()));
    }

    @Test
    void climbablesFollowTheVanillaTagAndTheLadderTrapdoor() {
        for (BlockState state : new BlockState[] {Blocks.LADDER.defaultBlockState(), Blocks.VINE.defaultBlockState(),
                Blocks.SCAFFOLDING.defaultBlockState(), Blocks.TWISTING_VINES.defaultBlockState(),
                Blocks.CAVE_VINES.defaultBlockState()}) {
            assertTrue(kinds(state).contains(Kind.CLIMBABLE), state + " 可攀爬");
        }
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH);
        BlockState trapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, Direction.NORTH);
        TestWorld open = new TestWorld().set(AT.below(), ladder).set(AT, trapdoor.setValue(TrapDoorBlock.OPEN, true));
        assertTrue(Semantics.is(open, AT, Kind.CLIMBABLE), "开着、接在同向梯子上的活板门当梯子");
        TestWorld closed = new TestWorld().set(AT.below(), ladder).set(AT, trapdoor);
        assertFalse(Semantics.is(closed, AT, Kind.CLIMBABLE));
        TestWorld turned = new TestWorld().set(AT.below(), ladder)
                .set(AT, trapdoor.setValue(TrapDoorBlock.OPEN, true).setValue(TrapDoorBlock.FACING, Direction.EAST));
        assertFalse(Semantics.is(turned, AT, Kind.CLIMBABLE), "朝向不同的不算");
    }

    @Test
    void aWaterloggedLadderIsBothWaterAndClimbable() {
        TestWorld world = new TestWorld().set(AT, Blocks.LADDER.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true))
                .set(AT.below(), Blocks.STONE.defaultBlockState());
        assertTrue(Semantics.is(world, AT, Kind.CLIMBABLE));
        assertTrue(Semantics.is(world, AT, Kind.WATER) || Semantics.is(world, AT, Kind.FLOWING_WATER));
    }

    @Test
    void woodenDoorsGatesAndTrapdoorsOpenByHandIronOnesDoNot() {
        for (BlockState state : new BlockState[] {Blocks.OAK_DOOR.defaultBlockState(), Blocks.OAK_FENCE_GATE.defaultBlockState(),
                Blocks.OAK_TRAPDOOR.defaultBlockState(), Blocks.CRIMSON_DOOR.defaultBlockState()}) {
            assertTrue(Semantics.openableByHand(state), state + " 能用手开");
            assertTrue(kinds(state).contains(Kind.DOOR));
            assertTrue(Semantics.toggled(state).getValue(BlockStateProperties.OPEN), "开关一下就开了");
        }
        for (BlockState state : new BlockState[] {Blocks.IRON_DOOR.defaultBlockState(), Blocks.IRON_TRAPDOOR.defaultBlockState()}) {
            assertFalse(Semantics.openableByHand(state), state + " 只能靠红石");
            assertFalse(kinds(state).contains(Kind.DOOR));
            assertThrows(IllegalArgumentException.class, () -> Semantics.toggled(state));
        }
    }

    @Test
    void hazards() {
        for (BlockState state : new BlockState[] {Blocks.FIRE.defaultBlockState(), Blocks.SOUL_FIRE.defaultBlockState(),
                Blocks.CACTUS.defaultBlockState(), Blocks.SWEET_BERRY_BUSH.defaultBlockState(),
                Blocks.MAGMA_BLOCK.defaultBlockState(), Blocks.POWDER_SNOW.defaultBlockState(),
                Blocks.COBWEB.defaultBlockState(), Blocks.WITHER_ROSE.defaultBlockState(),
                Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true), Blocks.END_PORTAL.defaultBlockState(),
                Blocks.END_GATEWAY.defaultBlockState()}) {
            assertTrue(kinds(state).contains(Kind.HAZARD), state + " 危险");
        }
        assertFalse(kinds(Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false)).contains(Kind.HAZARD),
                "熄了的营火不烫");
        assertTrue(kinds(Blocks.STONE.defaultBlockState()).isEmpty());
    }

    @Test
    void fallingTriggersAndFragile() {
        assertTrue(kinds(Blocks.SAND.defaultBlockState()).contains(Kind.FALLING));
        assertTrue(kinds(Blocks.GRAVEL.defaultBlockState()).contains(Kind.FALLING));
        assertTrue(kinds(Blocks.STONE_PRESSURE_PLATE.defaultBlockState()).contains(Kind.TRIGGER));
        assertTrue(kinds(Blocks.OAK_PRESSURE_PLATE.defaultBlockState()).contains(Kind.TRIGGER));
        assertTrue(kinds(Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE.defaultBlockState()).contains(Kind.TRIGGER));
        assertTrue(kinds(Blocks.TRIPWIRE.defaultBlockState()).contains(Kind.TRIGGER));
        assertTrue(kinds(Blocks.FARMLAND.defaultBlockState()).contains(Kind.FRAGILE));
        assertTrue(kinds(Blocks.TURTLE_EGG.defaultBlockState()).contains(Kind.FRAGILE));
    }

    @Test
    void theBlocksWhoseCollisionFollowsTheWorldOrTheBodyAreNamed() {
        for (BlockState state : new BlockState[] {Blocks.SCAFFOLDING.defaultBlockState(), Blocks.POWDER_SNOW.defaultBlockState(),
                Blocks.BAMBOO.defaultBlockState(), Blocks.POINTED_DRIPSTONE.defaultBlockState(),
                Blocks.SHULKER_BOX.defaultBlockState(), Blocks.MOVING_PISTON.defaultBlockState()}) {
            assertTrue(Semantics.dynamicCollision(state), state.toString());
        }
        assertFalse(Semantics.dynamicCollision(Blocks.OAK_STAIRS.defaultBlockState()));
    }

    @Test
    void honeyHalvesTheJumpFactor() {
        TestWorld honey = new TestWorld().set(AT, Blocks.HONEY_BLOCK.defaultBlockState());
        assertEquals(0.5, Semantics.jumpFactor(honey, 0, 64 + 15 / 16.0, 0), 1e-6);
        TestWorld stone = new TestWorld().set(AT, Blocks.STONE.defaultBlockState());
        assertEquals(1.0, Semantics.jumpFactor(stone, 0, 65, 0), 1e-6);
    }

    @Test
    void soulSandSlowsTheFeetAndWaterOverTheEyesIsNoticed() {
        TestWorld sand = new TestWorld().set(AT.below(), Blocks.SOUL_SAND.defaultBlockState());
        assertEquals(0.4, Semantics.speedFactor(sand, 0, AT.getY() - 0.125, 0), 1e-6);
        assertEquals(1.0, Semantics.speedFactor(new TestWorld().set(AT.below(), Blocks.STONE.defaultBlockState()), 0, AT.getY(), 0));
        TestWorld pool = new TestWorld().set(AT, Blocks.WATER.defaultBlockState()).set(AT.above(), Blocks.WATER.defaultBlockState());
        assertTrue(Semantics.eyeInWater(pool, 0.5, AT.getY() + 1.62, 0.5));
        TestWorld shallow = new TestWorld().set(AT, Blocks.WATER.defaultBlockState());
        assertFalse(Semantics.eyeInWater(shallow, 0.5, AT.getY() + 1.62, 0.5), "浅水只没过脚");
    }
}
