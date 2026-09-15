package com.dwinovo.numen.core.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 团编号簿的钉桩:编号跨扫描单调递增、不回到 g1;每次扫描整本替换;不在最新一次扫描里的编号明确报出、
 * 并说清最新一次列了哪些;取用按编号合并格子。需要 MC 注册表(方块),无头引导失败时跳过。
 */
@Tag("mc")
class GroupBookTest {

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

    @BeforeEach
    void requireBoot() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过团簿钉桩");
    }

    private static Map<BlockPos, Block> cells(int x) {
        return Map.of(new BlockPos(x, 64, 0), Blocks.OAK_LOG, new BlockPos(x, 65, 0), Blocks.OAK_LOG);
    }

    @Test
    void idsKeepCountingUpAcrossScans() {
        GroupBook book = new GroupBook();
        assertEquals(List.of("g1", "g2"), book.replace(List.of(cells(0), cells(5))));
        assertEquals(List.of("g3"), book.replace(List.of(cells(9))));
        assertEquals(List.of(), book.replace(List.of()));
        assertEquals(List.of("g4", "g5"), book.replace(List.of(cells(1), cells(2))));
    }

    @Test
    void anIdFromAnEarlierScanIsStaleAndTheLatestIdsAreNamed() {
        GroupBook book = new GroupBook();
        book.replace(List.of(cells(0), cells(5)));
        book.replace(List.of(cells(9), cells(12), cells(15)));

        String stale = book.staleMessage(List.of("g1"));
        assertTrue(stale != null && stale.contains("g1") && stale.contains("g3 to g5")
                && stale.contains("scan_blocks again"), stale);
        assertNull(book.staleMessage(List.of("g3", "g5")));
        String mixed = book.staleMessage(List.of("g4", "g2"));
        assertTrue(mixed != null && mixed.contains("g2") && !mixed.contains("group g4"), mixed);
    }

    /** 新身体(重启后重放的活也是)还没扫描过:照实说没有扫描结果,不说"最近一次什么都没找到"。 */
    @Test
    void aBodyThatNeverScannedSaysThereIsNoScan() {
        String stale = new GroupBook().staleMessage(List.of("g3"));
        assertTrue(stale != null && stale.contains("no scan_blocks result") && stale.contains("g3")
                && !stale.contains("found no groups"), stale);
    }

    @Test
    void aScanThatFoundNothingMakesEveryOldIdStale() {
        GroupBook book = new GroupBook();
        book.replace(List.of(cells(0)));
        book.replace(List.of());
        String stale = book.staleMessage(List.of("g1"));
        assertTrue(stale != null && stale.contains("found no groups"), stale);
    }

    @Test
    void takingGroupsMergesTheirCellsWithTheRecordedBlocks() {
        GroupBook book = new GroupBook();
        book.replace(List.of(cells(0), cells(5), cells(9)));
        Map<BlockPos, Block> taken = book.cells(List.of("g1", "g3"));
        assertEquals(4, taken.size());
        assertEquals(Blocks.OAK_LOG, taken.get(new BlockPos(9, 65, 0)));
        assertNull(taken.get(new BlockPos(5, 64, 0)));
        // 取用不划掉:同一次扫描的编号可以再取
        assertNull(book.staleMessage(List.of("g1")));
    }
}
