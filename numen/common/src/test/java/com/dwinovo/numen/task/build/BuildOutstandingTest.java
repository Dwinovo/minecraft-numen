package com.dwinovo.numen.task.build;

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
 * 没放好的格:回执按原因分组、按原因的固定先后排,每组数出格数、列出前几格的位置,说明点出方块与位置与细节,说不清楚的不写。
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

    private static BuildOutstanding.Cell poppy(BlockPos pos, BuildOutstanding.Cause cause, String detail) {
        return new BuildOutstanding.Cell(cause, new BuildTaskRecord.Target(Blocks.POPPY, Items.POPPY, pos, "poppy"),
                Blocks.AIR.defaultBlockState(), detail);
    }

    private static BuildOutstanding.Cell stone(BlockPos pos, BuildOutstanding.Cause cause, String detail) {
        return new BuildOutstanding.Cell(cause, new BuildTaskRecord.Target(Blocks.STONE, Items.STONE, pos, "stone"),
                Blocks.AIR.defaultBlockState(), detail);
    }

    @Test
    void groupsComeInTheFixedOrderWithTheirCellsAndPositions() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过缺格清单钉桩");
        BlockPos first = new BlockPos(100, 65, -20);
        BlockPos second = new BlockPos(100, 65, -18);
        BlockPos wall = new BlockPos(103, 64, -19);
        var report = BuildOutstanding.report(List.of(
                poppy(first, BuildOutstanding.Cause.DID_NOT_STAY, "poppy at 100,65,-20 cannot stay"),
                stone(wall, BuildOutstanding.Cause.BLOCKED_BY_ENTITY, "you are standing in it"),
                poppy(second, BuildOutstanding.Cause.DID_NOT_STAY, "poppy at 100,65,-18 cannot stay")), "");

        assertEquals(2, report.size());
        assertEquals("blocked_by_entity", report.get(0).kind(), "原因按固定的先后排:有人站着在前");
        assertEquals("did_not_stay", report.get(1).kind());
        assertEquals(2, report.get(1).cells());
        assertEquals(List.of(first, second), report.get(1).first());
        assertTrue(report.get(0).says().contains("1 stone (103,64,-19)") && report.get(0).says().contains("you are standing in it"),
                report.get(0).says());
        assertTrue(report.get(1).says().contains("poppy at 100,65,-20 cannot stay")
                && report.get(1).says().contains("poppy at 100,65,-18 cannot stay"), report.get(1).says());
    }

    @Test
    void liquidCellsAreOneGroupThatCountsEachKindAndSaysWhatToDoNext() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过缺格清单钉桩");
        var water = new BuildOutstanding.Cell(BuildOutstanding.Cause.LIQUID,
                new BuildTaskRecord.Target(Blocks.WATER, Items.AIR, new BlockPos(0, 64, 0), "water"),
                Blocks.AIR.defaultBlockState(), BuildOutstanding.LIQUID_PLACE);
        var drain = new BuildOutstanding.Cell(BuildOutstanding.Cause.LIQUID,
                new BuildTaskRecord.Target(Blocks.AIR, Items.AIR, new BlockPos(1, 64, 0), "air"),
                Blocks.WATER.defaultBlockState(), BuildOutstanding.LIQUID_DRAIN);
        var wet = new BuildOutstanding.Cell(BuildOutstanding.Cause.LIQUID,
                new BuildTaskRecord.Target(Blocks.OAK_STAIRS, Items.OAK_STAIRS, new BlockPos(2, 64, 0), "stairs"),
                Blocks.OAK_STAIRS.defaultBlockState(), BuildOutstanding.LIQUID_HELD);
        var report = BuildOutstanding.report(List.of(water, drain, wet, drain), "");
        assertEquals(1, report.size());
        assertEquals("liquid", report.get(0).kind());
        assertEquals(4, report.get(0).cells());
        String says = report.get(0).says();
        assertTrue(says.contains("1 cell(s): " + BuildOutstanding.LIQUID_PLACE) && says.contains("2 cell(s): " + BuildOutstanding.LIQUID_DRAIN)
                && says.contains("1 cell(s): " + BuildOutstanding.LIQUID_HELD) && says.contains("1 water (0,64,0)"), says);
        assertTrue(report.get(0).hint().orElse("").contains("numen.use.block"), report.get(0).hint().toString());
    }

    @Test
    void theFirstFewPositionsAreListedNotAllOfThem() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过缺格清单钉桩");
        List<BuildOutstanding.Cell> cells = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            cells.add(stone(new BlockPos(i, 64, 0), BuildOutstanding.Cause.SHORT_OF_MATERIAL, null));
        }
        var report = BuildOutstanding.report(cells, "40 stone");
        assertEquals(1, report.size());
        assertEquals(40, report.get(0).cells());
        assertEquals(5, report.get(0).first().size());
        assertTrue(report.get(0).says().contains("you still need 40 stone"), report.get(0).says());
        assertTrue(report.get(0).hint().isPresent());
    }
}
