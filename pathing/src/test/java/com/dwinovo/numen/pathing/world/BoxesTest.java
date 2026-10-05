package com.dwinovo.numen.pathing.world;

import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 碰撞箱的分类:全空、整块还是别的形状,由同一份碰撞箱推出。对原版的每一个方块状态、每一种身体(包括细雪托得住的、
 * 冻得住水面的)、脚在它上面与脚在它里面两种高度逐一核对:算作全空的真的没有碰撞箱,算作整块的真的只有这一格整块,
 * 其余都不是这两样。迈步按整块位图推导(见 {@code SteppingTest}),靠的就是这一条。
 */
class BoxesTest {

    private static final int Y = 64;
    private static final BlockPos AT = new BlockPos(0, Y, 0);
    private static final AABB UNIT = new AABB(0, 0, 0, 1, 1, 1);

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    @Test
    void everyBlockStateIsClassifiedByItsOwnCollisionBoxes() {
        BodyStats[] bodies = {Vanilla.SURVIVAL, Vanilla.LEATHER_BOOTS, Vanilla.FROST_WALKER};
        double[] feet = {Y, Y + 1};
        int states = 0;
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            TestWorld world = new TestWorld().set(AT, state);
            for (BodyStats body : bodies) {
                for (double f : feet) {
                    Boxes.Shape shape = Boxes.shape(world, body, AT.getX(), AT.getY(), AT.getZ(), state, f);
                    AABB[] boxes = shape.boxes();
                    assertArrayEquals(boxes, Boxes.at(world, body, AT.getX(), AT.getY(), AT.getZ(), state, f),
                            state + " 的碰撞箱与分类出自同一份");
                    boolean empty = boxes.length == 0;
                    boolean whole = boxes.length == 1 && boxes[0].equals(UNIT);
                    Boxes.Fill expected = empty ? Boxes.Fill.EMPTY : whole ? Boxes.Fill.WHOLE : Boxes.Fill.PARTIAL;
                    assertEquals(expected, shape.fill(), state + " 脚在 " + f);
                }
            }
            states++;
        }
        assertFalse(states < 20_000, "原版方块状态没有读全:" + states);
    }

    @Test
    void commonShapesFallIntoTheirClasses() {
        assertEquals(Boxes.Fill.WHOLE, fill(Blocks.STONE.defaultBlockState()));
        assertEquals(Boxes.Fill.WHOLE, fill(Blocks.OAK_LEAVES.defaultBlockState()));
        assertEquals(Boxes.Fill.EMPTY, fill(Blocks.AIR.defaultBlockState()));
        assertEquals(Boxes.Fill.EMPTY, fill(Blocks.SHORT_GRASS.defaultBlockState()));
        assertEquals(Boxes.Fill.EMPTY, fill(Blocks.WATER.defaultBlockState()));
        assertEquals(Boxes.Fill.PARTIAL, fill(Blocks.OAK_SLAB.defaultBlockState()));
        assertEquals(Boxes.Fill.PARTIAL, fill(Blocks.OAK_STAIRS.defaultBlockState()));
        assertEquals(Boxes.Fill.PARTIAL, fill(Blocks.SOUL_SAND.defaultBlockState()));
    }

    private static Boxes.Fill fill(BlockState state) {
        return Boxes.shape(new TestWorld().set(AT, state), Vanilla.SURVIVAL, 0, Y, 0, state, Y + 1).fill();
    }
}
