package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.area.Cells;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code area add --box} 的写法:两角任意顺序,写错了说清该怎么写,太大的一框拒收。 */
class AreaOpsTest {

    @Test
    void aBoxIsTwoCornersInEitherOrder() {
        Cells box = AreaOps.boxCells("10,60,5..12,61,6");
        assertEquals(3 * 2 * 2, box.size());
        assertEquals(box, AreaOps.boxCells(" 12,61,6..10,60,5 "), "两角换个顺序是同一个盒子");
        assertTrue(box.contains(new BlockPos(11, 61, 5)));
        assertEquals("10,60,5..12,61,6", AreaText.box(box.bounds()), "区域展示的包围盒能原样抄回 --box");
        assertEquals(1, AreaOps.boxCells("-3,-64,-3..-3,-64,-3").size());
    }

    @Test
    void aMiswrittenBoxSaysHowToWriteIt() {
        for (String bad : new String[] {"10,60,5", "10,60..12,61,6", "a,b,c..1,2,3", "1,2,3..4,5,6,7"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AreaOps.boxCells(bad));
            assertTrue(e.getMessage().contains("x1,y1,z1..x2,y2,z2"), e.getMessage());
        }
    }

    @Test
    void aBoxTooBigToKeepIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AreaOps.boxCells("0,0,0..4096,64,4096"));
        assertTrue(e.getMessage().contains("at most " + AreaOps.MAX_BOX_CELLS), e.getMessage());
        assertEquals(AreaOps.MAX_BOX_CELLS, AreaOps.boxCells("0,0,0..255,255,255").size(), "正好上限的收");
    }
}
