package com.dwinovo.numen.task.build;

import com.dwinovo.numen.FailureType;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 收不了尾时的缺格清单:失败类型跟主导病因走,回执按病因 → 方块 → 坐标归堆。
 * 需要 MC 注册表,无头引导失败时跳过而不失败。
 */
@Tag("mc")
class BuildOutstandingTest {

    private static boolean booted;

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
    }

    private static BuildOutstanding.Cell poppy(BlockPos pos, BuildOutstanding.Cause cause) {
        return new BuildOutstanding.Cell(cause, new BuildTaskRecord.Target(Blocks.POPPY, Items.POPPY, pos, "poppy"),
                Blocks.AIR.defaultBlockState());
    }

    private static BuildOutstanding.Cell stone(BlockPos pos, BuildOutstanding.Cause cause) {
        return new BuildOutstanding.Cell(cause, new BuildTaskRecord.Target(Blocks.STONE, Items.STONE, pos, "stone"),
                Blocks.AIR.defaultBlockState());
    }

    @Test
    void failureFollowsTheDominantCause() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过缺格清单钉桩");
        BlockPos a = new BlockPos(0, 64, 0);
        BlockPos b = new BlockPos(1, 64, 0);
        BlockPos c = new BlockPos(2, 64, 0);

        assertEquals(FailureType.NO_SUPPORT, new BuildOutstanding(List.of(
                poppy(a, BuildOutstanding.Cause.UNSUPPORTED), poppy(b, BuildOutstanding.Cause.UNSUPPORTED),
                stone(c, BuildOutstanding.Cause.OCCUPIED)), 0).failure(),
                "两格立不住、一格有人站着:立不住的多,按立不住报,不是没有路");
        assertEquals(FailureType.ENTITY_BLOCKED, new BuildOutstanding(List.of(
                poppy(a, BuildOutstanding.Cause.UNSUPPORTED), stone(b, BuildOutstanding.Cause.OCCUPIED)), 0).failure(),
                "一样多时按病因的先后:有人站着排在前面");
        assertEquals(FailureType.NOT_KEPT, new BuildOutstanding(List.of(
                stone(a, BuildOutstanding.Cause.NOT_KEPT)), 0).failure());
    }

    @Test
    void receiptGroupsByCauseThenBlock() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过缺格清单钉桩");
        BlockPos first = new BlockPos(100, 65, -20);
        BlockPos second = new BlockPos(100, 65, -18);
        BlockPos wall = new BlockPos(103, 64, -19);
        BuildOutstanding outstanding = new BuildOutstanding(List.of(
                poppy(first, BuildOutstanding.Cause.UNSUPPORTED),
                stone(wall, BuildOutstanding.Cause.OCCUPIED),
                poppy(second, BuildOutstanding.Cause.UNSUPPORTED)), 0);

        String receipt = outstanding.describe();
        assertTrue(receipt.startsWith("3 cell(s) unbuilt"), receipt);
        String occupied = "1 blocked by someone standing there — ask them to step aside: 1 stone ("
                + xyz(wall) + ")";
        String unheld = "2 that vanilla physics will not hold at that spot (the design asks for something"
                + " impossible there): 2 poppy (" + xyz(first) + "; " + xyz(second) + ")";
        assertTrue(receipt.contains(occupied), receipt);
        assertTrue(receipt.contains(unheld), receipt);
        assertTrue(receipt.indexOf(occupied) < receipt.indexOf(unheld), "病因按固定的先后排:" + receipt);
    }

    private static String xyz(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
