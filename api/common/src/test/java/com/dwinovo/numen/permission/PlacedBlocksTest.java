package com.dwinovo.numen.permission;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 放置记录的记与清:记了就有,抹了就没;查到格子已是空气视为无记号并顺手清掉;跨区块各记各的;
 * 存档来回不丢。模式存档也来回一趟。
 */
@Tag("mc")
class PlacedBlocksTest {

    private static final PlacedBlocks.Placer STEVE =
            new PlacedBlocks.Placer(java.util.UUID.fromString("00000000-0000-0000-0000-0000000000aa"), "Steve");

    private static boolean booted;

    @BeforeAll
    static void boot() {
        booted = FakeWorld.boot();
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过放置记录钉桩");
    }

    @Test
    void recordForgetAndAirClearsItself() {
        PlacedBlocks placed = new PlacedBlocks();
        BlockPos a = new BlockPos(5, 64, 5);
        assertFalse(placed.isPlaced(a, Blocks.STONE.defaultBlockState()));
        placed.record(a, STEVE);
        assertTrue(placed.isPlaced(a, Blocks.STONE.defaultBlockState()));
        assertEquals(1, placed.size());
        // 格子已是空气:视为无记号,并顺手清掉
        assertFalse(placed.isPlaced(a, Blocks.AIR.defaultBlockState()));
        assertEquals(0, placed.size());
        assertFalse(placed.isPlaced(a, Blocks.STONE.defaultBlockState()), "清掉后再放的东西不是玩家放的");
        placed.record(a, STEVE);
        placed.forget(a);
        assertEquals(0, placed.size());
    }

    @Test
    void chunksAreIndependentAndNeighbourhoodQueryReadsTheView() {
        PlacedBlocks placed = new PlacedBlocks();
        FakeWorld world = new FakeWorld();
        BlockPos a = new BlockPos(15, 64, 15);
        BlockPos b = new BlockPos(16, 64, 16);   // 隔壁区块
        world.set(a, Blocks.STONE.defaultBlockState());
        world.set(b, Blocks.STONE.defaultBlockState());
        placed.record(a, STEVE);
        placed.record(b, STEVE);
        assertTrue(placed.isPlaced(a, world.getBlockState(a)));
        assertTrue(placed.isPlaced(b, world.getBlockState(b)));
        assertTrue(placed.anyPlacedWithin(new BlockPos(13, 64, 13), 3, world, null));
        assertFalse(placed.anyPlacedWithin(new BlockPos(30, 64, 30), 3, world, null));
        assertFalse(placed.anyPlacedWithin(new BlockPos(13, 64, 13), 3, world, STEVE.id()),
                "放的人自己不算:邻域里只有他放的");
        // 邻域查询也按视图清空气
        world.set(a, Blocks.AIR.defaultBlockState());
        world.set(b, Blocks.AIR.defaultBlockState());
        assertFalse(placed.anyPlacedWithin(new BlockPos(15, 64, 15), 1, world, null));
        assertEquals(0, placed.size());
    }

    @Test
    void survivesASaveLoadCycle() {
        PlacedBlocks placed = new PlacedBlocks();
        BlockPos a = new BlockPos(-7, 12, 99);
        placed.record(a, STEVE);
        PlacedBlocks back = PlacedBlocks.load(placed.save(new CompoundTag(), null), null);
        assertTrue(back.isPlaced(a, Blocks.STONE.defaultBlockState()));
        assertEquals(STEVE, back.placerAt(a, Blocks.STONE.defaultBlockState()), "是谁放的跟着存档来回");
        assertEquals(1, back.size());
        assertEquals(0, PlacedBlocks.load(new CompoundTag(), null).size(), "坏档退回空记录,不炸");
    }

    @Test
    void cellsFromBeforePlacersWereKeptAreStillPlacedBySomeoneUnknown() {
        BlockPos a = new BlockPos(1, 70, 1);
        CompoundTag old = new CompoundTag();
        net.minecraft.nbt.ListTag cells = new net.minecraft.nbt.ListTag();
        cells.add(net.minecraft.nbt.LongTag.valueOf(a.asLong()));
        old.put("cells", cells);

        PlacedBlocks back = PlacedBlocks.load(old, null);
        assertTrue(back.isPlaced(a, Blocks.STONE.defaultBlockState()), "旧存档记下的格子照样算玩家放的");
        assertFalse(back.placerAt(a, Blocks.STONE.defaultBlockState()).known(), "只是不知道是谁放的");
    }

    @Test
    void modesSurviveASaveLoadCycle() {
        PermissionStore store = new PermissionStore();
        UUID companion = UUID.fromString("11111111-1111-1111-1111-111111111111");
        assertEquals(Mode.ASK, store.modeOf(companion), "没设过就是 ask");
        store.setMode(companion, Mode.OBSERVE);
        PermissionStore back = PermissionStore.load(store.save(new CompoundTag(), null), null);
        assertEquals(Mode.OBSERVE, back.modeOf(companion));
        assertEquals(Mode.ASK, back.modeOf(UUID.randomUUID()));
    }
}
