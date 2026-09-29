package com.dwinovo.numen.area;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 区域:部分的编号(区域内递增、删了不重排)、逐部分的运算、跨维度报错、点名的写法、按主人的存档。需要 MC 引导。
 */
@Tag("mc")
class AreaTest {

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
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过区域钉桩");
    }

    private static Cells box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return Cells.box(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
    }

    /** 三部分的房子:地基、门、屋顶。 */
    private static Area house() {
        return Area.of(Level.OVERWORLD, Area.Kind.BOX, box(0, 64, 0, 9, 64, 9))
                .with(Area.Kind.BOX, box(4, 65, 0, 5, 66, 0))
                .with(Area.Kind.BOX, box(0, 70, 0, 9, 70, 9));
    }

    private static List<String> ids(Area area) {
        return area.parts().stream().map(Area.Part::id).toList();
    }

    // ==================== 部分与编号 ====================

    @Test
    void partsCountUpPerLetterAndADroppedNumberIsNeverReissued() {
        Area area = house().with(Area.Kind.POINT, Cells.point(new BlockPos(20, 64, 20)));
        assertEquals(List.of("b1", "b2", "b3", "p1"), ids(area));
        Area dropped = area.without("b2");
        assertEquals(List.of("b1", "b3", "p1"), ids(dropped), "删了不重排");
        assertFalse(dropped.contains(Level.OVERWORLD, new BlockPos(4, 65, 0)));
        assertEquals(List.of("b1", "b3", "p1", "b4"), ids(dropped.with(Area.Kind.BOX, box(30, 64, 30, 31, 64, 31))),
                "b2 的号不再发");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> dropped.without("b2"))
                .getMessage().contains("b1, b3, p1"));
        assertThrows(IllegalArgumentException.class, () -> area.with(Area.Kind.GROUP, Cells.EMPTY));
        assertEquals(100 + 4 + 100 + 1, area.cells().size(), "整块是各部分的并");
    }

    @Test
    void theWholeAreaIsInOneDimension() {
        Area area = house();
        assertTrue(area.contains(Level.OVERWORLD, new BlockPos(3, 64, 3)));
        assertFalse(area.contains(Level.NETHER, new BlockPos(3, 64, 3)), "别的维度里同一个坐标不在区域里");
        Area nether = Area.of(Level.NETHER, Area.Kind.BOX, box(0, 64, 0, 3, 64, 3));
        for (Runnable op : List.<Runnable>of(() -> area.union(nether), () -> area.minus(nether),
                () -> area.intersect(nether))) {
            assertTrue(assertThrows(IllegalArgumentException.class, op::run).getMessage()
                    .contains("different dimensions"));
        }
    }

    // ==================== 运算 ====================

    @Test
    void theLeftPartsKeepTheirNumbersThroughMinusIntersectAndGrow() {
        Area house = house();
        Area door = AreaRef.parse("house/b2").resolve(Map.of("house", house));
        Area safe = house.minus(door);
        assertEquals(List.of("b1", "b3"), ids(safe), "门那一部分减空了就去掉");
        assertEquals(200, safe.cells().size());
        assertEquals(List.of("b2"), ids(house.intersect(door)));
        assertTrue(house.minus(house).parts().isEmpty(), "自己减自己是空的区域");
        assertEquals(0, house.minus(house).cells().size());

        Area buffer = house.grow(1);
        assertEquals(List.of("b1", "b2", "b3"), ids(buffer));
        assertEquals(box(-1, 63, -1, 10, 65, 10), buffer.part("b1").cells());
        assertTrue(buffer.contains(Level.OVERWORLD, new BlockPos(-1, 71, -1)));
    }

    @Test
    void unionAppendsTheRightPartsWithTheLeftsCount() {
        Area ores = Area.of(Level.OVERWORLD, Area.Kind.GROUP,
                        Cells.seen(Map.of(new BlockPos(1, 12, 1), Blocks.IRON_ORE.defaultBlockState()), 5L))
                .with(Area.Kind.GROUP, Cells.seen(Map.of(new BlockPos(9, 12, 1), Blocks.IRON_ORE.defaultBlockState()), 5L));
        Area gold = Area.of(Level.OVERWORLD, Area.Kind.GROUP,
                Cells.seen(Map.of(new BlockPos(1, 12, 9), Blocks.GOLD_ORE.defaultBlockState()), 7L));
        Area all = ores.union(gold);
        assertEquals(List.of("g1", "g2", "g3"), ids(all));
        assertEquals(Blocks.GOLD_ORE.defaultBlockState(), all.part("g3").cells().seenAt(new BlockPos(1, 12, 9)).state());
        assertEquals(List.of("g1", "g2"), ids(all.filter(s -> s.is(Blocks.IRON_ORE))), "按方块筛,筛空的部分去掉");
    }

    @Test
    void theCenterIsASinglePointInsideTheArea() {
        Area mid = house().center();
        assertEquals(List.of("p1"), ids(mid));
        assertEquals(1, mid.cells().size());
        assertTrue(house().cells().contains(mid.cells().nearest(BlockPos.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> Area.empty(Level.OVERWORLD).center());
    }

    // ==================== 点名的写法 ====================

    @Test
    void referencesNameTheWholeOrOnePart() {
        Map<String, Area> areas = Map.of("house", house());
        assertEquals(new AreaRef("house", null), AreaRef.parse("house"));
        assertEquals("house/b3", AreaRef.parse("house/b3").toString());
        assertEquals(house(), AreaRef.parse("house").resolve(areas));
        assertEquals(List.of("b3"), ids(AreaRef.parse("house/b3").resolve(areas)));
        assertNull(AreaRef.parse("house/b9").resolve(areas), "没有这一部分");
        assertNull(AreaRef.parse("barn").resolve(areas), "没有这块区域");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AreaRef.parse("My House"))
                .getMessage().contains("area names are lowercase"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AreaRef.parse("house/door"))
                .getMessage().contains("a letter and a number"));
    }

    // ==================== 存盘 ====================

    @Test
    void anAreaReadsBackWithItsPartsAndItsCount() {
        Area area = house().with(Area.Kind.GROUP,
                        Cells.seen(Map.of(new BlockPos(-40, 5, 7), Blocks.DIAMOND_ORE.defaultBlockState()), 77L))
                .with(Area.Kind.SPHERE, Cells.sphere(new BlockPos(0, 64, 0), 3))
                .without("b3");
        Area back = Area.load(area.save(), BuiltInRegistries.BLOCK.asLookup());
        assertEquals(area, back);
        assertEquals(List.of("b1", "b2", "g1", "s1", "b4"), ids(back.with(Area.Kind.BOX, box(0, 0, 0, 0, 0, 0))),
                "读回来的计数照旧:b3 删过,下一个是 b4");
        Area nether = Area.load(Area.of(Level.NETHER, Area.Kind.POINT, Cells.point(BlockPos.ZERO)).save(),
                BuiltInRegistries.BLOCK.asLookup());
        assertEquals(Level.NETHER, nether.dimension());
    }

    @Test
    void theStoreKeepsAnOwnersAreasByName() {
        AreaStore store = new AreaStore();
        store.create("house", house());
        store.create("ores", Area.of(Level.OVERWORLD, Area.Kind.POINT, Cells.point(BlockPos.ZERO)));
        var before = store.all();
        assertTrue(assertThrows(IllegalArgumentException.class, () -> store.create("house", house()))
                .getMessage().contains("already an area named house"));
        assertThrows(IllegalArgumentException.class, () -> store.create("Big House", house()));
        assertThrows(IllegalArgumentException.class, () -> store.replace("barn", house()));

        store.replace("house", house().without("b2"));
        assertEquals(List.of("b1", "b3"), ids(store.get("house")));
        assertEquals(List.of("b1", "b2", "b3"), ids(before.get("house")), "取走的快照不跟着变");
        assertEquals(List.of("house", "ores"), List.copyOf(store.all().keySet()));

        CompoundTag saved = store.save(new CompoundTag(), null);
        AreaStore back = AreaStore.load(saved, BuiltInRegistries.BLOCK.asLookup());
        assertEquals(store.all(), back.all());

        assertEquals(Area.of(Level.OVERWORLD, Area.Kind.POINT, Cells.point(BlockPos.ZERO)), store.delete("ores"));
        assertNull(store.delete("ores"));
        assertEquals(List.of("house"), List.copyOf(store.all().keySet()));
    }

    @Test
    void anUnreadableAreaIsLeftOutAndTheRestLoad() {
        AreaStore store = new AreaStore();
        store.create("house", house());
        CompoundTag saved = store.save(new CompoundTag(), null);
        CompoundTag broken = house().save();
        broken.putString("dimension", "Not A Dimension");
        saved.getCompound("areas").put("barn", broken);
        AreaStore back = AreaStore.load(saved, BuiltInRegistries.BLOCK.asLookup());
        assertEquals(List.of("house"), List.copyOf(back.all().keySet()));
    }
}
