package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.scan.BlockGroups;
import com.dwinovo.numen.core.scan.BlockSearch;
import com.dwinovo.numen.sdk.BlockAt;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    /** 一团是它的每一格(近的在前,各是一个 Block),最近的那一格,格数;大团也一格不少。 */
    @Test
    void aClusterIsEveryBlockNearestFirstWithItsNearestAndCount() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过团回执钉桩");
        BlockPos center = new BlockPos(0, 64, 0);
        BlockGroups groups = new BlockGroups();
        // 一圈末地传送门框架:12 格围着 3×3 的口,四角空着,靠对角相连成一团
        for (int d = 1; d <= 3; d++) {
            for (BlockPos p : List.of(new BlockPos(3 + d, 64, 4), new BlockPos(3 + d, 64, 8),
                    new BlockPos(3, 64, 4 + d), new BlockPos(7, 64, 4 + d))) {
                groups.add(p, Blocks.END_PORTAL_FRAME.defaultBlockState());
            }
        }
        for (int i = 0; i < 200; i++) {
            groups.add(new BlockPos(-40 - i, 70, 0), Blocks.OAK_LOG.defaultBlockState());
        }
        List<BlockGroups.Group> grouped = groups.grouped(center);
        assertEquals(2, grouped.size());

        ScanOps.Cluster small = ScanOps.cluster(grouped.get(0));
        assertEquals(12, small.count());
        assertEquals(12, small.blocks().size());
        BlockAt first = small.blocks().get(0);
        assertEquals("minecraft:end_portal_frame", first.name());
        assertEquals(new BlockPos(4, 64, 4), first.pos(), "近的在前,每格是 Block");
        assertEquals(new BlockAt("minecraft:end_portal_frame", new BlockPos(4, 64, 4)), small.nearest(),
                "最近一格带着看到的方块与它的 pos,原样能交给 numen.work.dig");

        ScanOps.Cluster big = ScanOps.cluster(grouped.get(1));
        assertEquals(200, big.count());
        assertEquals(200, big.blocks().size(), "大团也一格不少");
        assertEquals(new BlockPos(-40, 70, 0), big.nearest().pos());
    }
}
