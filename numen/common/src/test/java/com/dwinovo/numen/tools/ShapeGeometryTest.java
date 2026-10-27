package com.dwinovo.numen.tools;

import com.dwinovo.numen.api.sdk.ApiTester;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 形状函数交回的格集合,和 {@link ShapeReference} 按 WorldEdit 判据另写的参考算式逐格对照:圆柱、球、圆锥、金字塔、线、曲线,实心与空心,
 * 半径取整数、小数、两个或三个,高度取正负零。判据只看格集合,不看交回的先后。
 */
class ShapeGeometryTest {

    private static String at(int x, int y, int z) {
        return ShapeLua.pos(x, y, z);
    }

    @Test
    void cylindersCoverTheCellsWorldEditsCircleCovers() {
        double[][] radii = {{1, 1}, {2, 2}, {3, 3}, {4.5, 4.5}, {2.5, 2.5}, {3, 6}, {5.5, 2}, {0.2, 0.2}};
        int[] heights = {1, 4, -3};
        for (double[] r : radii) {
            for (int h : heights) {
                String call = "numen.shape.cyl(" + at(10, 5, -7) + ", \"stone\", {" + r[0] + ", " + r[1] + "}, " + h + ")";
                assertEquals(ShapeReference.cylinder(10, 5, -7, r[0], r[1], h, true), ShapeLua.positions(call), call);
                call = "numen.shape.hcyl(" + at(10, 5, -7) + ", \"stone\", {" + r[0] + ", " + r[1] + "}, " + h + ")";
                assertEquals(ShapeReference.cylinder(10, 5, -7, r[0], r[1], h, false), ShapeLua.positions(call), call);
            }
        }
    }

    @Test
    void aCylinderOfNoHeightIsEmptyAndTheDefaultIsOneLayer() {
        assertEquals(Set.of(), ShapeLua.positions("numen.shape.cyl(" + at(0, 0, 0) + ", \"stone\", 3, 0)"));
        assertEquals(ShapeReference.cylinder(0, 0, 0, 3, 3, 1, true),
                ShapeLua.positions("numen.shape.cyl(" + at(0, 0, 0) + ", \"stone\", 3)"));
    }

    @Test
    void spheresCoverTheCellsWorldEditsSphereCovers() {
        double[][] radii = {{0, 0, 0}, {1, 1, 1}, {2, 2, 2}, {3.5, 3.5, 3.5}, {4, 4, 4}, {6, 6, 6}, {3, 5, 2}, {2.5, 1, 4}};
        for (double[] r : radii) {
            for (boolean raised : new boolean[]{false, true}) {
                String args = at(-3, 20, 8) + ", \"stone\", {" + r[0] + ", " + r[1] + ", " + r[2] + "}, " + raised + ")";
                assertEquals(ShapeReference.sphere(-3, 20, 8, r[0], r[1], r[2], raised, true),
                        ShapeLua.positions("numen.shape.sphere(" + args), args);
                assertEquals(ShapeReference.sphere(-3, 20, 8, r[0], r[1], r[2], raised, false),
                        ShapeLua.positions("numen.shape.hsphere(" + args), args);
            }
        }
    }

    /** 空心球是最薄的连续壳:半径 4 的空心球,每一格向外一步至少有一格在球外。 */
    @Test
    void aHollowSphereIsTheThinnestClosedShell() {
        Set<String> filled = ShapeLua.positions("numen.shape.sphere(" + at(0, 0, 0) + ", \"stone\", 4)");
        Set<String> hollow = ShapeLua.positions("numen.shape.hsphere(" + at(0, 0, 0) + ", \"stone\", 4)");
        assertEquals(ShapeReference.sphere(0, 0, 0, 4, 4, 4, false, false), hollow);
        assertTrue(filled.containsAll(hollow));
        for (String cell : hollow) {
            String[] p = cell.split(",");
            int x = Integer.parseInt(p[0]);
            int y = Integer.parseInt(p[1]);
            int z = Integer.parseInt(p[2]);
            int sx = Integer.signum(x);
            int sy = Integer.signum(y);
            int sz = Integer.signum(z);
            assertTrue(!filled.contains(ShapeLua.at(x + (sx == 0 ? 1 : sx), y, z))
                            || !filled.contains(ShapeLua.at(x, y + (sy == 0 ? 1 : sy), z))
                            || !filled.contains(ShapeLua.at(x, y, z + (sz == 0 ? 1 : sz))),
                    cell + " has the sphere outside it in no direction");
        }
    }

    @Test
    void conesCoverTheCellsWorldEditsConeCovers() {
        double[][] radii = {{1, 1}, {3, 3}, {4, 2}, {5.5, 5.5}};
        int[] heights = {1, 4, 6, -5};
        for (double[] r : radii) {
            for (int h : heights) {
                String args = at(2, 3, 4) + ", \"stone\", {" + r[0] + ", " + r[1] + "}, " + h;
                assertEquals(ShapeReference.cone(2, 3, 4, r[0], r[1], h, true, 1), ShapeLua.positions("numen.shape.cone(" + args + ")"),
                        args);
                for (double thickness : new double[]{1, 2, 1.5}) {
                    assertEquals(ShapeReference.cone(2, 3, 4, r[0], r[1], h, false, thickness),
                            ShapeLua.positions("numen.shape.hcone(" + args + ", " + thickness + ")"), args + " thickness " + thickness);
                }
            }
        }
    }

    @Test
    void pyramidsAreStepsOfShrinkingSquares() {
        for (int size : new int[]{1, 2, 3, 5}) {
            assertEquals(ShapeReference.pyramid(1, 2, 3, size, true),
                    ShapeLua.positions("numen.shape.pyramid(" + at(1, 2, 3) + ", \"stone\", " + size + ")"), "size " + size);
            assertEquals(ShapeReference.pyramid(1, 2, 3, size, false),
                    ShapeLua.positions("numen.shape.hpyramid(" + at(1, 2, 3) + ", \"stone\", " + size + ")"), "size " + size);
        }
        // size layers, the base 2 * size - 1 across, a single cell on top
        Set<String> four = ShapeLua.positions("numen.shape.pyramid(" + at(0, 0, 0) + ", \"stone\", 4)");
        assertTrue(four.contains("3,0,3") && four.contains("-3,0,-3") && !four.contains("4,0,0"));
        assertTrue(four.contains("0,3,0") && !four.contains("1,3,0") && !four.contains("0,4,0"));
    }

    @Test
    void linesAndPolylinesAreTheCellsWorldEditDraws() {
        int[][][] paths = {
                {{0, 0, 0}, {10, 3, 5}},
                {{0, 0, 0}, {0, 7, 2}},
                {{4, 4, 4}, {4, 4, 4}},
                {{0, 0, 0}, {-6, 2, -9}},
                {{0, 0, 0}, {5, 0, 0}, {5, 0, 5}, {0, 4, 5}},
        };
        for (int[][] path : paths) {
            StringBuilder lua = new StringBuilder("{");
            for (int[] p : path) {
                lua.append(at(p[0], p[1], p[2])).append(", ");
            }
            lua.append("}");
            for (double thickness : new double[]{0, 1, 2.5}) {
                assertEquals(ShapeReference.line(path, thickness, true),
                        ShapeLua.positions("numen.shape.line(" + lua + ", \"stone\", " + thickness + ")"), lua + " " + thickness);
                assertEquals(ShapeReference.line(path, thickness, false),
                        ShapeLua.positions("numen.shape.hline(" + lua + ", \"stone\", " + thickness + ")"), lua + " " + thickness);
            }
        }
    }

    @Test
    void curvesPassThroughTheirPointsAsWorldEditDrawsThem() {
        int[][][] paths = {
                {{0, 0, 0}, {10, 0, 0}},
                {{0, 0, 0}, {6, 4, 0}, {12, 0, 6}, {20, 2, 6}},
                {{0, 0, 0}, {8, 8, 8}, {0, 16, 0}},
        };
        for (int[][] path : paths) {
            StringBuilder lua = new StringBuilder("{");
            for (int[] p : path) {
                lua.append(at(p[0], p[1], p[2])).append(", ");
            }
            lua.append("}");
            for (double thickness : new double[]{0, 1}) {
                Set<String> got = ShapeLua.positions("numen.shape.curve(" + lua + ", \"stone\", " + thickness + ")");
                assertEquals(ShapeReference.curve(path, thickness, true), got, lua + " " + thickness);
                assertEquals(ShapeReference.curve(path, thickness, false),
                        ShapeLua.positions("numen.shape.hcurve(" + lua + ", \"stone\", " + thickness + ")"));
            }
            Set<String> thin = ShapeLua.positions("numen.shape.curve(" + lua + ", \"stone\")");
            for (int[] p : path) {
                assertTrue(thin.contains(ShapeLua.at(p[0], p[1], p[2])), "the curve passes through " + p[0] + "," + p[1] + "," + p[2]);
            }
        }
    }

    private static Set<String> ball(int cx, int cy, int cz, int radiusSquared, int reach) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (int x = -reach; x <= reach; x++) {
            for (int y = -reach; y <= reach; y++) {
                for (int z = -reach; z <= reach; z++) {
                    if (x * x + y * y + z * z <= radiusSquared) {
                        out.add(ShapeLua.at(cx + x, cy + y, cz + z));
                    }
                }
            }
        }
        return out;
    }

    @Test
    void generateKeepsTheCellsWhereTheFunctionSaysTrueInTheSpaceTheModeNames() {
        String sphere = "function(x, y, z) return x * x + y * y + z * z <= 4 end";
        // "center": blocks from the middle of the selection
        assertEquals(ball(0, 0, 0, 4, 3), ShapeLua.positions("numen.shape.generate(numen.shape.cuboid(" + at(-3, -3, -3) + ", " + at(3, 3, 3)
                + "), \"stone\", " + sphere + ", \"center\")"));
        // a Pos: blocks from that cell
        assertEquals(ball(10, 0, 0, 4, 3), ShapeLua.positions("numen.shape.generate(numen.shape.cuboid(" + at(7, -3, -3) + ", " + at(13, 3, 3)
                + "), \"stone\", " + sphere + ", " + at(10, 0, 0) + ")"));
        // "world": the cells' own coordinates
        Set<String> checker = new java.util.LinkedHashSet<>();
        for (int x = 0; x <= 5; x++) {
            for (int z = 0; z <= 5; z++) {
                if ((x + z) % 2 == 0) {
                    checker.add(ShapeLua.at(x, 64, z));
                }
            }
        }
        assertEquals(checker, ShapeLua.positions("numen.shape.generate(numen.shape.cuboid(" + at(0, 60, 0) + ", " + at(5, 66, 5)
                + "), \"stone\", function(x, y, z) return y == 64 and (x + z) % 2 == 0 end, \"world\")"));
        // "unit" (the default): -1 to 1 across the selection, the function sees (x - middle) / half the size
        Set<String> unit = new java.util.LinkedHashSet<>();
        for (int x = -6; x <= 6; x++) {
            for (int y = -6; y <= 6; y++) {
                for (int z = -6; z <= 6; z++) {
                    double ux = x / 6.0;
                    double uy = y / 6.0;
                    double uz = z / 6.0;
                    if (ux * ux + uy * uy + uz * uz <= 1) {
                        unit.add(ShapeLua.at(x, y, z));
                    }
                }
            }
        }
        assertEquals(unit, ShapeLua.positions("numen.shape.generate(numen.shape.cuboid(" + at(-6, -6, -6) + ", " + at(6, 6, 6)
                + "), \"stone\", function(x, y, z) return x * x + y * y + z * z <= 1 end)"));
    }

    @Test
    void aHollowGenerateKeepsTheCellsNextToOnesTheFunctionRefuses() {
        String region = "numen.shape.cuboid(" + at(-3, -3, -3) + ", " + at(3, 3, 3) + ")";
        assertEquals(ShapeReference.surface(ball(0, 0, 0, 4, 3)), ShapeLua.positions("numen.shape.hgenerate(" + region
                + ", \"stone\", function(x, y, z) return x * x + y * y + z * z <= 4 end, \"center\")"));
        // the function decides what is outside, the region only limits which cells are asked: where it says true all
        // round, even past the edge of the region, no cell has an outside next to it
        assertEquals(Set.of(), ShapeLua.positions("numen.shape.hgenerate(" + region + ", \"stone\", function() return true end)"));
    }

    @Test
    void aGenerateFunctionAnswersTrueOrFalse() {
        ApiTester.Run run = ShapeLua.run("numen.shape.generate(numen.shape.cuboid(" + at(0, 0, 0) + ", " + at(1, 1, 1)
                + "), \"stone\", function() return 1 end)");
        assertFalse(run.ok());
        assertTrue(run.message().contains("returns true for a cell that is part of the shape"), run.message());
        ApiTester.Run mode = ShapeLua.run("numen.shape.generate(numen.shape.cuboid(" + at(0, 0, 0) + ", " + at(1, 1, 1)
                + "), \"stone\", function() return true end, \"nearby\")");
        assertFalse(mode.ok());
        assertTrue(mode.message().contains("mode is \"unit\""), mode.message());
    }

    @Test
    void aShapeWithoutAPatternIsTheSelectionAndWithOneTheCells() {
        String cyl = "numen.shape.cyl(" + at(0, 0, 0) + ", %s, 3, 4)";
        assertEquals(ShapeLua.positions(String.format(cyl, "\"stone\"")), ShapeLua.positions(String.format(cyl, "nil") + ":cells()"));
        assertEquals("cyl", ShapeLua.value(String.format(cyl, "nil") + ".kind").getAsString());
        assertEquals("ellipsoid", ShapeLua.value("numen.shape.sphere(" + at(0, 0, 0) + ", nil, 3).kind").getAsString());
        assertEquals("cells", ShapeLua.value("numen.shape.cone(" + at(0, 0, 0) + ", nil, 3, 4).kind").getAsString());
    }

    @Test
    void bigShapesAreRefusedWithTheWayToSplitThem() {
        ApiTester.Run run = ShapeLua.run("numen.shape.sphere(" + at(0, 0, 0) + ", \"stone\", 40)");
        assertFalse(run.ok());
        assertTrue(run.message().contains("bad_argument") && run.message().contains("more than 16384 cells")
                && run.message().contains("in pieces"), run.message());
        ApiTester.Run huge = ShapeLua.run("numen.shape.cuboid(" + at(0, 0, 0) + ", " + at(999, 999, 999) + "):set(\"stone\")");
        assertFalse(huge.ok());
        assertTrue(huge.message().contains("more than 16384 cells"), huge.message());
    }

    @Test
    void shapesNeedSensibleArguments() {
        ApiTester.Run thin = ShapeLua.run("numen.shape.line({" + at(0, 0, 0) + ", " + at(3, 0, 0) + "}, \"stone\", -1)");
        assertFalse(thin.ok());
        assertTrue(thin.message().contains("thickness is at least 0"), thin.message());
        ApiTester.Run one = ShapeLua.run("numen.shape.line({" + at(0, 0, 0) + "}, \"stone\")");
        assertFalse(one.ok());
        assertTrue(one.message().contains("at least two points"), one.message());
        ApiTester.Run radii = ShapeLua.run("numen.shape.cyl(" + at(0, 0, 0) + ", \"stone\", {1, 2, 3}, 2)");
        assertFalse(radii.ok());
        assertTrue(radii.message().contains("radii is one number"), radii.message());
    }
}
