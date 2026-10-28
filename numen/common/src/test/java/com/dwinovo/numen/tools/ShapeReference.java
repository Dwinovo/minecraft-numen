package com.dwinovo.numen.tools;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 形状函数的参考算式:按 WorldEdit 的判据重写一遍(整个包围盒逐格判,不用对称与提前跳出),和 Lua 模块的实现各写各的,格集合必须一样。
 * 判据:圆与球的半径先加 0.5,按"距离平方不超过 1"判,距离是整数乘半径的倒数得来的(同 //cyl 与 //sphere 的写法,边界上的格才不会因舍入分歧);
 * 空心的判据是向外一步的格都还在里面就去掉。
 */
final class ShapeReference {

    private ShapeReference() {}

    static Set<String> cylinder(int cx, int cy, int cz, double rx, double rz, int height, boolean filled) {
        throw new UnsupportedOperationException("净室重写中");
    }

    static Set<String> sphere(int cx, int cy, int cz, double rx, double ry, double rz, boolean raised, boolean filled) {
        throw new UnsupportedOperationException("净室重写中");
    }

    static Set<String> cone(int cx, int cy, int cz, double rx, double rz, int height, boolean filled, double thickness) {
        throw new UnsupportedOperationException("净室重写中");
    }

    /** 金字塔:第 y 层(从 0 起)是以中心为心、边长 2 * (size - 1 - y) + 1 的方块;空心的只留这一层的外圈。 */
    static Set<String> pyramid(int cx, int cy, int cz, int size, boolean filled) {
        Set<String> out = new LinkedHashSet<>();
        for (int y = 0; y < size; y++) {
            int s = size - 1 - y;
            for (int dx = -s; dx <= s; dx++) {
                for (int dz = -s; dz <= s; dz++) {
                    if (filled || Math.max(Math.abs(dx), Math.abs(dz)) == s) {
                        out.add(ShapeLua.at(cx + dx, cy + y, cz + dz));
                    }
                }
            }
        }
        return out;
    }

    /** 折线:每段按最长的轴一格一格走、别的轴四舍五入跟上,再给每一格套一个半径为 thickness 的球。 */
    static Set<String> line(int[][] points, double thickness, boolean filled) {
        throw new UnsupportedOperationException("净室重写中");
    }

    /**
     * 过各点的光滑曲线(Kochanek-Bartels,张力、偏置、连续性都是 0,即两端钳住的 Catmull-Rom):每个点取格心,每格长度采样十次;
     * 长度按每段八个子区间的梯形法算(//curve 就是这样,采样密度因此和它一致)。
     */
    static Set<String> curve(int[][] points, double thickness, boolean filled) {
        throw new UnsupportedOperationException("净室重写中");
    }

    private static double speed(double[][] c, double t) {
        throw new UnsupportedOperationException("净室重写中");
    }

    /** 一串中心线的格,每个套一个半径为 thickness 的球;不填满的只留有一面朝外的格。 */
    static Set<String> thick(Set<String> centre, double thickness, boolean filled) {
        Set<String> balloon = new HashSet<>();
        int reach = (int) Math.ceil(thickness);
        for (String c : centre) {
            String[] p = c.split(",");
            int x = Integer.parseInt(p[0]);
            int y = Integer.parseInt(p[1]);
            int z = Integer.parseInt(p[2]);
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dy = -reach; dy <= reach; dy++) {
                    for (int dz = -reach; dz <= reach; dz++) {
                        if (dx * dx + dy * dy + dz * dz <= thickness * thickness) {
                            balloon.add(ShapeLua.at(x + dx, y + dy, z + dz));
                        }
                    }
                }
            }
        }
        return filled ? balloon : surface(balloon);
    }

    /** 六个邻居不全在里面的格。 */
    static Set<String> surface(Set<String> cells) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : cells) {
            String[] p = c.split(",");
            int x = Integer.parseInt(p[0]);
            int y = Integer.parseInt(p[1]);
            int z = Integer.parseInt(p[2]);
            if (!(cells.contains(ShapeLua.at(x + 1, y, z)) && cells.contains(ShapeLua.at(x - 1, y, z))
                    && cells.contains(ShapeLua.at(x, y + 1, z)) && cells.contains(ShapeLua.at(x, y - 1, z))
                    && cells.contains(ShapeLua.at(x, y, z + 1)) && cells.contains(ShapeLua.at(x, y, z - 1)))) {
                out.add(c);
            }
        }
        return out;
    }

    /** 选区的四周(walls)或整个表面(faces):有邻居在选区外的格;walls 把高度范围之外当作"在里面",顶和底就留空。 */
    static Set<String> shell(Set<String> region, boolean walls) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (String c : region) {
            int y = Integer.parseInt(c.split(",")[1]);
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        Set<String> out = new LinkedHashSet<>();
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (String c : region) {
            String[] p = c.split(",");
            int x = Integer.parseInt(p[0]);
            int y = Integer.parseInt(p[1]);
            int z = Integer.parseInt(p[2]);
            for (int[] d : around) {
                int ny = y + d[1];
                boolean out_of_range = ny < min || ny > max;
                boolean inside = region.contains(ShapeLua.at(x + d[0], ny, z + d[2])) || (walls && out_of_range);
                if (!inside) {
                    out.add(c);
                    break;
                }
            }
        }
        return out;
    }

    /** 多边形(俯视的 x、z 角点)在 minY 到 maxY 之间:含边与角点;判据是整数运算的"在边上,或向 +x 的射线穿过奇数条边"。 */
    static Set<String> poly(int[][] corners, int minY, int maxY) {
        Set<String> out = new LinkedHashSet<>();
        int lox = Integer.MAX_VALUE;
        int hix = Integer.MIN_VALUE;
        int loz = Integer.MAX_VALUE;
        int hiz = Integer.MIN_VALUE;
        for (int[] c : corners) {
            lox = Math.min(lox, c[0]);
            hix = Math.max(hix, c[0]);
            loz = Math.min(loz, c[1]);
            hiz = Math.max(hiz, c[1]);
        }
        for (int z = loz; z <= hiz; z++) {
            for (int x = lox; x <= hix; x++) {
                if (insidePolygon(corners, x, z)) {
                    for (int y = minY; y <= maxY; y++) {
                        out.add(ShapeLua.at(x, y, z));
                    }
                }
            }
        }
        return out;
    }

    private static boolean insidePolygon(int[][] corners, int x, int z) {
        boolean inside = false;
        for (int i = 0; i < corners.length; i++) {
            int[] a = corners[i];
            int[] b = corners[(i + 1) % corners.length];
            long cross = (long) (b[0] - a[0]) * (z - a[1]) - (long) (x - a[0]) * (b[1] - a[1]);
            if (cross == 0 && x >= Math.min(a[0], b[0]) && x <= Math.max(a[0], b[0]) && z >= Math.min(a[1], b[1])
                    && z <= Math.max(a[1], b[1])) {
                return true;
            }
            if ((a[1] > z) != (b[1] > z)) {
                boolean left = b[1] > a[1] ? cross > 0 : cross < 0;
                if (left) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }
}
