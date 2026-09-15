package com.dwinovo.numen.core.scan;

import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.Verdict;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 分团的钉桩:相连(对角也算)成团;挖掉它权限层说法不同的相邻格分成两团;超过阈值的团按 section 切块、
 * 跨区块边界的小团不切;按离中心最近排、只整理最近的若干团;格数、包围盒、流体源头的记账。
 * 需要 MC 注册表(方块与规则的信号),无头引导失败时跳过。
 */
@Tag("mc")
class BlockGroupsTest {

    private static final BlockPos CENTER = new BlockPos(0, 64, 0);

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
        assumeTrue(booted, "Minecraft 引导不可用,跳过分团钉桩");
    }

    private static BlockState log() {
        return Blocks.OAK_LOG.defaultBlockState();
    }

    @Test
    void touchingCellsIncludingDiagonalsAreOneGroupAndApartCellsAnother() {
        BlockGroups groups = new BlockGroups();
        groups.add(new BlockPos(1, 64, 1), log(), Verdict.allow());
        groups.add(new BlockPos(2, 65, 2), log(), Verdict.allow());   // 只隔一条对角
        groups.add(new BlockPos(3, 66, 2), log(), Verdict.allow());
        groups.add(new BlockPos(8, 64, 1), log(), Verdict.allow());   // 隔开了

        BlockGroups.Grouped grouped = groups.grouped(CENTER, 16);
        assertEquals(2, grouped.total());
        assertEquals(3, grouped.nearest().get(0).cells().size());
        assertEquals(1, grouped.nearest().get(1).cells().size());
    }

    @Test
    void aDifferentAnswerForBreakingSplitsTouchingCells() {
        Verdict placed = Verdict.ask(Rule.parse("break(placed)"));
        BlockGroups groups = new BlockGroups();
        for (int y = 64; y < 67; y++) {
            groups.add(new BlockPos(2, y, 0), log(), placed);        // 玩家放的原木柱
            groups.add(new BlockPos(3, y, 0), log(), Verdict.allow()); // 贴着它的野树
        }
        groups.add(new BlockPos(3, 67, 0), log(), Verdict.allow());

        BlockGroups.Grouped grouped = groups.grouped(CENTER, 16);
        assertEquals(2, grouped.total());
        BlockGroups.Group pillar = grouped.nearest().get(0);
        BlockGroups.Group tree = grouped.nearest().get(1);
        assertEquals(placed, pillar.verdict());
        assertEquals(3, pillar.cells().size());
        assertTrue(pillar.cells().keySet().stream().allMatch(p -> p.getX() == 2));
        assertEquals(Verdict.allow(), tree.verdict());
        assertEquals(4, tree.cells().size());
    }

    @Test
    void askAnswersFromDifferentRulesAreDifferentGroups() {
        BlockGroups groups = new BlockGroups();
        groups.add(new BlockPos(1, 64, 0), log(), Verdict.ask(Rule.parse("break(placed)")));
        groups.add(new BlockPos(2, 64, 0), log(), Verdict.ask(Rule.parse("break(block_entity)")));
        groups.add(new BlockPos(3, 64, 0), log(), Verdict.deny("observe mode: break would change the world"));
        assertEquals(3, groups.grouped(CENTER, 16).total());
    }

    @Test
    void aSmallGroupAcrossAChunkBorderStaysWhole() {
        BlockGroups groups = new BlockGroups();
        for (int x = 13; x <= 18; x++) {
            groups.add(new BlockPos(x, 64, 15), log(), Verdict.allow());
            groups.add(new BlockPos(x, 64, 16), log(), Verdict.allow());
        }
        BlockGroups.Grouped grouped = groups.grouped(CENTER, 16);
        assertEquals(1, grouped.total());
        assertEquals(12, grouped.nearest().get(0).cells().size());
    }

    @Test
    void aGroupOverTheThresholdIsCutAlongSectionLines() {
        BlockGroups groups = new BlockGroups();
        // 20×20 一层 = 400 格,跨 x=16 与 z=16 两条区块线
        for (int x = 8; x < 28; x++) {
            for (int z = 8; z < 28; z++) {
                groups.add(new BlockPos(x, 64, z), Blocks.STONE.defaultBlockState(), Verdict.allow());
            }
        }
        assertTrue(groups.size() > BlockGroups.SPLIT_ABOVE);
        BlockGroups.Grouped grouped = groups.grouped(CENTER, 16);
        assertEquals(4, grouped.total());
        int cells = 0;
        Set<Long> sections = new HashSet<>();
        for (BlockGroups.Group piece : grouped.nearest()) {
            cells += piece.cells().size();
            Set<Long> own = new HashSet<>();
            for (BlockPos p : piece.cells().keySet()) {
                own.add(net.minecraft.core.SectionPos.blockToSection(p.asLong()));
            }
            assertEquals(1, own.size(), "a piece spans more than one section");
            assertTrue(sections.add(own.iterator().next()), "two pieces share a section");
        }
        assertEquals(400, cells);
    }

    @Test
    void groupsComeNearestFirstAndOnlyTheNearestAreListed() {
        BlockGroups groups = new BlockGroups();
        for (int i = 0; i < 5; i++) {
            groups.add(new BlockPos(40 - 8 * i, 64, 0), log(), Verdict.allow());
        }
        BlockGroups.Grouped grouped = groups.grouped(CENTER, 2);
        assertEquals(5, grouped.total());
        assertEquals(2, grouped.nearest().size());
        assertEquals(new BlockPos(8, 64, 0), grouped.nearest().get(0).nearest());
        assertEquals(8.0, grouped.nearest().get(0).distance());
        assertEquals(new BlockPos(16, 64, 0), grouped.nearest().get(1).nearest());
    }

    @Test
    void aGroupCountsItsBlocksBoxAndFluidSources() {
        BlockState source = Blocks.WATER.defaultBlockState();
        BlockState flowing = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3);
        BlockGroups groups = new BlockGroups();
        groups.add(new BlockPos(5, 60, 5), source, Verdict.allow());
        groups.add(new BlockPos(6, 60, 5), source, Verdict.allow());
        groups.add(new BlockPos(7, 61, 6), flowing, Verdict.allow());

        BlockGroups.Group water = groups.grouped(CENTER, 16).nearest().get(0);
        assertEquals(List.of(new BlockPos(5, 60, 5), new BlockPos(6, 60, 5), new BlockPos(7, 61, 6)),
                List.copyOf(water.cells().keySet()));
        assertEquals(3, water.counts().get(Blocks.WATER));
        assertEquals(new BlockPos(5, 60, 5), water.min());
        assertEquals(new BlockPos(7, 61, 6), water.max());
        assertEquals(3, water.fluidCells());
        assertEquals(2, water.sources());
    }
}
