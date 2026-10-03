package com.dwinovo.numen.core.scan;


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
 * 分团的钉桩:相连(对角也算)成团,谁放的都一样;超过阈值的团按 section 切块、
 * 跨区块边界的小团不切;按离中心最近排、只整理最近的若干团;格数、包围盒、流体源头的记账。
 * 需要 MC 注册表(方块),无头引导失败时跳过。
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
        groups.add(new BlockPos(1, 64, 1), log());
        groups.add(new BlockPos(2, 65, 2), log());   // 只隔一条对角
        groups.add(new BlockPos(3, 66, 2), log());
        groups.add(new BlockPos(8, 64, 1), log());   // 隔开了

        List<BlockGroups.Group> grouped = groups.grouped(CENTER);
        assertEquals(2, grouped.size());
        assertEquals(3, grouped.get(0).cells().size());
        assertEquals(1, grouped.get(1).cells().size());
    }

    /** 团只由相连定:玩家放的原木柱贴着一棵野树,是一团——挖不挖得成由挖的那一下问权限层,看的时候不分。 */
    @Test
    void touchingCellsAreOneGroupWhoeverPlacedThem() {
        BlockGroups groups = new BlockGroups();
        for (int y = 64; y < 67; y++) {
            groups.add(new BlockPos(2, y, 0), log());
            groups.add(new BlockPos(3, y, 0), log());
        }
        groups.add(new BlockPos(3, 67, 0), log());

        List<BlockGroups.Group> grouped = groups.grouped(CENTER);
        assertEquals(1, grouped.size());
        assertEquals(7, grouped.get(0).cells().size());
    }

    @Test
    void aSmallGroupAcrossAChunkBorderStaysWhole() {
        BlockGroups groups = new BlockGroups();
        for (int x = 13; x <= 18; x++) {
            groups.add(new BlockPos(x, 64, 15), log());
            groups.add(new BlockPos(x, 64, 16), log());
        }
        List<BlockGroups.Group> grouped = groups.grouped(CENTER);
        assertEquals(1, grouped.size());
        assertEquals(12, grouped.get(0).cells().size());
    }

    @Test
    void aGroupOverTheThresholdIsCutAlongSectionLines() {
        BlockGroups groups = new BlockGroups();
        // 20×20 一层 = 400 格,跨 x=16 与 z=16 两条区块线
        for (int x = 8; x < 28; x++) {
            for (int z = 8; z < 28; z++) {
                groups.add(new BlockPos(x, 64, z), Blocks.STONE.defaultBlockState());
            }
        }
        assertTrue(groups.size() > BlockGroups.SPLIT_ABOVE);
        List<BlockGroups.Group> grouped = groups.grouped(CENTER);
        assertEquals(4, grouped.size());
        int cells = 0;
        Set<Long> sections = new HashSet<>();
        for (BlockGroups.Group piece : grouped) {
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
    void groupsComeNearestFirst() {
        BlockGroups groups = new BlockGroups();
        for (int i = 0; i < 5; i++) {
            groups.add(new BlockPos(40 - 8 * i, 64, 0), log());
        }
        List<BlockGroups.Group> grouped = groups.grouped(CENTER);
        assertEquals(5, grouped.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(new BlockPos(8 + 8 * i, 64, 0), grouped.get(i).nearest(), "由近及远排");
            assertEquals(8.0 + 8 * i, grouped.get(i).distance());
        }
    }

    /** 一团带着每一格看到的方块状态(连同流体的源头与流动)。 */
    @Test
    void aGroupKeepsTheStateSeenInEachCell() {
        BlockState source = Blocks.WATER.defaultBlockState();
        BlockState flowing = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3);
        BlockGroups groups = new BlockGroups();
        groups.add(new BlockPos(5, 60, 5), source);
        groups.add(new BlockPos(6, 60, 5), source);
        groups.add(new BlockPos(7, 61, 6), flowing);

        BlockGroups.Group water = groups.grouped(CENTER).get(0);
        assertEquals(List.of(new BlockPos(5, 60, 5), new BlockPos(6, 60, 5), new BlockPos(7, 61, 6)),
                List.copyOf(water.cells().keySet()));
        assertEquals(List.of(source, source, flowing), List.copyOf(water.cells().values()));
    }
}
