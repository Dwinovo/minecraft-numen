package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 工地外圈:包围盒四周各外扩 {@link #MARGIN} 格的那一圈列,俯视顺时针排好、首尾相接,相邻两列只差一步。
 * 走出工地走到的是它,演出绕着走的也是它——两处认同一圈。只有几何,不读世界:哪一段走得通由走的那一方现看。
 */
final class SiteRing {

    /** 外圈离包围盒几格:站在上面身体碰不到任何图纸格,又近得看得清正在长的那一面。 */
    static final int MARGIN = 2;

    private final int[] xs;
    private final int[] zs;

    private SiteRing(int[] xs, int[] zs) {
        this.xs = xs;
        this.zs = zs;
    }

    /** 围着 {@code min}..{@code max} 这个包围盒的一圈。 */
    static SiteRing around(BlockPos min, BlockPos max) {
        int x0 = min.getX() - MARGIN;
        int x1 = max.getX() + MARGIN;
        int z0 = min.getZ() - MARGIN;
        int z1 = max.getZ() + MARGIN;
        List<int[]> cells = new ArrayList<>();
        for (int x = x0; x < x1; x++) cells.add(new int[]{x, z0});
        for (int z = z0; z < z1; z++) cells.add(new int[]{x1, z});
        for (int x = x1; x > x0; x--) cells.add(new int[]{x, z1});
        for (int z = z1; z > z0; z--) cells.add(new int[]{x0, z});
        int[] xs = new int[cells.size()];
        int[] zs = new int[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            xs[i] = cells.get(i)[0];
            zs[i] = cells.get(i)[1];
        }
        return new SiteRing(xs, zs);
    }

    int size() {
        return xs.length;
    }

    /** 第 {@code i} 列的 x;下标绕圈取模,走过头就是回到起点。 */
    int x(int i) {
        return xs[Math.floorMod(i, xs.length)];
    }

    int z(int i) {
        return zs[Math.floorMod(i, zs.length)];
    }

    /** 离水平位置 {@code (x, z)} 最近的一列。 */
    int nearest(double x, double z) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < xs.length; i++) {
            double d = distance(i, x, z);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** 水平位置 {@code (x, z)} 离第 {@code i} 列中心多远。 */
    double distance(int i, double x, double z) {
        double dx = x(i) + 0.5 - x;
        double dz = z(i) + 0.5 - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 站到圈上任何一列:搜索自己挑最近的那一段走过去。 */
    Goal goal() {
        List<Goal> columns = new ArrayList<>(xs.length);
        for (int i = 0; i < xs.length; i++) {
            columns.add(Goals.column(xs[i], zs[i]));
        }
        return Goals.anyOf(columns);
    }
}
