package com.dwinovo.numen.pathing.world;

import java.util.ArrayList;
import java.util.Arrays;
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
 *
 * <h2>两种记法,一套推导</h2>
 * 推导只问三件事:取样的位置、脚底在某处撞上的最高顶面、脚底下最高的顶面({@link Obstacles})。要看的格全是全空或整块
 * ({@link Boxes.Fill})时,每列记成一个整块位图,三件事按整数格子答({@link Columns});有别的形状,就把全部碰撞箱换成绝对
 * 坐标逐个比({@link Pieces})。两种记法对同一份几何答得一模一样,推导只写一遍。
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
        checkDirection(dx, dz);
        double jump = body.jumpHeight(Semantics.jumpFactor(level, x, fromFeetY, z));
        Obstacles obstacles = gather(level, body, x, z, dx, dz, Math.min(fromFeetY, toFeetY),
                Math.max(fromFeetY, toFeetY) + jump + body.height(Pose.STANDING), fromFeetY);
        return between(obstacles, body, jump, x, fromFeetY, z, dx, dz, toFeetY);
    }

    /** {@link #between} 的推导,碰撞箱已经收好。 */
    static Step between(Obstacles obstacles, BodyStats body, double jump, int x, double fromFeetY, int z,
                        int dx, int dz, double toFeetY) {
        Walk walk = walk(obstacles, body, jump, x, fromFeetY, z, dx, dz);
        if (walk == null || Math.abs(walk.feet - toFeetY) > Footing.EPSILON) {
            return Step.BLOCKED;
        }
        if (walk.biggestStep <= body.stepHeight() + Footing.EPSILON) {
            return Step.WALK;
        }
        double height = body.height(Pose.STANDING);
        double half = body.width() / 2 - Clearance.DEFLATE;
        double sx = x + 0.5;
        double sz = z + 0.5;
        // 起跳:从起点竖直升到最高的脚高,再平移到最高处,这一段头顶都不能撞
        if (!Double.isNaN(obstacles.highestHit(sx, sz, half, walk.peak, height))) {
            return Step.BLOCKED;
        }
        for (int i = 0; i <= walk.peakAt; i++) {
            double cx = sx + walk.points[i] * dx;
            double cz = sz + walk.points[i] * dz;
            if (!Double.isNaN(obstacles.highestHit(cx, cz, half, walk.peak, height))) {
                return Step.BLOCKED;
            }
        }
        return Step.JUMP;
    }

    /**
     * 身体不是站在方块上,而是被水或梯子托在脚高 {@code fromFeetY}(浮着、攀着),朝 {@code (dx, dz)} 挪进相邻一列、落在脚高
     * {@code toFeetY}:先在起步那一列里升到两个脚高中较高的那个(游上去、爬上去),再在那个高度上平着挪过去,最后落到
     * {@code toFeetY}。不起跳——托着它的是水和梯子,不是脚下的方块,{@link #between} 那套"从脚下的碰撞箱起步"的推导不适用。
     * 升到的高度上身体在起步那一列放得下、平挪途中处处放得下,就是走得过去;否则过不去。落到的那一格托不托得住由调用方看。
     */
    public static Step fromHold(BlockGetter level, BodyStats body, int x, double fromFeetY, int z,
                                int dx, int dz, double toFeetY) {
        double top = Math.max(fromFeetY, toFeetY);
        if (!Clearance.fits(level, body, Pose.STANDING, x, top, z)
                || !Clearance.blockers(level, body, Pose.STANDING, x, top, z, dx, dz).isEmpty()) {
            return Step.BLOCKED;
        }
        return Step.WALK;
    }

    /**
     * 站立的身体脚在 {@code (x, fromFeetY, z)},不起跳,朝 {@code (dx, dz)} 走进相邻一列,脚最后落在多高:与
     * {@link #between} 同一套推导,只是不指定终点。途中有坎高过迈步高度、要跳才过得去,答 {@link Double#NaN};脚下直到
     * {@code lowestFeetY} 都没有东西托住,答 {@link Double#NEGATIVE_INFINITY}(身体落出了看的范围)。落进水里、抓住梯子
     * 这类碰撞箱表达不了的接住,由调用方按语义判断。
     */
    public static double walkOff(BlockGetter level, BodyStats body, int x, double fromFeetY, int z,
                                 int dx, int dz, double lowestFeetY) {
        checkDirection(dx, dz);
        double jump = body.jumpHeight(Semantics.jumpFactor(level, x, fromFeetY, z));
        Obstacles obstacles = gather(level, body, x, z, dx, dz, Math.min(fromFeetY, lowestFeetY),
                fromFeetY + jump + body.height(Pose.STANDING), fromFeetY);
        return walkOff(obstacles, body, jump, x, fromFeetY, z, dx, dz);
    }

    /** {@link #walkOff} 的推导,碰撞箱已经收好。 */
    static double walkOff(Obstacles obstacles, BodyStats body, double jump, int x, double fromFeetY, int z,
                          int dx, int dz) {
        Walk walk = walk(obstacles, body, jump, x, fromFeetY, z, dx, dz);
        if (walk == null || walk.biggestStep > body.stepHeight() + Footing.EPSILON) {
            return Double.NaN;
        }
        return walk.feet;
    }

    private static void checkDirection(int dx, int dz) {
        if (dx < -1 || dx > 1 || dz < -1 || dz > 1 || (dx == 0 && dz == 0)) {
            throw new IllegalArgumentException("相邻一列的方向只能是正方向或斜向:" + dx + "," + dz);
        }
    }

    /**
     * 沿直线走一遍的结果:最后的脚高、途中最大的坎、最高的脚高与它出现在第几个取样点,以及取样点(起跳时复核头顶)。
     */
    record Walk(double[] points, double feet, double biggestStep, double peak, int peakAt) {}

    /** 按类注释里的推导走一遍;途中要抬到起跳也够不着的高度时返回 null。{@code jump} 是这具身体从起点能跳多高。 */
    static Walk walk(Obstacles obstacles, BodyStats body, double jump, int x, double fromFeetY, int z, int dx, int dz) {
        double height = body.height(Pose.STANDING);
        double half = body.width() / 2 - Clearance.DEFLATE;
        double sx = x + 0.5;
        double sz = z + 0.5;
        double[] points = samplePoints(obstacles, half, sx, sz, dx, dz);

        double feet = fromFeetY;
        double biggestStep = 0;
        double peak = fromFeetY;
        int peakAt = -1;
        for (int i = 0; i < points.length; i++) {
            double cx = sx + points[i] * dx;
            double cz = sz + points[i] * dz;
            double before = feet;
            double top;
            while (!Double.isNaN(top = obstacles.highestHit(cx, cz, half, feet, height))) {
                feet = top;
                if (feet - fromFeetY > jump + Footing.EPSILON) {
                    return null;
                }
            }
            if (feet > before) {
                biggestStep = Math.max(biggestStep, feet - before);
                if (feet > peak) {
                    peak = feet;
                    peakAt = i;
                }
            } else {
                feet = obstacles.support(cx, cz, half, feet);
            }
        }
        return new Walk(points, feet, biggestStep, peak, peakAt);
    }

    /**
     * 收起起点列与终点列(斜走时连同两侧的两列)里,脚高范围 {@code [low, high]} 附近的全部碰撞箱。下面一格的碰撞箱可能
     * 伸上来半格(栅栏、墙),所以从 {@code low} 所在格的下面一格看起。看到的格全是全空或整块、而且不超过 64 格高时记成
     * 整块位图;碰到别的形状就把已经看过的整块换成碰撞箱,接着逐个收。
     */
    static Obstacles gather(BlockGetter level, BodyStats body, int x, int z, int dx, int dz,
                            double low, double high, double feetY) {
        int y0 = Footing.cellOf(low) - 1;
        int y1 = Mth.floor(high) + 1;
        int x0 = Math.min(x, x + dx);
        int z0 = Math.min(z, z + dz);
        int nx = Math.abs(dx) + 1;
        int nz = Math.abs(dz) + 1;
        Columns columns = y1 - y0 < Long.SIZE ? new Columns(x0, z0, nx, nz, y0) : null;
        List<AABB> boxes = columns == null ? new ArrayList<>() : null;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cx = x0; cx < x0 + nx; cx++) {
            for (int cz = z0; cz < z0 + nz; cz++) {
                for (int cy = y0; cy <= y1; cy++) {
                    Boxes.Shape shape = Boxes.shape(level, body, cx, cy, cz, level.getBlockState(pos.set(cx, cy, cz)), feetY);
                    if (shape.fill() == Boxes.Fill.EMPTY) {
                        continue;
                    }
                    if (columns != null) {
                        if (shape.fill() == Boxes.Fill.WHOLE) {
                            columns.set(cx, cy, cz);
                            continue;
                        }
                        boxes = columns.pieces();
                        columns = null;
                    }
                    for (AABB box : shape.boxes()) {
                        boxes.add(box.move(cx, cy, cz));
                    }
                }
            }
        }
        return columns != null ? columns : new Pieces(boxes);
    }

    /**
     * 沿途取样的位置(0 是起点列中心,1 是终点列中心):脚底的边越过碰撞箱的边的那些位置把路分成若干段,段内与脚底交叠的
     * 碰撞箱不变,每段取中点;终点单独取。
     */
    private static double[] samplePoints(Obstacles obstacles, double half, double sx, double sz, int dx, int dz) {
        double[] cuts = obstacles.cuts(half, sx, sz, dx, dz);
        int n = cuts.length;
        Arrays.sort(cuts);
        int distinct = 0;
        for (int i = 0; i < n; i++) {
            if (distinct == 0 || cuts[i] != cuts[distinct - 1]) {
                cuts[distinct++] = cuts[i];
            }
        }
        double[] points = new double[distinct];
        for (int i = 0; i + 1 < distinct; i++) {
            points[i] = (cuts[i] + cuts[i + 1]) / 2;
        }
        points[distinct - 1] = 1.0;
        return points;
    }

    /** 一个碰撞箱的四条竖边在路上切出的位置,落在 {@code (0, 1)} 里的记进 {@code cuts}。 */
    private static int addCuts(double[] cuts, int n, double minX, double minZ, double maxX, double maxZ,
                               double half, double sx, double sz, int dx, int dz) {
        if (dx != 0) {
            n = addCut(cuts, n, (maxX + half - sx) / dx);
            n = addCut(cuts, n, (minX - half - sx) / dx);
        }
        if (dz != 0) {
            n = addCut(cuts, n, (maxZ + half - sz) / dz);
            n = addCut(cuts, n, (minZ - half - sz) / dz);
        }
        return n;
    }

    private static int addCut(double[] cuts, int n, double t) {
        if (t > 0 && t < 1) {
            cuts[n++] = t;
        }
        return n;
    }

    /** 迈步推导要问碰撞箱的三件事。 */
    abstract static sealed class Obstacles permits Pieces, Columns {

        /** 取样的切点:0、1,加上每个碰撞箱的竖边在路上切出的位置;可以有重复,未排序。 */
        abstract double[] cuts(double half, double sx, double sz, int dx, int dz);

        /** 脚底中心在 {@code (cx, cz)}、脚在 {@code feet} 时身体撞上的碰撞箱里最高的顶面;没撞上是 NaN。 */
        abstract double highestHit(double cx, double cz, double half, double feet, double height);

        /** 脚底下最高的顶面(身体没撞上任何东西时往下落到那里);脚底下什么也没有,就落出取样的范围。 */
        abstract double support(double cx, double cz, double half, double feet);
    }

    /** 碰撞箱逐个记,绝对坐标。 */
    static final class Pieces extends Obstacles {

        private final List<AABB> boxes;

        Pieces(List<AABB> boxes) {
            this.boxes = boxes;
        }

        @Override
        double[] cuts(double half, double sx, double sz, int dx, int dz) {
            double[] cuts = new double[2 + 4 * boxes.size()];
            int n = 0;
            cuts[n++] = 0.0;
            cuts[n++] = 1.0;
            for (AABB box : boxes) {
                n = addCuts(cuts, n, box.minX, box.minZ, box.maxX, box.maxZ, half, sx, sz, dx, dz);
            }
            return Arrays.copyOf(cuts, n);
        }

        @Override
        double highestHit(double cx, double cz, double half, double feet, double height) {
            double best = Double.NaN;
            for (AABB box : boxes) {
                if (overlapsFootprint(box.minX, box.minZ, box.maxX, box.maxZ, cx, cz, half)
                        && box.minY < feet + height - Clearance.DEFLATE && box.maxY > feet + Clearance.DEFLATE
                        && (Double.isNaN(best) || box.maxY > best)) {
                    best = box.maxY;
                }
            }
            return best;
        }

        @Override
        double support(double cx, double cz, double half, double feet) {
            double best = Double.NEGATIVE_INFINITY;
            for (AABB box : boxes) {
                if (overlapsFootprint(box.minX, box.minZ, box.maxX, box.maxZ, cx, cz, half)
                        && box.maxY <= feet + Clearance.DEFLATE && box.maxY > best) {
                    best = box.maxY;
                }
            }
            return best;
        }
    }

    /**
     * 每列一个整块位图:第 {@code k} 位是 {@code y0 + k} 那一格整块。只有全空与整块时用:整块的碰撞箱就是 {@code [cx, cx + 1]}
     * 这样的整数边,三件事按整数格子答,与把它们当碰撞箱逐个比得出的数一模一样。
     */
    static final class Columns extends Obstacles {

        private final int x0;
        private final int z0;
        private final int nx;
        private final int nz;
        private final int y0;
        private final long[] whole;

        Columns(int x0, int z0, int nx, int nz, int y0) {
            this.x0 = x0;
            this.z0 = z0;
            this.nx = nx;
            this.nz = nz;
            this.y0 = y0;
            this.whole = new long[nx * nz];
        }

        void set(int x, int y, int z) {
            whole[(x - x0) * nz + (z - z0)] |= 1L << (y - y0);
        }

        /** 换成逐个的碰撞箱,次序与逐格收的一样(按列、每列自下而上)。 */
        List<AABB> pieces() {
            List<AABB> out = new ArrayList<>();
            for (int i = 0; i < whole.length; i++) {
                int cx = x0 + i / nz;
                int cz = z0 + i % nz;
                for (long bits = whole[i]; bits != 0; bits &= bits - 1) {
                    int cy = y0 + Long.numberOfTrailingZeros(bits);
                    out.add(new AABB(cx, cy, cz, cx + 1, cy + 1, cz + 1));
                }
            }
            return out;
        }

        @Override
        double[] cuts(double half, double sx, double sz, int dx, int dz) {
            double[] cuts = new double[2 + 4 * whole.length];
            int n = 0;
            cuts[n++] = 0.0;
            cuts[n++] = 1.0;
            for (int i = 0; i < whole.length; i++) {
                if (whole[i] != 0) {
                    int cx = x0 + i / nz;
                    int cz = z0 + i % nz;
                    n = addCuts(cuts, n, cx, cz, cx + 1, cz + 1, half, sx, sz, dx, dz);
                }
            }
            return Arrays.copyOf(cuts, n);
        }

        @Override
        double highestHit(double cx, double cz, double half, double feet, double height) {
            double below = feet + height - Clearance.DEFLATE;
            double above = feet + Clearance.DEFLATE;
            double best = Double.NaN;
            for (int i = 0; i < whole.length; i++) {
                long bits = whole[i];
                if (bits == 0 || !overlapsColumn(i, cx, cz, half)) {
                    continue;
                }
                // 自上而下找第一个与身体竖向交叠的整块:它的顶就是这一列撞上的最高顶面
                for (; bits != 0; bits &= ~Long.highestOneBit(bits)) {
                    int cy = y0 + 63 - Long.numberOfLeadingZeros(bits);
                    if (cy < below) {
                        if (cy + 1.0 > above && (Double.isNaN(best) || cy + 1.0 > best)) {
                            best = cy + 1.0;
                        }
                        break;
                    }
                }
            }
            return best;
        }

        @Override
        double support(double cx, double cz, double half, double feet) {
            double limit = feet + Clearance.DEFLATE;
            double best = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < whole.length; i++) {
                long bits = whole[i];
                if (bits == 0 || !overlapsColumn(i, cx, cz, half)) {
                    continue;
                }
                for (; bits != 0; bits &= ~Long.highestOneBit(bits)) {
                    int cy = y0 + 63 - Long.numberOfLeadingZeros(bits);
                    if (cy + 1.0 <= limit) {
                        if (cy + 1.0 > best) {
                            best = cy + 1.0;
                        }
                        break;
                    }
                }
            }
            return best;
        }

        private boolean overlapsColumn(int i, double cx, double cz, double half) {
            int x = x0 + i / nz;
            int z = z0 + i % nz;
            return overlapsFootprint(x, z, x + 1, z + 1, cx, cz, half);
        }
    }

    /** 碰撞箱的水平投影与脚底真的交叠(贴边不算)。 */
    private static boolean overlapsFootprint(double minX, double minZ, double maxX, double maxZ,
                                             double cx, double cz, double half) {
        return minX < cx + half && maxX > cx - half && minZ < cz + half && maxZ > cz - half;
    }
}
