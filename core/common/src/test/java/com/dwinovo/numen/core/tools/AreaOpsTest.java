package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.area.Cells;

import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code area add --box} 的盒子:两个对角(写法由命令行的坐标读),任意顺序;不是正好两角说清该怎么写,太大的一框拒收。 */
class AreaOpsTest {

    private static Cells box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return AreaOps.boxCells(List.of(new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2)));
    }

    @Test
    void aBoxIsTwoCornersInEitherOrder() {
        Cells box = box(10, 60, 5, 12, 61, 6);
        assertEquals(3 * 2 * 2, box.size());
        assertEquals(box, box(12, 61, 6, 10, 60, 5), "两角换个顺序是同一个盒子");
        assertTrue(box.contains(new BlockPos(11, 61, 5)));
        assertEquals("10,60,5 12,61,6", AreaText.box(box.bounds()), "区域展示的包围盒能原样抄回 --box");
        assertEquals(1, box(-3, -64, -3, -3, -64, -3).size());
    }

    @Test
    void aBoxIsExactlyTwoCorners() {
        for (List<BlockPos> bad : List.of(List.of(new BlockPos(1, 2, 3)),
                List.of(new BlockPos(1, 2, 3), new BlockPos(4, 5, 6), new BlockPos(7, 8, 9)))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AreaOps.boxCells(bad));
            assertTrue(e.getMessage().startsWith("a box is two corners, x1 y1 z1 x2 y2 z2"), e.getMessage());
        }
    }

    @Test
    void aBoxTooBigToKeepIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> box(0, 0, 0, 4096, 64, 4096));
        assertTrue(e.getMessage().contains("at most " + AreaOps.MAX_BOX_CELLS), e.getMessage());
        assertEquals(AreaOps.MAX_BOX_CELLS, box(0, 0, 0, 255, 255, 255).size(), "正好上限的收");
    }
}
