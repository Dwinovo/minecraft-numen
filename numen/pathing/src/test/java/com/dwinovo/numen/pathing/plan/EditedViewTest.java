package com.dwinovo.numen.pathing.plan;

import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 叠了改动的世界。 */
class EditedViewTest {

    private static final int Y = 64;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    /** 叠在另一份改动之上的视图改自己的不动底下那份,底下那份后来再改也不动它。 */
    @Test
    void aViewOverAnEditedViewKeepsItsOwnChangesAndIsNotMovedByTheOneUnderIt() {
        TestWorld base = new TestWorld().floor(-4, -4, 4, 4, Y - 1);
        BlockPos a = new BlockPos(0, Y, 0);
        BlockPos b = new BlockPos(1, Y, 0);
        BlockPos c = new BlockPos(2, Y, 0);
        EditedView under = new EditedView(base);
        under.place(a, Blocks.STONE);

        EditedView over = new EditedView(under);
        assertEquals(Blocks.STONE, over.getBlockState(a).getBlock(), "没改之前读到底下那份的改动");
        over.place(b, Blocks.DIRT);
        assertEquals(Blocks.DIRT, over.getBlockState(b).getBlock());
        assertTrue(under.getBlockState(b).isAir(), "上面改自己的,不改底下那份");

        under.place(c, Blocks.GRAVEL);
        assertEquals(Blocks.GRAVEL, under.getBlockState(c).getBlock());
        assertTrue(over.getBlockState(c).isAir(), "底下那份后来再改,上面的看到的不变");
        assertEquals(Blocks.STONE, over.getBlockState(a).getBlock());
    }

    /** 一层一层叠:每层读到的是自己和底下各层的改动,同一格以最上面改的为准,改上面不动下面。 */
    @Test
    void stackedViewsReadThroughEveryLayerAndTheTopOneWinsOnTheSameCell() {
        TestWorld base = new TestWorld().floor(-4, -4, 4, 4, Y - 1);
        BlockPos a = new BlockPos(0, Y, 0);
        BlockPos b = new BlockPos(1, Y, 0);
        EditedView first = new EditedView(base);
        first.place(a, Blocks.STONE);
        EditedView second = new EditedView(first);
        second.place(b, Blocks.DIRT);
        EditedView third = new EditedView(second);
        third.place(a, Blocks.GRAVEL);

        assertEquals(Blocks.GRAVEL, third.getBlockState(a).getBlock());
        assertEquals(Blocks.DIRT, third.getBlockState(b).getBlock());
        assertEquals(Blocks.STONE, second.getBlockState(a).getBlock());
        assertEquals(Blocks.STONE, first.getBlockState(a).getBlock());
        assertTrue(first.getBlockState(b).isAir());
        assertTrue(third.changed(a));
        assertTrue(third.changed(b));
        assertFalse(first.changed(b));
    }

    /** 改的格多到表要长大,每一格照样读得回;被覆盖的格只算一格。 */
    @Test
    void aViewWithManyChangesReadsEveryOneBack() {
        TestWorld base = new TestWorld().floor(-40, -40, 40, 40, Y - 1);
        EditedView view = new EditedView(base);
        for (int i = 0; i < 50; i++) {
            view.place(new BlockPos(i - 25, Y + (i % 3), -i), i % 2 == 0 ? Blocks.STONE : Blocks.DIRT);
        }
        view.place(new BlockPos(-25, Y, 0), Blocks.GRAVEL);
        for (int i = 0; i < 50; i++) {
            BlockPos pos = new BlockPos(i - 25, Y + (i % 3), -i);
            Block expected = i == 0 ? Blocks.GRAVEL : i % 2 == 0 ? Blocks.STONE : Blocks.DIRT;
            assertEquals(expected, view.getBlockState(pos).getBlock(), pos.toString());
        }
        assertTrue(view.getBlockState(new BlockPos(30, Y, 30)).isAir());
    }

    /** 哪一列、哪一个区段里改过,各层合起来算:底下那层改的格,上面的视图也说改过。 */
    @Test
    void whichColumnsAndSectionsWereChangedCountsEveryLayer() {
        TestWorld base = new TestWorld().floor(-4, -4, 4, 4, Y - 1);
        EditedView under = new EditedView(base);
        under.place(new BlockPos(1, Y, 2), Blocks.STONE);
        EditedView over = new EditedView(under);
        over.place(new BlockPos(3, Y, 3), Blocks.STONE);

        assertTrue(over.changed(1, Y, Y, 2));
        assertTrue(over.changed(3, Y - 2, Y + 2, 3));
        assertFalse(over.changed(1, Y + 1, Y + 3, 2));
        assertFalse(over.changed(2, Y, Y, 2));
        assertFalse(under.changed(3, Y, Y, 3), "上面改的不算在底下那份里");
        assertFalse(over.airSection(1, Y, 2));
        assertFalse(under.airSection(1, Y, 2));
        assertTrue(over.airSection(100, Y, 100));
    }
}
