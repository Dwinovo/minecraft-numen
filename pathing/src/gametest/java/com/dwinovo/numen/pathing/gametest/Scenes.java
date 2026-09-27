package com.dwinovo.numen.pathing.gametest;

import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

/** 搭场景常用的几样方块,以及几条常用的收场断言。 */
final class Scenes {

    private Scenes() {}

    static BlockState stair(Direction facing, Half half) {
        return Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, half);
    }

    static BlockState stair(Direction facing) {
        return stair(facing, Half.BOTTOM);
    }

    static BlockState slab(SlabType type) {
        return Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, type);
    }

    static BlockState trapdoor(Block block, Direction facing, Half half, boolean open) {
        return block.defaultBlockState().setValue(TrapDoorBlock.FACING, facing).setValue(TrapDoorBlock.HALF, half)
                .setValue(TrapDoorBlock.OPEN, open);
    }

    /** 在 {@code (x, y, z)} 立一扇门(下半在这一格,上半在上面一格)。 */
    static void door(Trial t, int x, int y, int z, Block block, Direction facing, boolean open) {
        BlockState lower = block.defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.OPEN, open)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        t.set(x, y, z, lower);
        t.set(x, y + 1, z, lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    /** 一路没起跳:迈步上坎是瞬间抬上去的,起跳会留下 0.3 以上往上的速度。 */
    static void noJump(Trial.Run r) {
        if (r.highestRise > 0.3) {
            throw new GameTestAssertException("路上起跳了:往上的速度到过 " + r.highestRise);
        }
    }

    /** 每刻看一眼:腾空那几刻身体一直朝着离地那一刻的方向(差不过 90 度),空中没回身。 */
    static java.util.function.Consumer<Trial.Run> noTurnInMidAir() {
        float[] takeoff = {Float.NaN};
        return r -> {
            if (r.body.onGround() || r.body.onClimbable() || r.body.isInWater()) {
                takeoff[0] = Float.NaN;
                return;
            }
            if (Float.isNaN(takeoff[0])) {
                takeoff[0] = r.body.getYRot();
            } else if (Math.abs(net.minecraft.util.Mth.wrapDegrees(r.body.getYRot() - takeoff[0])) > 90) {
                throw new GameTestAssertException("空中回了身:离地时 yaw " + takeoff[0] + ",此刻 " + r.body.getYRot());
            }
        };
    }

    /** 实际账里没有挖、没有放(开关门不算)。 */
    static void unaltered(Trial.Run r) {
        if (r.report.ledger().alterations() != 0) {
            throw new GameTestAssertException("改了地形:" + r.report.ledger().entries());
        }
    }
}
