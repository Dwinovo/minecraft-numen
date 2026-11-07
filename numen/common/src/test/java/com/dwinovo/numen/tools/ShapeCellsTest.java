package com.dwinovo.numen.tools;

import com.dwinovo.numen.api.sdk.ApiTester;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cells 的操作(stack、move、paste、rotate、flip、revolve、origin)与图案(权重、随机状态、Cells 平铺)在她的入口跑出的结果:格移到哪、方块跟着变成什么、
 * 每格掷的骰子的分布。变换的算法与方块状态的转法照 WorldEdit,期望值按它的规则手算。
 */
class ShapeCellsTest {

    private static String at(int x, int y, int z) {
        return ShapeLua.pos(x, y, z);
    }

    /** 一串格的 Lua 写法,每格 {name, x, y, z}。 */
    private static String blocks(Object... cells) {
        StringBuilder out = new StringBuilder("numen.shape.cells({");
        for (int i = 0; i < cells.length; i += 4) {
            out.append("{name = \"").append(cells[i]).append("\", pos = ").append(at((int) cells[i + 1], (int) cells[i + 2],
                    (int) cells[i + 3])).append("}, ");
        }
        return out + "})";
    }

    private static Map<String, String> named(String expression) {
        Map<String, String> out = new TreeMap<>();
        ShapeLua.cells(expression).forEach(c -> out.put(c.at(), c.name()));
        return out;
    }

    private static Set<String> set(String... positions) {
        return new LinkedHashSet<>(List.of(positions));
    }

    // ---- origin ----

    @Test
    void cellsTurnAboutTheirOriginWhichIsTheSmallestCornerUnlessGiven() {
        String l = "numen.shape.cells({" + at(3, 1, 5) + ", " + at(4, 1, 5) + ", " + at(3, 2, 7) + "})";
        assertEquals("[3,1,5]", ShapeLua.value("(function() local o = " + l + ":origin(); return {o.x, o.y, o.z} end)()").toString().replace(" ", ""));
        assertEquals("[9,9,9]", ShapeLua.value("(function() local o = numen.shape.cells({" + at(3, 1, 5) + "}, " + at(9, 9, 9)
                + "):origin(); return {o.x, o.y, o.z} end)()").toString().replace(" ", ""));
        // a drawn shape remembers where it was drawn; shifting moves the origin along, and so do union and minus keep it
        assertEquals("[8,5,8]", ShapeLua.value("(function() local o = numen.shape.cyl(" + at(5, 5, 5)
                + ", \"stone\", 2, 2):shift(3, 0, 3):origin(); return {o.x, o.y, o.z} end)()").toString().replace(" ", ""));
        assertEquals("[5,5,5]", ShapeLua.value("(function() local o = numen.shape.cyl(" + at(5, 5, 5)
                + ", \"stone\", 2, 2):union({}):minus({}):origin(); return {o.x, o.y, o.z} end)()").toString().replace(" ", ""));
    }

    // ---- paste, stack, move ----

    @Test
    void pasteLandsTheOriginOnThePoint() {
        assertEquals(set("10,5,10", "11,5,10", "10,5,11", "11,5,11"),
                ShapeLua.positions("numen.shape.cuboid(" + at(0, 0, 0) + ", " + at(1, 0, 1) + "):set(\"stone\"):paste(" + at(10, 5, 10) + ")"));
        // a cylinder drawn at 3, 0, 3 pasted at 20, 0, 20 is the cylinder drawn there
        assertEquals(ShapeLua.positions("numen.shape.cyl(" + at(20, 0, 20) + ", \"stone\", 2, 3)"),
                ShapeLua.positions("numen.shape.cyl(" + at(3, 0, 3) + ", \"stone\", 2, 3):paste(" + at(20, 0, 20) + ")"));
        assertEquals("[20,0,20]", ShapeLua.value("(function() local o = numen.shape.cyl(" + at(3, 0, 3) + ", \"stone\", 2, 3):paste("
                + at(20, 0, 20) + "):origin(); return {o.x, o.y, o.z} end)()").toString().replace(" ", ""));
    }

    @Test
    void stackAddsCopiesOneBoxApart() {
        String two = "numen.shape.cuboid(" + at(0, 0, 0) + ", " + at(1, 0, 1) + "):set(\"stone\")";
        Set<String> up = ShapeLua.positions(two + ":stack(2, \"up\")");
        assertEquals(12, up.size());
        assertTrue(up.contains("0,0,0") && up.contains("1,1,1") && up.contains("0,2,1") && !up.contains("0,3,0"));
        // the box is 2 wide, so three copies east start at 2, 4 and 6
        Set<String> east = ShapeLua.positions(two + ":stack(3, \"east\")");
        assertEquals(16, east.size());
        assertTrue(east.contains("2,0,0") && east.contains("7,0,1") && !east.contains("8,0,0"));
        // in cells: a box one cell tall stacked up one cell at a time is a stack of layers
        assertEquals(12, ShapeLua.positions(two + ":stack(2, \"up\", {cells = true})").size());
        // a vector steps by its own length on each axis
        Set<String> diagonal = ShapeLua.positions(two + ":stack(1, {x = 1, y = 0, z = 1})");
        assertTrue(diagonal.contains("2,0,2") && diagonal.contains("3,0,3") && diagonal.size() == 8);
    }

    @Test
    void stackKeepsTheBlocksOfEachCell() {
        Map<String, String> got = named(blocks("stone", 0, 0, 0, "dirt", 0, 1, 0) + ":stack(1, \"up\")");
        assertEquals(Map.of("0,0,0", "stone", "0,1,0", "dirt", "0,2,0", "stone", "0,3,0", "dirt"), got);
    }

    @Test
    void stackCanLeaveTheAirOutOfTheCopies() {
        String cells = blocks("stone", 0, 0, 0, "air", 1, 0, 0, "cave_air", 2, 0, 0, "minecraft:void_air", 3, 0, 0);
        assertEquals(4 + 1, named(cells + ":stack(1, \"up\", {skip_air = true})").size());
        assertEquals(8, named(cells + ":stack(1, \"up\")").size());
    }

    @Test
    void stackedCopiesMayNotOverlapTheOriginal() {
        ApiTester.Run run = ShapeLua.run("numen.shape.cuboid(" + at(0, 0, 0) + ", " + at(3, 3, 3) + "):set(\"stone\"):stack(1, \"up\", {cells = true})");
        assertFalse(run.ok());
        assertTrue(run.message().contains("the copies would overlap the original"), run.message());
        ApiTester.Run none = ShapeLua.run("numen.shape.cuboid(" + at(0, 0, 0) + ", " + at(3, 3, 3) + "):set(\"stone\"):stack(0, \"up\")");
        assertFalse(none.ok());
        assertTrue(none.message().contains("at least 1"), none.message());
        ApiTester.Run many = ShapeLua.run("numen.shape.cuboid(" + at(0, 0, 0) + ", " + at(9, 9, 9) + "):set(\"stone\"):stack(100, \"up\")");
        assertFalse(many.ok());
        assertTrue(many.message().contains("more than 16384 cells"), many.message());
    }

    @Test
    void moveWritesTheCellsWhereTheyGoAndFillsWhatTheyLeave() {
        String row = blocks("stone", 0, 0, 0, "stone", 1, 0, 0);
        assertEquals(Map.of("0,0,0", "air", "1,0,0", "stone", "2,0,0", "stone"), named(row + ":move(1, \"east\")"));
        assertEquals(Map.of("0,0,0", "dirt", "1,0,0", "dirt", "3,0,0", "stone", "4,0,0", "stone"), named(row + ":move(3, \"east\", \"dirt\")"));
        assertEquals(Map.of("0,0,0", "air", "1,0,0", "air", "0,2,0", "stone", "1,2,0", "stone"), named(row + ":move(2, \"up\")"));
        // the origin goes with the cells
        assertEquals("[1,0,0]", ShapeLua.value("(function() local o = " + row + ":move(1, \"east\"):origin(); return {o.x, o.y, o.z} end)()")
                .toString().replace(" ", ""));
        ApiTester.Run none = ShapeLua.run(row + ":move(0, \"east\")");
        assertFalse(none.ok());
        assertTrue(none.message().contains("at least 1"), none.message());
    }

    // ---- rotate ----

    @Test
    void rotateTurnsCellsClockwiseSeenFromAboveAboutTheirOrigin() {
        String l = "numen.shape.cells({" + at(0, 0, 0) + ", " + at(1, 0, 0) + ", " + at(0, 0, 2) + "})";
        assertEquals(set("0,0,0", "0,0,1", "-2,0,0"), ShapeLua.positions(l + ":rotate(90)"));
        assertEquals(set("0,0,0", "-1,0,0", "0,0,-2"), ShapeLua.positions(l + ":rotate(180)"));
        assertEquals(set("0,0,0", "0,0,-1", "2,0,0"), ShapeLua.positions(l + ":rotate(270)"));
        assertEquals(ShapeLua.positions(l + ":rotate(270)"), ShapeLua.positions(l + ":rotate(-90)"));
        assertEquals(ShapeLua.positions(l), ShapeLua.positions(l + ":rotate(360)"));
        // about a given origin
        assertEquals(set("5,0,7"), ShapeLua.positions("numen.shape.cells({" + at(7, 0, 5) + "}, " + at(5, 0, 5) + "):rotate(90)"));
        // east turns to south: the first quarter turn clockwise
        assertEquals(set("5,0,6"), ShapeLua.positions("numen.shape.cells({" + at(6, 0, 5) + "}, " + at(5, 0, 5) + "):rotate(90)"));
    }

    @Test
    void rotateGoesAboutTheOtherAxesToo() {
        String l = "numen.shape.cells({" + at(0, 0, 0) + ", " + at(0, 1, 0) + "})";
        // about x: up turns to north; about z: up turns to east
        assertEquals(set("0,0,0", "0,0,-1"), ShapeLua.positions(l + ":rotate(0, 90)"));
        assertEquals(set("0,0,0", "1,0,0"), ShapeLua.positions(l + ":rotate(0, 0, 90)"));
        assertEquals(set("0,0,0", "0,-1,0"), ShapeLua.positions(l + ":rotate(0, 180)"));
        // the z turn is applied first: east goes up about z, then about y it stays up
        assertEquals(set("0,0,0", "0,1,0"), ShapeLua.positions("numen.shape.cells({" + at(0, 0, 0) + ", " + at(1, 0, 0) + "}):rotate(90, 0, -90)"));
    }

    @Test
    void anAngleThatIsNotAQuarterMovesEachCellToTheCellItsCentreTurnsInto() {
        // 45 degrees clockwise: 4 east becomes 2.83 east and 2.83 south, rounded down
        assertEquals(set("0,0,0", "2,0,2"), ShapeLua.positions("numen.shape.cells({" + at(0, 0, 0) + ", " + at(4, 0, 0) + "}):rotate(45)"));
    }

    @Test
    void rotateTurnsTheBlocksWithTheCells() {
        // the blocks come out as the vanilla rotate turns them: stairs face, logs lie, rails run, signs point
        Map<String, String> got = named(blocks("oak_stairs[facing=north,half=top]", 1, 0, 0, "oak_log[axis=x]", 0, 0, 2,
                "rail[shape=north_south]", 0, 0, 0, "oak_sign[rotation=0]", 2, 0, 0) + ":rotate(90)");
        assertEquals("oak_stairs[facing=east,half=top]", got.get("0,0,1"));
        assertEquals("oak_log[axis=z]", got.get("-2,0,0"));
        assertEquals("rail[shape=east_west]", got.get("0,0,0"));
        assertEquals("oak_sign[rotation=4]", got.get("0,0,2"));
        // a property the cell did not write comes along when the turn changes it
        assertEquals("oak_stairs[facing=east]", named(blocks("oak_stairs", 0, 0, 0) + ":rotate(90)").get("0,0,0"));
    }

    @Test
    void rotateRefusesNothingButItsOwnBadArguments() {
        ApiTester.Run run = ShapeLua.run("numen.shape.cells({{name = \"not_a_block\", pos = " + at(0, 0, 0) + "}}):rotate(90)");
        assertFalse(run.ok());
        assertTrue(run.message().contains("not_a_block"), run.message());
    }

    // ---- flip ----

    @Test
    void flipMirrorsAcrossTheOriginAlongADirection() {
        String l = "numen.shape.cells({" + at(0, 0, 0) + ", " + at(2, 0, 1) + ", " + at(1, 2, 3) + "})";
        assertEquals(set("0,0,0", "-2,0,1", "-1,2,3"), ShapeLua.positions(l + ":flip(\"east\")"));
        assertEquals(set("0,0,0", "-2,0,1", "-1,2,3"), ShapeLua.positions(l + ":flip(\"west\")"));
        assertEquals(set("0,0,0", "2,0,-1", "1,2,-3"), ShapeLua.positions(l + ":flip(\"north\")"));
        assertEquals(set("0,0,0", "2,0,1", "1,-2,3"), ShapeLua.positions(l + ":flip(\"up\")"));
        assertEquals(set("0,0,0", "-2,0,-1", "-1,2,-3"), ShapeLua.positions(l + ":flip({x = 1, y = 0, z = 1})"));
        ApiTester.Run bad = ShapeLua.run(l + ":flip(\"left\")");
        assertFalse(bad.ok());
        assertTrue(bad.message().contains("a direction is north, south, east, west, up or down"), bad.message());
    }

    @Test
    void flipTurnsTheBlocksWithTheCells() {
        assertEquals("oak_stairs[facing=west,half=bottom]",
                named("numen.shape.cells({{name = \"oak_stairs[facing=east,half=bottom]\", pos = " + at(1, 0, 0) + "}}, " + at(0, 0, 0)
                        + "):flip(\"east\")").get("-1,0,0"));
        Map<String, String> upsideDown = named(blocks("oak_stairs[facing=east,half=bottom]", 1, 0, 0, "oak_slab[type=bottom]", 0, 1, 0,
                "rail[shape=ascending_east]", 2, 0, 0, "piston[facing=up]", 0, 2, 0) + ":flip(\"up\")");
        assertEquals("oak_stairs[facing=east,half=top]", upsideDown.get("1,0,0"));
        assertEquals("oak_slab[type=top]", upsideDown.get("0,-1,0"));
        assertEquals("rail[shape=ascending_west]", upsideDown.get("2,0,0"));
        assertEquals("piston[facing=down]", upsideDown.get("0,-2,0"));
    }

    // ---- revolve ----

    @Test
    void revolveCopiesEvenlyRoundAFullTurnAboutTheVerticalAxis() {
        String cell = "numen.shape.cells({{name = \"stone\", pos = " + at(3, 0, 0) + "}}, " + at(0, 0, 0) + ")";
        assertEquals(set("3,0,0", "0,0,3", "-3,0,0", "0,0,-3"), ShapeLua.positions(cell + ":revolve(4)"));
        String stairs = "numen.shape.cells({{name = \"oak_stairs[facing=north]\", pos = " + at(3, 0, 0) + "}}, " + at(0, 0, 0) + ")";
        // clockwise seen from above by default, counter-clockwise when reversed: the stairs turn along
        assertEquals("oak_stairs[facing=east]", named(stairs + ":revolve(4)").get("0,0,3"));
        assertEquals("oak_stairs[facing=west]", named(stairs + ":revolve(4, {reverse = true})").get("0,0,-3"));
        assertEquals("oak_stairs[facing=east]", named(stairs + ":revolve(4, {reverse = true})").get("0,0,3"));
        assertEquals(set("3,0,0", "-3,0,0"), ShapeLua.positions(cell + ":revolve(2)"));
        ApiTester.Run run = ShapeLua.run(cell + ":revolve(1)");
        assertFalse(run.ok());
        assertTrue(run.message().contains("at least 2"), run.message());
    }

    @Test
    void revolveTurnsTheBlocksAndLeavesTheAirOutOfTheCopies() {
        Map<String, String> got = named("numen.shape.cells({{name = \"oak_stairs[facing=north]\", pos = " + at(3, 0, 0) + "}, {name = \"air\", pos = "
                + at(4, 0, 0) + "}}, " + at(0, 0, 0) + "):revolve(4)");
        assertEquals("oak_stairs[facing=north]", got.get("3,0,0"));
        assertEquals("oak_stairs[facing=east]", got.get("0,0,3"));
        assertEquals("oak_stairs[facing=south]", got.get("-3,0,0"));
        assertEquals("oak_stairs[facing=west]", got.get("0,0,-3"));
        assertEquals("air", got.get("4,0,0"));
        assertEquals(5, got.size());
    }

    // ---- cells as a selection of arbitrary shape ----

    @Test
    void cellsTakeTheSelectionOperationsAsAnArbitraryShape() {
        String box = "numen.shape.cuboid(" + at(0, 0, 0) + ", " + at(4, 2, 3) + "):set(\"stone\")";
        assertEquals(ShapeLua.positions("numen.shape.cuboid(" + at(0, 0, 0) + ", " + at(4, 2, 3) + "):faces(\"dirt\")"),
                ShapeLua.positions(box + ":faces(\"dirt\")"));
        assertEquals(ShapeLua.positions("numen.shape.cuboid(" + at(0, 0, 0) + ", " + at(4, 2, 3) + "):walls(\"dirt\")"),
                ShapeLua.positions(box + ":walls(\"dirt\")"));
        assertEquals(Set.of("2,1,1", "2,1,2"), ShapeLua.positions(box + ":center(\"dirt\")"));
        assertEquals(Set.of("dirt"), new LinkedHashSet<>(named(box + ":set(\"dirt\")").values()));
        assertEquals(ShapeLua.positions(box + ":faces(\"dirt\")"), ShapeLua.positions(box + ":outline(\"dirt\")"));
        // what that shape cannot do, it says
        ApiTester.Run grow = ShapeLua.run(box + ":expand(1, \"up\")");
        assertFalse(grow.ok());
        assertTrue(grow.message().contains("selection of arbitrary cells cannot be expanded"), grow.message());
    }

    // ---- patterns ----

    private static Map<String, Integer> tally(String expression) {
        Map<String, Integer> out = new TreeMap<>();
        ShapeLua.cells(expression).forEach(c -> out.merge(c.name(), 1, Integer::sum));
        return out;
    }

    private static String thousand(String pattern) {
        return "numen.shape.cuboid(" + at(0, 0, 0) + ", " + at(9, 9, 9) + "):set(\"" + pattern + "\")";
    }

    @Test
    void weightsAreRelativeAndEachCellRollsAlone() {
        Map<String, Integer> weighted = tally(thousand("30%stone,70%dirt"));
        // 1000 cells, 300 stone expected, standard deviation about 14.5: five of them either side
        assertTrue(Math.abs(weighted.get("stone") - 300) < 73, weighted.toString());
        assertEquals(1000, weighted.get("stone") + weighted.get("dirt"));
        Map<String, Integer> even = tally(thousand("stone,dirt"));
        assertTrue(Math.abs(even.get("stone") - 500) < 80, even.toString());
        Map<String, Integer> relative = tally(thousand("1%stone,3%dirt,stone"));
        assertTrue(Math.abs(relative.get("dirt") - 3000.0 / 5) < 5 * 15, relative.toString());
        assertEquals(1000, tally(thousand("stone")).get("stone"));
    }

    @Test
    void aPatternPartMayBeAnyStateOfABlock() {
        Map<String, Integer> states = tally(thousand("*oak_stairs[facing=north]"));
        assertEquals(20, states.size(), states.toString());
        assertTrue(states.keySet().stream().allMatch(s -> s.startsWith("minecraft:oak_stairs[facing=north,")), states.toString());
        // written properties stay, the rest take every value
        assertEquals(2, tally(thousand("*lever[face=wall,facing=east]")).size());
        assertEquals(1, tally(thousand("*lever[face=wall,facing=east,powered=true]")).size());
        assertEquals(1, tally(thousand("*stone")).size());
    }

    @Test
    void aPatternReadsToItsWeightedParts() {
        assertEquals("[{\"blocks\":[\"stone\"],\"weight\":1},{\"blocks\":[\"dirt\"],\"weight\":1}]",
                ShapeLua.value("numen.shape.pattern(\"stone,dirt\")").toString().replace(" ", ""));
        assertEquals("[{\"blocks\":[\"stone\"],\"weight\":30},{\"blocks\":[\"oak_stairs[facing=north,half=top]\"],\"weight\":1}]",
                ShapeLua.value("numen.shape.pattern(\"30%stone,oak_stairs[facing=north,half=top]\")").toString().replace(" ", ""));
        // the pattern can be read once and used by several
        assertEquals(2, ShapeLua.cells("numen.shape.cuboid(" + at(0, 0, 0) + ", " + at(1, 0, 0)
                + "):set(numen.shape.pattern(\"stone\"))").size());
    }

    @Test
    void aPatternThatDoesNotReadSaysWhichPart() {
        ApiTester.Run unknown = ShapeLua.run("numen.shape.cuboid(" + at(0, 0, 0) + ", " + at(1, 0, 0) + "):set(\"50%stone,50%not_a_block\")");
        assertFalse(unknown.ok());
        assertTrue(unknown.message().contains("not_a_block") && unknown.message().contains("does not read"), unknown.message());
        ApiTester.Run weight = ShapeLua.run("numen.shape.pattern(\"50%,stone\")");
        assertFalse(weight.ok());
        assertTrue(weight.message().contains("a weight but no block"), weight.message());
        ApiTester.Run tag = ShapeLua.run("numen.shape.pattern(\"##no_such_tag\")");
        assertFalse(tag.ok());
        assertTrue(tag.message().contains("no_such_tag") && tag.message().contains("no block tag"), tag.message());
        ApiTester.Run number = ShapeLua.run("numen.shape.cuboid(" + at(0, 0, 0) + ", " + at(1, 0, 0) + "):set(5)");
        assertFalse(number.ok());
        assertTrue(number.message().contains("a pattern is text"), number.message());
    }

    @Test
    void cellsAsAPatternRepeatThroughTheWorldFromTheirBox() {
        String tile = blocks("stone", 100, 0, 0, "dirt", 101, 0, 0);
        Map<String, String> got = named("numen.shape.cuboid(" + at(3, 0, 0) + ", " + at(8, 0, 0) + "):set(" + tile + ")");
        // a cell at x takes the tile's cell x mod 2 from the tile's smallest corner: even x is stone
        assertEquals(Map.of("3,0,0", "dirt", "4,0,0", "stone", "5,0,0", "dirt", "6,0,0", "stone", "7,0,0", "dirt", "8,0,0", "stone"), got);
        // where the tile has no cell, the cell gets none
        Map<String, String> sparse = named("numen.shape.cuboid(" + at(0, 0, 0) + ", " + at(2, 0, 0) + "):set(" + blocks("stone", 0, 0, 0, "dirt", 2, 0, 0) + ")");
        assertEquals(Map.of("0,0,0", "stone", "2,0,0", "dirt"), sparse);
        ApiTester.Run flat = ShapeLua.run("numen.shape.cuboid(" + at(0, 0, 0) + ", " + at(1, 0, 0) + "):set(numen.shape.cells({" + at(0, 0, 0) + "}))");
        assertFalse(flat.ok());
        assertTrue(flat.message().contains("need a block in each cell"), flat.message());
    }
}
