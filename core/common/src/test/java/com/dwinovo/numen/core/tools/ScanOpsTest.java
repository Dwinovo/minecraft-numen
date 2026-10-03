package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.core.scan.BlockGroups;
import com.dwinovo.numen.core.scan.BlockSearch;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.Verdict;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ScanOpsTest {

    /** 团的回执那几条要 MC 注册表(方块与规则);覆盖说明的几条不要。无头引导失败时只跳过前者。 */
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

    private static BlockSearch.ScanResult result(int scanned, int unloaded, int total, boolean sectionCapHit) {
        return new BlockSearch.ScanResult(List.of(), scanned, unloaded, total, sectionCapHit, false, false);
    }

    @Test
    void aScanThatCoveredEverythingSaysNothingExtra() {
        assertNull(ScanOps.coverageNote(result(625, 0, 625, false)));
    }

    @Test
    void skippedColumnsReadAsUnknownNotAsEmpty() {
        String note = ScanOps.coverageNote(result(25, 600, 625, false));
        assertTrue(note.contains("600 of 625"), note);
        assertTrue(note.contains("UNKNOWN, not absent"), note);
    }

    @Test
    void aSectionCapSaysHowFarTheWalkGot() {
        String note = ScanOps.coverageNote(result(180, 0, 625, true));
        assertTrue(note.contains("180/625"), note);
        assertTrue(note.contains("reads at most"), note);
    }

    /** Both limits can bite in one scan; neither may silently swallow the other. */
    @Test
    void aSectionCapDoesNotHideTheUnsearchedColumns() {
        String note = ScanOps.coverageNote(result(180, 300, 625, true));
        assertTrue(note.contains("reads at most"), note);
        assertTrue(note.contains("300 of 625"), note);
    }

    /**
     * Stopping early is a proof, not a shortcut: the nearest ones are already closer than
     * anything the unwalked rings could hold, so warning about coverage would be a lie.
     */
    @Test
    void stoppingEarlyOnTheRingBoundWarnsAboutNothing() {
        assertNull(ScanOps.coverageNote(
                new BlockSearch.ScanResult(List.of(), 41, 0, 625, false, true, false)));
    }

    /** Unloaded ground is still unloaded even when the quota was met early. */
    @Test
    void stoppingEarlyStillReportsGroundNobodyLookedAt() {
        String note = ScanOps.coverageNote(
                new BlockSearch.ScanResult(List.of(), 41, 12, 625, false, true, false));
        assertTrue(note.contains("12 of 625"), note);
    }

    /** The collect cap cuts a scan short like the section cap does, and the model has to be told so. */
    @Test
    void theCollectCapSaysGroupsAtTheEdgeMayBeCutOff() {
        BlockSearch.ScanResult capped = new BlockSearch.ScanResult(List.of(), 3, 0, 625, false, false, true);
        assertFalse(capped.coveredEverything());
        String note = ScanOps.coverageNote(capped);
        assertTrue(note.contains("stopped at " + BlockSearch.MAX_COLLECT), note);
        assertTrue(note.contains("cut off"), note);
    }

    /** A small group lists every cell; a group over the listing limit is only summarised. */
    @Test
    void aSmallGroupListsItsCellsAndABigOneIsSummarised() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过团回执钉桩");
        BlockPos center = new BlockPos(0, 64, 0);
        BlockGroups groups = new BlockGroups();
        // 一圈末地传送门框架:12 格围着 3×3 的口,四角空着,靠对角相连成一团
        for (int d = 1; d <= 3; d++) {
            for (BlockPos p : List.of(new BlockPos(3 + d, 64, 4), new BlockPos(3 + d, 64, 8),
                    new BlockPos(3, 64, 4 + d), new BlockPos(7, 64, 4 + d))) {
                groups.add(p, Blocks.END_PORTAL_FRAME.defaultBlockState(), Verdict.allow());
            }
        }
        for (int i = 0; i < AreaText.LIST_CELLS_UP_TO + 1; i++) {
            groups.add(new BlockPos(-40 - i, 70, 0), Blocks.OAK_LOG.defaultBlockState(), Verdict.allow());
        }
        List<BlockGroups.Group> grouped = groups.grouped(center);
        assertEquals(2, grouped.size());

        JsonObject small = ScanOps.groupJson("ores/g7", grouped.get(0), center, 100L);
        assertEquals("ores/g7", small.get("id").getAsString());
        assertEquals(12, small.get("count").getAsInt());
        assertEquals(12, small.getAsJsonObject("blocks").get("minecraft:end_portal_frame").getAsInt());
        assertEquals(12, small.getAsJsonArray("positions").size());
        assertEquals(com.dwinovo.numen.cli.Shapes.pos(new BlockPos(4, 64, 4)), small.getAsJsonArray("positions").get(0),
                "逐格是 Pos");
        assertEquals("allow", small.get("permission").getAsString());
        assertFalse(small.has("reason"));
        assertFalse(small.has("sources"));
        assertEquals("south-east", small.getAsJsonObject("nearest").get("direction").getAsString());
        assertEquals("minecraft:end_portal_frame", small.getAsJsonObject("nearest").get("name").getAsString(),
                "最近一格带着看到的方块与它的 pos,原样能交给 work.dig");
        assertEquals(com.dwinovo.numen.cli.Shapes.pos(new BlockPos(4, 64, 4)),
                small.getAsJsonObject("nearest").get("pos"));

        JsonObject big = ScanOps.groupJson(null, grouped.get(1), center, 100L);
        assertFalse(big.has("id"), "只是看、没存:团没有编号");
        assertEquals(AreaText.LIST_CELLS_UP_TO + 1, big.get("count").getAsInt());
        assertFalse(big.has("positions"));
        assertEquals("west, 6 up", big.getAsJsonObject("nearest").get("direction").getAsString());
    }

    /** A group that is not allowed carries the permission layer's own reason. */
    @Test
    void aGroupThatNeedsConsentSaysWhy() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过团回执钉桩");
        BlockPos center = new BlockPos(0, 64, 0);
        BlockGroups groups = new BlockGroups();
        groups.add(new BlockPos(1, 64, 0), Blocks.OAK_LOG.defaultBlockState(), Verdict.ask(Rule.parse("break(placed)")));
        JsonObject json = ScanOps.groupJson(null, groups.grouped(center).get(0), center, 100L);
        assertEquals("ask", json.get("permission").getAsString());
        assertTrue(json.get("reason").getAsString().contains("placed by a player"), json.toString());
    }

    /** 一部分附带的方块说出各种几格、流体的源头几格;包围盒写成 {@code area add --box} 收的样子。 */
    @Test
    void aPartCountsItsBlocksAndFluidSourcesAndWritesItsBox() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过区域说法钉桩");
        BlockState source = Blocks.WATER.defaultBlockState();
        BlockState flowing = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3);
        Map<BlockPos, BlockState> seen = new LinkedHashMap<>();
        seen.put(new BlockPos(5, 60, 5), source);
        seen.put(new BlockPos(6, 60, 5), source);
        seen.put(new BlockPos(7, 61, 6), flowing);
        Cells water = Cells.seen(seen, 7L);
        JsonObject part = AreaText.part("pond/g1", water, new BlockPos(0, 60, 0));
        assertEquals(3, part.getAsJsonObject("blocks").get("minecraft:water").getAsInt());
        assertEquals(2, part.get("sources").getAsInt());
        assertEquals("5,60,5 7,61,6", AreaText.box(water.bounds()));
        JsonObject framed = AreaText.part("pond/b1", Cells.box(new BlockPos(0, 60, 0), new BlockPos(1, 60, 0)),
                new BlockPos(3, 60, 0));
        assertFalse(framed.has("blocks"), "框出来的格不附带方块");
        assertEquals(com.dwinovo.numen.cli.Shapes.pos(new BlockPos(1, 60, 0)), framed.getAsJsonArray("positions").get(0),
                "逐格由近及远列");
    }
}
