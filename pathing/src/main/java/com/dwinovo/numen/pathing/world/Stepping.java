package com.dwinovo.numen.pathing.world;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;

/**
 * 迈步:站着的身体从一列走进相邻的一列(四个正方向或斜向),是直接走过去(平走、走上一个不高于迈步高度的坎、走下任意高)、
 * 要起跳,还是过不去。全部从碰撞箱推导:楼梯从正面走上是两个半格的坎,从背面、侧面进是一整格要跳;下半砖是半格的坎;
 * 栅栏、墙高 1.5,比起跳能到的高度还高。
 *
 * <h2>怎么推导</h2>
 * 身体的脚底(宽 {@link BodyStats#width()} 的正方形)沿直线从起点列中心移到终点列中心。途中与脚底交叠的碰撞箱只在脚底的边
 * 越过碰撞箱的边时变化,所以只需在这些位置之间各取一点,按顺序走一遍:
 * <ul>
 *   <li>身体在当前脚高撞上碰撞箱,就抬到撞上的那些里最高的顶面,直到不再撞上——这一次抬了多少就是这里的坎;</li>
 *   <li>没撞上,就落到脚底下最高的顶面上(走下台阶、走出边沿)。</li>
 * </ul>
 * 每个坎都不高于迈步高度,就是走过去,与原版每刻碰撞时先试着抬一个迈步高度是同一回事。有坎高过迈步高度,就看起跳:
 * 一路上最高的脚高不超过起跳能到的高度({@link BodyStats#jumpHeight},按脚下方块的起跳系数),且从起点到最高处这一段,
 * 身体在最高的脚高上处处放得下(头顶不撞)。走完落到的脚高必须就是终点节点的脚高,否则这一步去的不是那个节点。
 */
public final class Stepping {

    /** 走进相邻一列的方式。 */
    public enum Step {
        /** 直接走过去:每个坎都不高于迈步高度,走下多高都算。 */
        WALK,
        /** 要起跳才上得去。 */
        JUMP,
        /** 上不去,或走完落不到终点节点。 */
        BLOCKED
    }

    private Stepping() {}

    /**
     * 站立的身体脚在 {@code (x, fromFeetY, z)},朝 {@code (dx, dz)} 走进相邻一列,落在脚高 {@code toFeetY} 的节点上。
     *
     * @param dx 与 {@code dz} 各取 -1、0、1,不同时为 0
     * @param toFeetY 终点节点的脚高,由 {@link Footing#height} 给出
     */
    public static Step between(BlockGetter level, BodyStats body, int x, double fromFeetY, int z,
                               int dx, int dz, double toFeetY) {
        if (dx < -1 || dx > 1 || dz < -1 || dz > 1 || (dx == 0 && dz == 0)) {
            throw new IllegalArgumentException("相邻一列的方向只能是正方向或斜向:" + dx + "," + dz);
        }
        double height = body.height(Pose.STANDING);
        double jump = body.jumpHeight(Semantics.jumpFactor(level, x, fromFeetY, z));
        List<AABB> boxes = boxesAround(level, x, z, dx, dz,
                Math.min(fromFeetY, toFeetY), Math.max(fromFeetY, toFeetY) + jump + height, fromFeetY);
        double half = body.width() / 2 - Clearance.DEFLATE;
        double sx = x + 0.5;
        double sz = z + 0.5;
        double[] points = samplePoints(boxes, half, sx, sz, dx, dz);

        double feet = fromFeetY;
        double biggestStep = 0;
        double peak = fromFeetY;
        int peakAt = -1;
        for (int i = 0; i < points.length; i++) {
            double cx = sx + points[i] * dx;
            double cz = sz + points[i] * dz;
            double before = feet;
            double top;
            while (!Double.isNaN(top = highestHit(boxes, cx, cz, half, feet, height))) {
                feet = top;
                if (feet - fromFeetY > jump + Footing.EPSILON) {
                    return Step.BLOCKED;
                }
            }
            if (feet > before) {
                biggestStep = Math.max(biggestStep, feet - before);
                if (feet > peak) {
                    peak = feet;
                    peakAt = i;
                }
            } else {
                feet = support(boxes, cx, cz, half, feet);
            }
        }
        if (Math.abs(feet - toFeetY) > Footing.EPSILON) {
            return Step.BLOCKED;
        }
        if (biggestStep <= body.stepHeight() + Footing.EPSILON) {
            return Step.WALK;
        }
        // 起跳:从起点竖直升到最高的脚高,再平移到最高处,这一段头顶都不能撞
        if (!Double.isNaN(highestHit(boxes, sx, sz, half, peak, height))) {
            return Step.BLOCKED;
        }
        for (int i = 0; i <= peakAt; i++) {
            if (!Double.isNaN(highestHit(boxes, sx + points[i] * dx, sz + points[i] * dz, half, peak, height))) {
                return Step.BLOCKED;
            }
        }
        return Step.JUMP;
    }

    /** 起点列与终点列(斜走时连同两侧的两列)里,脚高范围 {@code [low, high]} 附近所有碰撞箱,换成绝对坐标。 */
    private static List<AABB> boxesAround(BlockGetter level, int x, int z, int dx, int dz,
                                          double low, double high, double feetY) {
        List<AABB> out = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // 下面一格的碰撞箱可能伸上来半格(栅栏、墙),所以从脚所在格的下面一格看起
        int y0 = Footing.cellOf(low) - 1;
        int y1 = Mth.floor(high) + 1;
        for (int cx = Math.min(x, x + dx); cx <= Math.max(x, x + dx); cx++) {
            for (int cz = Math.min(z, z + dz); cz <= Math.max(z, z + dz); cz++) {
                for (int cy = y0; cy <= y1; cy++) {
                    for (AABB box : Boxes.at(level, cx, cy, cz, level.getBlockState(pos.set(cx, cy, cz)), feetY)) {
                        out.add(box.move(cx, cy, cz));
                    }
                }
            }
        }
        return out;
    }

    /**
     * 沿途取样的位置(0 是起点列中心,1 是终点列中心):脚底的边越过碰撞箱的边的那些位置把路分成若干段,段内与脚底交叠的
     * 碰撞箱不变,每段取中点;终点单独取。
     */
    private static double[] samplePoints(List<AABB> boxes, double half, double sx, double sz, int dx, int dz) {
        List<Double> cuts = new ArrayList<>();
        cuts.add(0.0);
        cuts.add(1.0);
        for (AABB box : boxes) {
            if (dx != 0) {
                addCut(cuts, (box.maxX + half - sx) / dx);
                addCut(cuts, (box.minX - half - sx) / dx);
            }
            if (dz != 0) {
                addCut(cuts, (box.maxZ + half - sz) / dz);
                addCut(cuts, (box.minZ - half - sz) / dz);
            }
        }
        double[] sorted = cuts.stream().mapToDouble(Double::doubleValue).distinct().sorted().toArray();
        double[] points = new double[sorted.length];
        for (int i = 0; i + 1 < sorted.length; i++) {
            points[i] = (sorted[i] + sorted[i + 1]) / 2;
        }
        points[sorted.length - 1] = 1.0;
        return points;
    }

    private static void addCut(List<Double> cuts, double t) {
        if (t > 0 && t < 1) {
            cuts.add(t);
        }
    }

    /** 脚底中心在 {@code (cx, cz)}、脚在 {@code feet} 时身体撞上的碰撞箱里最高的顶面;没撞上是 NaN。 */
    private static double highestHit(List<AABB> boxes, double cx, double cz, double half, double feet, double height) {
        double best = Double.NaN;
        for (AABB box : boxes) {
            if (overlapsFootprint(box, cx, cz, half)
                    && box.minY < feet + height - Clearance.DEFLATE && box.maxY > feet + Clearance.DEFLATE
                    && (Double.isNaN(best) || box.maxY > best)) {
                best = box.maxY;
            }
        }
        return best;
    }

    /** 脚底下最高的顶面(身体没撞上任何东西时往下落到那里);脚底下什么也没有,就落出取样的范围。 */
    private static double support(List<AABB> boxes, double cx, double cz, double half, double feet) {
        double best = Double.NEGATIVE_INFINITY;
        for (AABB box : boxes) {
            if (overlapsFootprint(box, cx, cz, half) && box.maxY <= feet + Clearance.DEFLATE && box.maxY > best) {
                best = box.maxY;
            }
        }
        return best;
    }

    private static boolean overlapsFootprint(AABB box, double cx, double cz, double half) {
        return box.minX < cx + half && box.maxX > cx - half && box.minZ < cz + half && box.maxZ > cz - half;
    }
}
