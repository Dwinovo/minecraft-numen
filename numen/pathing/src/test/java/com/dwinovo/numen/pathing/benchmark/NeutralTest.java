package com.dwinovo.numen.pathing.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.benchmark.Neutral.Cls;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 方块状态到中立类别:每个类别各举几个有代表性的状态,边界(半高、流水、含水的实心)单独点出。 */
class NeutralTest {

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    private static void is(Cls expected, Block... blocks) {
        for (Block block : blocks) {
            assertEquals(expected, Neutral.of(block.defaultBlockState()), block.getDescriptionId());
        }
    }

    @Test
    void passableAndSolidClasses() {
        is(Cls.AIR, Blocks.AIR, Blocks.SHORT_GRASS, Blocks.TORCH, Blocks.WHITE_CARPET);
        is(Cls.SOFT, Blocks.DIRT, Blocks.SAND, Blocks.OAK_LEAVES, Blocks.GRASS_BLOCK);
        is(Cls.STONE, Blocks.STONE, Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.STONE_BRICKS);
        is(Cls.HARD, Blocks.DEEPSLATE, Blocks.IRON_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.OBSIDIAN);
        is(Cls.BEDROCK, Blocks.BEDROCK);
    }

    @Test
    void liquidsAndDangers() {
        is(Cls.WATER, Blocks.WATER, Blocks.SEAGRASS, Blocks.KELP);
        BlockState flowing = Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 3);
        assertEquals(Cls.FORBIDDEN, Neutral.of(flowing));
        is(Cls.FORBIDDEN, Blocks.LAVA, Blocks.FIRE, Blocks.CACTUS, Blocks.POWDER_SNOW, Blocks.COBWEB, Blocks.MAGMA_BLOCK,
                Blocks.OAK_PRESSURE_PLATE, Blocks.TRIPWIRE, Blocks.FARMLAND, Blocks.TURTLE_EGG);
    }

    @Test
    void climbDoorTall() {
        is(Cls.CLIMB, Blocks.LADDER, Blocks.VINE, Blocks.SCAFFOLDING);
        is(Cls.DOOR, Blocks.OAK_DOOR, Blocks.OAK_TRAPDOOR, Blocks.OAK_FENCE_GATE);
        is(Cls.TALL, Blocks.OAK_FENCE, Blocks.COBBLESTONE_WALL);
    }

    @Test
    void halfHeightFollowsCollisionHeight() {
        // 半高方块按碰撞高度归类:底部半砖 0.5 是实心,雪层(一层碰撞为空)是空气;含水的半砖仍是实心
        assertEquals(Cls.STONE, Neutral.of(Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM)));
        assertEquals(Cls.AIR, Neutral.of(Blocks.SNOW.defaultBlockState()));
        assertEquals(Cls.STONE, Neutral.of(Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)));
    }
}
