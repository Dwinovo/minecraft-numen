package com.dwinovo.numen.tools;

import com.dwinovo.numen.api.sdk.ApiTester;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 选区(Region):各形状展开成哪些格、{@code expand}/{@code contract}/{@code shift}/{@code outset}/{@code inset} 照 WorldEdit 按形状怎么改、
 * {@code walls}/{@code faces}/{@code center} 画哪些格。判据是 WorldEdit 的算法(圆柱按半径与中心改、多边形只上下改、凸体不能改……)和
 * {@link ShapeReference} 另写的参考集合。
 */
class ShapeRegionTest {

    private static String at(int x, int y, int z) {
        return ShapeLua.pos(x, y, z);
    }

    private static String cuboid(int x1, int y1, int z1, int x2, int y2, int z2) {
        return "numen.shape.cuboid(" + at(x1, y1, z1) + ", " + at(x2, y2, z2) + ")";
    }

    /** 一个选区(或表达式)的格集合的包围盒:{minX, minY, minZ, maxX, maxY, maxZ}。 */
    private static int[] box(Set<String> cells) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (String c : cells) {
            String[] p = c.split(",");
            for (int k = 0; k < 3; k++) {
                int v = Integer.parseInt(p[k]);
                b[k] = Math.min(b[k], v);
                b[3 + k] = Math.max(b[3 + k], v);
            }
        }
        return b;
    }

    private static Set<String> cells(String region) {
        return ShapeLua.positions(region + ":cells()");
    }

    private static void assertBox(int[] expected, Set<String> cells, String what) {
        assertArrayEquals(expected, box(cells), what);
    }

    private static void assertArrayEquals(int[] expected, int[] got, String what) {
        assertEquals(java.util.Arrays.toString(expected), java.util.Arrays.toString(got), what);
    }

    @Test
    void aCuboidIsTheBoxBetweenItsCornersInAnyOrder() {
        Set<String> cells = cells(cuboid(4, 2, 3, 0, 0, 0));
        assertEquals(5 * 3 * 4, cells.size());
        assertBox(new int[]{0, 0, 0, 4, 2, 3}, cells, "box");
        assertEquals(cells, cells(cuboid(0, 0, 0, 4, 2, 3)));
    }

    @Test
    void aCuboidGrowsAndShrinksByItsSidesWhicheverCornerIsWhich() {
        for (boolean swapped : new boolean[]{false, true}) {
            String box = swapped ? cuboid(4, 2, 3, 0, 0, 0) : cuboid(0, 0, 0, 4, 2, 3);
            assertBox(new int[]{0, 0, 0, 6, 2, 3}, cells(box + ":expand(2, \"east\")"), "east 2");
            assertBox(new int[]{-3, 0, 0, 4, 2, 3}, cells(box + ":expand(3, \"west\")"), "west 3");
            assertBox(new int[]{0, 0, 0, 4, 3, 3}, cells(box + ":expand(1, \"up\")"), "up 1");
            assertBox(new int[]{0, 0, -2, 4, 2, 3}, cells(box + ":expand(2, \"north\")"), "north 2");
            assertBox(new int[]{0, 0, 0, 6, 2, 3}, cells(box + ":expand(2, \"east\", 0)"), "reverse 0");
            assertBox(new int[]{-1, 0, 0, 6, 2, 3}, cells(box + ":expand(2, \"east\", 1)"), "east 2 and west 1");
            assertBox(new int[]{-1, 0, -2, 4, 2, 4}, cells(box + ":expand(1, {\"north\", \"south\"}):expand(1, \"west\")"
                    + ":expand(1, \"north\")"), "a list of directions, one after another");
            assertBox(new int[]{2, 0, 0, 4, 2, 3}, cells(box + ":contract(2, \"east\")"), "contract east 2: the west side moves east");
            assertBox(new int[]{0, 1, 0, 4, 2, 3}, cells(box + ":contract(1, \"up\")"), "contract up moves the bottom up");
            assertBox(new int[]{0, 0, 0, 4, 1, 3}, cells(box + ":contract(1, \"down\")"), "contract down moves the top down");
            assertBox(new int[]{0, 5, 0, 4, 7, 3}, cells(box + ":shift(5, \"up\")"), "shift up 5");
            assertBox(new int[]{1, 2, 3, 5, 4, 6}, cells(box + ":shift(1, 2, 3)"), "shift by offsets");
            assertBox(new int[]{1, 2, 3, 5, 4, 6}, cells(box + ":shift(" + at(1, 2, 3) + ")"), "shift by a Pos");
        }
    }

    @Test
    void outsetAndInsetMoveEverySideOnlyHorizontallyOrVerticallyIfAsked() {
        String box = cuboid(0, 0, 0, 4, 2, 3);
        assertBox(new int[]{-1, -1, -1, 5, 3, 4}, cells(box + ":outset(1)"), "all round");
        assertBox(new int[]{-2, 0, -2, 6, 2, 5}, cells(box + ":outset(2, {horizontal = true})"), "only sideways");
        assertBox(new int[]{0, -1, 0, 4, 3, 3}, cells(box + ":outset(1, {vertical = true})"), "only up and down");
        assertBox(new int[]{1, 0, 1, 3, 2, 2}, cells(cuboid(0, 0, 0, 4, 2, 3) + ":inset(1, {horizontal = true})"),
                "inset sideways");
        assertBox(new int[]{0, 1, 0, 4, 1, 3}, cells(box + ":inset(1, {vertical = true})"), "inset up and down");
        assertBox(new int[]{1, 1, 1, 3, 1, 2}, cells(box + ":inset(1)"), "inset all round");
    }

    @Test
    void aSelectionStaysAsItWasWhenYouChangeIt() {
        String lua = "(function() local box = " + cuboid(0, 0, 0, 2, 2, 2) + "; local bigger = box:expand(3, \"up\"); "
                + "return {#box:cells(), #bigger:cells()} end)()";
        assertEquals("[27,54]", ShapeLua.value(lua).toString().replace(" ", ""));
    }

    @Test
    void aCylinderIsEditedByItsCentreAndRadius() {
        String cyl = "numen.shape.cyl(" + at(0, 0, 0) + ", nil, 3, 4)";
        assertEquals(ShapeReference.cylinder(0, 0, 0, 3, 3, 4, true), cells(cyl));
        // 2 north: the centre moves one north and the north-south radius grows by one, so only the north side moves
        assertEquals(ShapeReference.cylinder(0, 0, -1, 3, 4, 4, true), cells(cyl + ":expand(2, \"north\")"));
        assertEquals(ShapeReference.cylinder(1, 0, 0, 4, 3, 4, true), cells(cyl + ":expand(2, \"east\")"));
        // up and down only change the layers
        assertEquals(ShapeReference.cylinder(0, 0, 0, 3, 3, 6, true), cells(cyl + ":expand(2, \"up\")"));
        assertEquals(ShapeReference.cylinder(0, -3, 0, 3, 3, 7, true), cells(cyl + ":expand(3, \"down\")"));
        assertEquals(ShapeReference.cylinder(-1, 0, 0, 2, 3, 4, true), cells(cyl + ":contract(2, \"east\")"));
        assertEquals(ShapeReference.cylinder(0, 0, 0, 3, 3, 3, true), cells(cyl + ":contract(1, \"down\")"));
        assertEquals(ShapeReference.cylinder(0, 1, 0, 3, 3, 3, true), cells(cyl + ":contract(1, \"up\")"));
        assertEquals(ShapeReference.cylinder(2, 3, -4, 3, 3, 4, true), cells(cyl + ":shift(2, 3, -4)"));
        // every side by 2: the radius grows by 2, the layers by 2 above and below
        assertEquals(ShapeReference.cylinder(0, -2, 0, 5, 5, 8, true), cells(cyl + ":outset(2)"));
        assertEquals(ShapeReference.cylinder(0, 0, 0, 5, 5, 4, true), cells(cyl + ":outset(2, {horizontal = true})"));
        // a radius of at least 1 stays
        assertEquals(ShapeReference.cylinder(0, 0, 0, 1, 1, 4, true), cells(cyl + ":inset(9, {horizontal = true})"));
    }

    @Test
    void aCylinderChangesSidewaysByEvenAmountsOnly() {
        ApiTester.Run odd = ShapeLua.run("return numen.shape.cyl(" + at(0, 0, 0) + ", nil, 3, 4):expand(1, \"north\")");
        assertFalse(odd.ok());
        assertTrue(odd.message().contains("even number"), odd.message());
    }

    @Test
    void aSphereIsEditedByItsRadii() {
        String sphere = "numen.shape.sphere(" + at(0, 0, 0) + ", nil, 3)";
        assertEquals(ShapeReference.sphere(0, 0, 0, 3, 3, 3, false, true), cells(sphere));
        assertEquals(ShapeReference.sphere(0, 2, 0, 3, 5, 3, false, true), cells(sphere + ":expand(4, \"up\")"));
        assertEquals(ShapeReference.sphere(-1, 0, 0, 4, 3, 3, false, true), cells(sphere + ":expand(2, \"west\")"));
        assertEquals(ShapeReference.sphere(0, 0, 0, 5, 5, 5, false, true), cells(sphere + ":outset(2)"));
        assertEquals(ShapeReference.sphere(0, 0, 0, 1, 1, 1, false, true), cells(sphere + ":inset(2)"));
        assertEquals(ShapeReference.sphere(4, 0, 0, 3, 3, 3, false, true), cells(sphere + ":shift(4, \"east\")"));
        assertEquals(ShapeReference.sphere(0, 0, 0, 3, 3, 3, false, true), cells("numen.shape.ellipsoid(" + at(0, 0, 0) + ", 3)"));
        assertEquals(ShapeReference.sphere(0, 0, 0, 2, 4, 3, false, true), cells("numen.shape.ellipsoid(" + at(0, 0, 0) + ", {2, 4, 3})"));
    }

    @Test
    void aPolygonOnlyChangesUpAndDown() {
        int[][] l = {{0, 0}, {8, 0}, {8, 3}, {3, 3}, {3, 8}, {0, 8}};
        StringBuilder lua = new StringBuilder("numen.shape.poly({");
        for (int[] p : l) {
            lua.append(at(p[0], 64, p[1])).append(", ");
        }
        String poly = lua + "}, 70, 72)";
        assertEquals(ShapeReference.poly(l, 70, 72), cells(poly));
        assertEquals(ShapeReference.poly(l, 70, 74), cells(poly + ":expand(2, \"up\")"));
        assertEquals(ShapeReference.poly(l, 69, 72), cells(poly + ":expand(1, \"down\")"));
        assertEquals(ShapeReference.poly(l, 71, 72), cells(poly + ":contract(1, \"up\")"));
        assertEquals(ShapeReference.poly(l, 70, 71), cells(poly + ":contract(1, \"down\")"));
        int[][] moved = new int[l.length][];
        for (int i = 0; i < l.length; i++) {
            moved[i] = new int[]{l[i][0] + 2, l[i][1] - 1};
        }
        assertEquals(ShapeReference.poly(moved, 75, 77), cells(poly + ":shift(2, 5, -1)"));
        ApiTester.Run sideways = ShapeLua.run("return " + poly + ":expand(1, \"east\")");
        assertFalse(sideways.ok());
        assertTrue(sideways.message().contains("only changes up and down"), sideways.message());
    }

    @Test
    void aTriangleAndATiltedPolygonIncludeTheirEdges() {
        int[][] triangle = {{0, 0}, {7, 2}, {2, 6}};
        String poly = "numen.shape.poly({" + at(0, 0, 0) + ", " + at(7, 0, 2) + ", " + at(2, 0, 6) + "}, 5, 5)";
        assertEquals(ShapeReference.poly(triangle, 5, 5), cells(poly));
    }

    @Test
    void aConvexIsTheHullOfItsPoints() {
        Set<String> octahedron = new LinkedHashSet<>();
        for (int x = -3; x <= 3; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -3; z <= 3; z++) {
                    if (Math.abs(x) + Math.abs(y) + Math.abs(z) <= 3) {
                        octahedron.add(ShapeLua.at(x, y, z));
                    }
                }
            }
        }
        String points = "{" + at(3, 0, 0) + ", " + at(-3, 0, 0) + ", " + at(0, 3, 0) + ", " + at(0, -3, 0) + ", "
                + at(0, 0, 3) + ", " + at(0, 0, -3) + "}";
        assertEquals(octahedron, cells("numen.shape.convex(" + points + ")"));
        Set<String> tetrahedron = new LinkedHashSet<>();
        for (int x = 0; x <= 4; x++) {
            for (int y = 0; y <= 4; y++) {
                for (int z = 0; z <= 4; z++) {
                    if (x + y + z <= 4) {
                        tetrahedron.add(ShapeLua.at(x + 10, y, z));
                    }
                }
            }
        }
        String tetra = "numen.shape.convex({" + at(10, 0, 0) + ", " + at(14, 0, 0) + ", " + at(10, 4, 0) + ", " + at(10, 0, 4) + ", "
                + at(11, 1, 1) + "})";
        assertEquals(tetrahedron, cells(tetra));
        Set<String> moved = new LinkedHashSet<>();
        tetrahedron.forEach(c -> {
            String[] p = c.split(",");
            moved.add(ShapeLua.at(Integer.parseInt(p[0]), Integer.parseInt(p[1]) + 6, Integer.parseInt(p[2])));
        });
        assertEquals(moved, cells(tetra + ":shift(0, 6, 0)"));
    }

    @Test
    void aConvexNeedsFourPointsOutOfOnePlaneAndCannotBeResized() {
        ApiTester.Run flat = ShapeLua.run("return numen.shape.convex({" + at(0, 0, 0) + ", " + at(4, 0, 0) + ", " + at(0, 0, 4) + ", "
                + at(4, 0, 4) + "})");
        assertFalse(flat.ok());
        assertTrue(flat.message().contains("do not lie in one plane"), flat.message());
        ApiTester.Run grow = ShapeLua.run("return numen.shape.convex({" + at(0, 0, 0) + ", " + at(4, 0, 0) + ", " + at(0, 4, 0) + ", "
                + at(0, 0, 4) + "}):expand(1, \"up\")");
        assertFalse(grow.ok());
        assertTrue(grow.message().contains("cannot be expanded"), grow.message());
    }

    @Test
    void cellsOfAnyShapeCannotBeExpanded() {
        ApiTester.Run grow = ShapeLua.run("return numen.shape.cone(" + at(0, 0, 0) + ", nil, 3, 4):expand(1, \"up\")");
        assertFalse(grow.ok());
        assertTrue(grow.message().contains("selection of arbitrary cells cannot be expanded"), grow.message());
    }

    @Test
    void wallsAndFacesAreTheShellsWorldEditBuilds() {
        Set<String> sphere = ShapeReference.sphere(0, 0, 0, 3, 3, 3, false, true);
        assertEquals(ShapeReference.shell(sphere, false), ShapeLua.positions("numen.shape.sphere(" + at(0, 0, 0) + ", nil, 3):faces(\"stone\")"));
        assertEquals(ShapeReference.shell(sphere, true), ShapeLua.positions("numen.shape.sphere(" + at(0, 0, 0) + ", nil, 3):walls(\"stone\")"));
        assertEquals(ShapeReference.shell(sphere, false), ShapeLua.positions("numen.shape.sphere(" + at(0, 0, 0) + ", nil, 3):outline(\"stone\")"));
        Set<String> cyl = ShapeReference.cylinder(2, 1, 2, 3, 3, 5, true);
        assertEquals(ShapeReference.shell(cyl, false), ShapeLua.positions("numen.shape.cyl(" + at(2, 1, 2) + ", nil, 3, 5):faces(\"stone\")"));
        assertEquals(ShapeReference.shell(cyl, true), ShapeLua.positions("numen.shape.cyl(" + at(2, 1, 2) + ", nil, 3, 5):walls(\"stone\")"));
        // the walls of a cylinder are the wall the hollow cylinder has
        assertEquals(ShapeReference.cylinder(2, 1, 2, 3, 3, 5, false), ShapeLua.positions("numen.shape.cyl(" + at(2, 1, 2) + ", nil, 3, 5):walls(\"stone\")"));
        int[][] l = {{0, 0}, {8, 0}, {8, 3}, {3, 3}, {3, 8}, {0, 8}};
        Set<String> poly = ShapeReference.poly(l, 4, 6);
        String lua = "numen.shape.poly({" + at(0, 4, 0) + ", " + at(8, 4, 0) + ", " + at(8, 4, 3) + ", " + at(3, 4, 3) + ", " + at(3, 4, 8)
                + ", " + at(0, 4, 8) + "}, 4, 6)";
        assertEquals(ShapeReference.shell(poly, false), ShapeLua.positions(lua + ":faces(\"stone\")"));
        assertEquals(ShapeReference.shell(poly, true), ShapeLua.positions(lua + ":walls(\"stone\")"));
    }

    @Test
    void aCuboidsFacesAreSixSidesAndItsWallsFour() {
        Set<String> box = new LinkedHashSet<>();
        for (int x = 0; x <= 4; x++) {
            for (int y = 0; y <= 2; y++) {
                for (int z = 0; z <= 3; z++) {
                    box.add(ShapeLua.at(x, y, z));
                }
            }
        }
        assertEquals(60 - 3 * 1 * 2, ShapeLua.positions(cuboid(0, 0, 0, 4, 2, 3) + ":faces(\"stone\")").size());
        assertEquals(ShapeReference.shell(box, false), ShapeLua.positions(cuboid(0, 0, 0, 4, 2, 3) + ":faces(\"stone\")"));
        assertEquals(ShapeReference.shell(box, true), ShapeLua.positions(cuboid(0, 0, 0, 4, 2, 3) + ":walls(\"stone\")"));
        // a slab one cell thick is all face, and has walls all round
        assertEquals(5 * 4, ShapeLua.positions(cuboid(0, 0, 0, 4, 0, 3) + ":faces(\"stone\")").size());
        assertEquals(5 * 4 - 3 * 2, ShapeLua.positions(cuboid(0, 0, 0, 4, 0, 3) + ":walls(\"stone\")").size());
    }

    @Test
    void theCentreIsOneTwoFourOrEightCells() {
        assertEquals(Set.of("2,2,2"), ShapeLua.positions(cuboid(0, 0, 0, 4, 4, 4) + ":center(\"stone\")"));
        Set<String> eight = new LinkedHashSet<>();
        for (int x = 1; x <= 2; x++) {
            for (int y = 1; y <= 2; y++) {
                for (int z = 1; z <= 2; z++) {
                    eight.add(ShapeLua.at(x, y, z));
                }
            }
        }
        assertEquals(eight, ShapeLua.positions(cuboid(0, 0, 0, 3, 3, 3) + ":center(\"stone\")"));
        assertEquals(Set.of("1,2,2", "2,2,2"), ShapeLua.positions(cuboid(0, 0, 0, 3, 4, 4) + ":center(\"stone\")"));
        // between -3 and -2 on every axis: rounds away from zero on one side, towards it on the other
        Set<String> negative = new LinkedHashSet<>();
        for (int x = -3; x <= -2; x++) {
            for (int y = -3; y <= -2; y++) {
                for (int z = -3; z <= -2; z++) {
                    negative.add(ShapeLua.at(x, y, z));
                }
            }
        }
        assertEquals(negative, ShapeLua.positions(cuboid(-4, -4, -4, -1, -1, -1) + ":center(\"stone\")"));
        assertEquals(Set.of("5,1,5", "5,2,5"), ShapeLua.positions("numen.shape.cyl(" + at(5, 0, 5) + ", nil, 3, 4):center(\"stone\")"));
        assertEquals(Set.of("0,0,0"), ShapeLua.positions("numen.shape.sphere(" + at(0, 0, 0) + ", nil, 4):center(\"stone\")"));
    }

    @Test
    void selectionsRememberWhereTheyWereDrawn() {
        // a shape drawn at a point carries that point as its origin, the cuboid between two corners has none
        assertEquals("[5,0,5]", ShapeLua.value("(function() local o = numen.shape.cyl(" + at(5, 0, 5) + ", \"stone\", 2, 2):origin(); return {o.x, o.y, o.z} end)()")
                .toString().replace(" ", ""));
        assertEquals("[5,-1,5]", ShapeLua.value("(function() local o = numen.shape.cyl(" + at(5, 0, 5) + ", nil, 2, 2):shift(0, -1, 0):cells():origin(); return {o.x, o.y, o.z} end)()")
                .toString().replace(" ", ""));
    }
}
