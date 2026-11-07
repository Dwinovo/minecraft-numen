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

    /** 圆柱:底面中心 (cx, cy, cz),height 层往上(负的往下);空心的去掉往外各走一步都还在圆里的格。 */
    static Set<String> cylinder(int cx, int cy, int cz, double rx, double rz, int height, boolean filled) {
        double ix = 1 / (Math.max(1, rx) + 0.5);
        double iz = 1 / (Math.max(1, rz) + 0.5);
        int bottom = height >= 0 ? cy : cy + height;
        int top = height >= 0 ? cy + height - 1 : cy - 1;
        int reach = (int) Math.ceil(Math.max(rx, rz)) + 2;
        Set<String> out = new LinkedHashSet<>();
        for (int y = bottom; y <= top; y++) {
            for (int x = cx - reach; x <= cx + reach; x++) {
                for (int z = cz - reach; z <= cz + reach; z++) {
                    double ax = Math.abs(x - cx);
                    double az = Math.abs(z - cz);
                    if (!inCircle(ax * ix, az * iz)) {
                        continue;
                    }
                    if (!filled && inCircle((ax + 1) * ix, az * iz) && inCircle(ax * ix, (az + 1) * iz)) {
                        continue;
                    }
                    out.add(ShapeLua.at(x, y, z));
                }
            }
        }
        return out;
    }

    private static boolean inCircle(double a, double b) {
        return a * a + b * b <= 1;
    }

    /** 椭球:中心 (cx, cy, cz),raised 时中心抬高竖直半径的整数部分;空心的去掉三个轴各往外一步都还在球里的格。 */
    static Set<String> sphere(int cx, int cy, int cz, double rx, double ry, double rz, boolean raised, boolean filled) {
        rx = Math.max(0, rx);
        ry = Math.max(0, ry);
        rz = Math.max(0, rz);
        int middleY = raised ? cy + (int) ry : cy;
        double ix = 1 / (rx + 0.5);
        double iy = 1 / (ry + 0.5);
        double iz = 1 / (rz + 0.5);
        int reach = (int) Math.ceil(Math.max(rx, Math.max(ry, rz))) + 2;
        Set<String> out = new LinkedHashSet<>();
        for (int x = cx - reach; x <= cx + reach; x++) {
            for (int y = middleY - reach; y <= middleY + reach; y++) {
                for (int z = cz - reach; z <= cz + reach; z++) {
                    double ax = Math.abs(x - cx);
                    double ay = Math.abs(y - middleY);
                    double az = Math.abs(z - cz);
                    if (!inBall(ax * ix, ay * iy, az * iz)) {
                        continue;
                    }
                    if (!filled && inBall((ax + 1) * ix, ay * iy, az * iz) && inBall(ax * ix, (ay + 1) * iy, az * iz)
                            && inBall(ax * ix, ay * iy, (az + 1) * iz)) {
                        continue;
                    }
                    out.add(ShapeLua.at(x, y, z));
                }
            }
        }
        return out;
    }

    private static boolean inBall(double a, double b, double c) {
        return a * a + b * b + c * c <= 1;
    }

    /**
     * 圆锥:底面中心 (cx, cy, cz),|height| 层到尖;第 k 层的圆按 (k - |height|)² / height² 缩小。空心的去掉"x、z 各往外 thickness 格、
     * 往上 thickness 层都还在锥里,且不是顶上那 thickness 层"的格。
     */
    static Set<String> cone(int cx, int cy, int cz, double rx, double rz, int height, boolean filled, double thickness) {
        rx = Math.max(1, rx);
        rz = Math.max(1, rz);
        int layers = Math.abs(height);
        double h2 = (double) height * height;
        int reach = (int) Math.ceil(Math.max(rx, rz)) + 2;
        Set<String> out = new LinkedHashSet<>();
        for (int y = cy - layers; y <= cy + layers; y++) {
            int k = height >= 0 ? y - cy : cy - y;
            if (k < 0 || k >= layers) {
                continue;
            }
            for (int x = cx - reach; x <= cx + reach; x++) {
                for (int z = cz - reach; z <= cz + reach; z++) {
                    double i = Math.abs(x - cx);
                    double j = Math.abs(z - cz);
                    double shrink = (k - layers) * (double) (k - layers) / h2;
                    double xs = i * i / (rx * rx);
                    double zs = j * j / (rz * rz);
                    if (xs + zs - shrink > 0) {
                        continue;
                    }
                    if (!filled) {
                        double above = (k + thickness - layers) * (k + thickness - layers) / h2;
                        boolean wider = (i + thickness) * (i + thickness) / (rx * rx) + zs - shrink <= 0;
                        boolean higher = xs + zs - above <= 0;
                        boolean deeper = xs + (j + thickness) * (j + thickness) / (rz * rz) - shrink <= 0;
                        if (wider && higher && deeper && k + thickness != layers) {
                            continue;
                        }
                    }
                    out.add(ShapeLua.at(x, y, z));
                }
            }
        }
        return out;
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
        Set<String> centre = new LinkedHashSet<>();
        for (int s = 0; s + 1 < points.length; s++) {
            int[] from = points[s];
            int[] to = points[s + 1];
            int[] length = new int[3];
            int[] dir = new int[3];
            for (int a = 0; a < 3; a++) {
                length[a] = Math.abs(to[a] - from[a]);
                dir[a] = to[a] > from[a] ? 1 : -1;
            }
            // 最长的轴;一样长时先 x 后 y 再 z
            int main = 0;
            for (int a = 1; a < 3; a++) {
                if (length[a] > length[main]) {
                    main = a;
                }
            }
            if (length[main] == 0) {
                centre.add(ShapeLua.at(from[0], from[1], from[2]));
                continue;
            }
            for (int step = 0; step <= length[main]; step++) {
                int[] cell = new int[3];
                for (int a = 0; a < 3; a++) {
                    cell[a] = a == main ? from[a] + step * dir[a]
                            : (int) Math.floor(from[a] + (double) step * length[a] / length[main] * dir[a] + 0.5);
                }
                centre.add(ShapeLua.at(cell[0], cell[1], cell[2]));
            }
        }
        return thick(centre, thickness, filled);
    }

    /**
     * 过各点的光滑曲线(Kochanek-Bartels,张力、偏置、连续性都是 0,即两端钳住的 Catmull-Rom):每个点取格心,每格长度采样十次;
     * 长度按每段八个子区间的梯形法算(//curve 就是这样,采样密度因此和它一致)。
     */
    static Set<String> curve(int[][] points, double thickness, boolean filled) {
        int n = points.length;
        // segments[i][axis] = {a, b, c, d}:第 i 段(从点 i 到点 i + 1)那一轴的三次式,下标出界的点取首尾
        double[][][] segments = new double[n][3][];
        for (int i = 0; i < n; i++) {
            int[] p0 = points[Math.max(0, i - 1)];
            int[] p1 = points[i];
            int[] p2 = points[Math.min(n - 1, i + 1)];
            int[] p3 = points[Math.min(n - 1, i + 2)];
            for (int a = 0; a < 3; a++) {
                double v0 = p0[a] + 0.5;
                double v1 = p1[a] + 0.5;
                double v2 = p2[a] + 0.5;
                double v3 = p3[a] + 0.5;
                segments[i][a] = new double[]{
                        -0.5 * v0 + 1.5 * v1 - 1.5 * v2 + 0.5 * v3,
                        v0 - 2.5 * v1 + 2 * v2 - 0.5 * v3,
                        -0.5 * v0 + 0.5 * v2,
                        v1};
            }
        }
        double length = 0;
        for (int i = 0; i < n - 1; i++) {
            double part = speed(segments[i], 0) / 2;
            for (int k = 1; k <= 6; k++) {
                part += speed(segments[i], k / 8.0);
            }
            part += speed(segments[i], 1) / 2;
            length += part / 8;
        }
        Set<String> centre = new LinkedHashSet<>();
        double step = length == 0 ? Double.POSITIVE_INFINITY : 1 / length / 10;
        for (double u = 0; u <= 1; u += step) {
            double along = u * (n - 1);
            int m = (int) Math.floor(along);
            double t = along - m;
            int[] cell = new int[3];
            for (int a = 0; a < 3; a++) {
                double[] c = segments[m][a];
                cell[a] = (int) Math.floor(((c[0] * t + c[1]) * t + c[2]) * t + c[3]);
            }
            centre.add(ShapeLua.at(cell[0], cell[1], cell[2]));
        }
        return thick(centre, thickness, filled);
    }

    /** 一段在参数 t 处的速度:三轴导数 3a t² + 2b t + c 合成的长度。 */
    private static double speed(double[][] c, double t) {
        double sum = 0;
        for (double[] axis : c) {
            double v = (3 * axis[0] * t + 2 * axis[1]) * t + axis[2];
            sum += v * v;
        }
        return Math.sqrt(sum);
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
