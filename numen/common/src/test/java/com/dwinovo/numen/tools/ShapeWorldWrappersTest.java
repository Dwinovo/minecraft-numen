package com.dwinovo.numen.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dwinovo.numen.tools.ShapeLua.at;
import static com.dwinovo.numen.tools.ShapeLua.pos;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 读世界的那些 Lua 入口里不读世界的部分:几何、参数的检查、选区交给它们的方法。读世界本身在 GameTest 里从她的入口测。 */
class ShapeWorldWrappersTest {

    @Test
    void removeAboveAndBelowAreAColumnBoxOfAir() {
        // size 2 is one cell either side of the column: 3 x 3 columns, 3 layers up from the cell
        List<ShapeLua.Cell> above = ShapeLua.cells("numen.shape.removeabove(" + pos(5, 10, 5) + ", 2, 3)");
        assertEquals(3 * 3 * 3, above.size());
        assertTrue(above.stream().allMatch(c -> c.name().endsWith("air") && c.y() >= 10 && c.y() <= 12));
        assertTrue(ShapeLua.positions("numen.shape.removeabove(" + pos(5, 10, 5) + ", 2, 3)").contains(at(4, 12, 4)));

        // below goes down from the cell: height 3 is layers 10, 9, 8
        assertEquals(java.util.Set.of(at(5, 10, 5), at(5, 9, 5), at(5, 8, 5)),
                ShapeLua.positions("numen.shape.removebelow(" + pos(5, 10, 5) + ", 1, 3)"));
    }

    @Test
    void splatterWithNoDecayIsEveryCellOfTheBallAndANegativeDecayIsRefused() {
        assertEquals(ShapeLua.positions("numen.shape.sphere(" + pos(0, 64, 0) + ", nil, 3):cells()"),
                ShapeLua.positions("numen.shape.splatter(" + pos(0, 64, 0) + ", \"gravel\", 3, 0)"));
        var run = ShapeLua.run("return numen.shape.splatter(" + pos(0, 64, 0) + ", \"gravel\", 3, 11)");
        assertFalse(run.ok());
        assertTrue(run.message().contains("decay is from 0 to 10"), run.message());
    }

    @Test
    void fillAndReplaceNeedAPatternToWrite() {
        var fill = ShapeLua.run("return numen.shape.fill(" + pos(0, 64, 0) + ", nil, 3)");
        assertFalse(fill.ok());
        assertTrue(fill.message().contains("needs the pattern"), fill.message());
    }
}
