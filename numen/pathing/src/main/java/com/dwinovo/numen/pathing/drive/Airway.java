package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Clearance;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

/**
 * 一段连续的空中路线:几个点连成的折线(脚底中心),身体要沿着它飞。路线规划交出的是格子的折线,锯齿一般都是格子带来的;
 * 拉直({@link #straighten})只在扫过的路线确实通的地方把中间的点去掉,拉直之后的折线才交给控制器({@link Pilot})去跟。
 * 身体在折线上走到了哪用弧长({@link #project})量,"走到哪"与"离折线多远"都只在这里算。
 *
 * <p>折线末尾往外按末段的方向延伸:停在收处的判断要看身体"滑到收处之外多远",身体滑过了头,弧长就超过 {@link #length}。
 */
final class Airway {

    /** 一条直线能不能飞:身体沿着从 {@code from} 到 {@code to} 的路飞过去,碰不碰方块;碰了交出碰到的那一格,没碰为 null。 */
    interface Sweep {
        BlockPos blocker(Vec3 from, Vec3 to);

        default boolean clear(Vec3 from, Vec3 to) {
            return blocker(from, to) == null;
        }
    }

    /** 沿线往前扫时每一小段的长度(格):包络盒按两端取,斜着的长线要切成短段才不会把一大片都算进去。 */
    private static final double PIECE = 0.5;

    private final Vec3[] points;
    /** 第 i 个点的弧长(从起点算)。 */
    private final double[] arc;
    /** 终点是降落点:落地面的高度(脚),以及水平方向上这个降落点中心;不降落为 null。 */
    private final Vec3 touchdown;
    /** 降落点这一圈(水平半径,格):圈里把地面当成降落点那一格的高度,脚落到它就算落地。迫降时地面只知道一点,圈大一些。 */
    private final double zone;
    /**
     * 线头这一段(起飞后的爬升)的弧长:排线时局部控制器在活世界里把它飞通了(躲得开沿途的方块),飞着往前查线通不通({@code Helm#blocked})从这之后起查,
     * 理想的直线爬升线不是控制器真会飞的轨迹。没有爬升段为 0。
     */
    private final double climb;

    private Airway(List<Vec3> points, Vec3 touchdown, double zone, double climb) {
        this.points = points.toArray(Vec3[]::new);
        this.arc = new double[this.points.length];
        for (int i = 1; i < this.points.length; i++) {
            arc[i] = arc[i - 1] + this.points[i].distanceTo(this.points[i - 1]);
        }
        this.touchdown = touchdown;
        this.zone = zone;
        this.climb = climb;
    }

    /** 直接用这几个点,不拉直。{@code touchdown} 是降落点(脚落在地面上的位置),没有为 null。 */
    static Airway through(List<Vec3> points, Vec3 touchdown) {
        return new Airway(points, touchdown, SPOT, 0);
    }

    /** 同样的点,降落点那一圈换成 {@code zone} 格(迫降用)。 */
    Airway withZone(double zone) {
        return new Airway(List.of(points), touchdown, zone, climb);
    }

    /** 同样的点,线头 {@code arc} 格是模拟里飞通了的爬升段。 */
    Airway withClimb(double arc) {
        return new Airway(List.of(points), touchdown, zone, arc);
    }

    /** 线头的爬升段有多长(弧长)。 */
    double climb() {
        return climb;
    }

    /**
     * 绕 {@code center} 转圈的折线(盘旋):在高度 {@code y},半径 {@code radius},从离 {@code from} 最近的一点起转 {@code laps} 圈,先从 {@code from} 过去。
     * 没有降落点:盘旋是为了等,不是为了落。
     */
    static Airway circle(Vec3 from, Vec3 center, double radius, double y, int laps) {
        int sides = CIRCLE_SIDES;
        double start = Math.atan2(from.z - center.z, from.x - center.x);
        List<Vec3> out = new ArrayList<>();
        out.add(from);
        for (int i = 1; i <= sides * laps; i++) {
            double angle = start + 2 * Math.PI * i / sides;
            out.add(new Vec3(center.x + radius * Math.cos(angle), y, center.z + radius * Math.sin(angle)));
        }
        return new Airway(out, null, SPOT, 0);
    }

    /** 盘旋的一圈用几条边。 */
    private static final int CIRCLE_SIDES = 12;
    /** 计划好的降落点那一圈的半径:落在那一格上。 */
    static final double SPOT = 1.0;

    /** 停在一点上(悬停):只有一个点的折线。 */
    static Airway at(Vec3 point) {
        return new Airway(List.of(point), null, SPOT, 0);
    }

    /**
     * 把 {@code raw} 这串点拉直:从头起,每次取最远的、与当前点之间直线能飞({@link Sweep})的那个点,中间的点去掉。
     * 一条路线一生只拉一次,扫的次数不超过点数的平方,飞得直的路线一次就拉完。
     */
    static Airway straighten(List<Vec3> raw, Vec3 touchdown, Sweep sweep) {
        List<Vec3> kept = new ArrayList<>();
        int last = raw.size() - 1;
        int i = 0;
        kept.add(raw.get(0));
        while (i < last) {
            int j = last;
            while (j > i + 1 && !sweep.clear(raw.get(i), raw.get(j))) {
                j--;
            }
            kept.add(raw.get(j));
            i = j;
        }
        return new Airway(kept, touchdown, SPOT, 0);
    }

    /**
     * 身体沿着 {@code stats} 这样的盒在活世界 {@code level} 里飞过一条直线碰不碰方块:切成 {@link #PIECE} 长的短段逐段看。
     * {@code skin} 是往下多留的一层:飞着的身体脚一碰到地面原版就停飞,擦着地面飞的线不算通。
     */
    static Sweep sweep(BlockGetter level, BodyStats stats, double skin) {
        return (from, to) -> {
            int pieces = Math.max(1, (int) Math.ceil(from.distanceTo(to) / PIECE));
            Vec3 a = from;
            for (int k = 1; k <= pieces; k++) {
                Vec3 b = from.lerp(to, (double) k / pieces);
                List<BlockPos> hit = Clearance.blockersAlong(level, stats, a.x, a.y - skin, a.z, b.x, b.y - skin, b.z);
                if (!hit.isEmpty()) {
                    return hit.get(0);
                }
                a = b;
            }
            return null;
        };
    }

    /** 折线总长(格)。 */
    double length() {
        return arc[arc.length - 1];
    }

    Vec3 start() {
        return points[0];
    }

    Vec3 end() {
        return points[points.length - 1];
    }

    /** 降落点;不降落为 null。 */
    Vec3 touchdown() {
        return touchdown;
    }

    /** 降落点那一圈的半径。 */
    double zone() {
        return zone;
    }

    /** 弧长 {@code s} 处的点;超出两头的按末段、首段的方向延伸。 */
    Vec3 at(double s) {
        if (points.length == 1) {
            return points[0];
        }
        int i = segment(s);
        return points[i].add(direction(i).scale(s - arc[i]));
    }

    /**
     * 点 {@code p} 在折线上的位置(弧长):离它最近的那一点。{@code from} 是身体已经走到的弧长,往回退不超过 {@link #BACK} 格,免得折回来的路线
     * 把身体错认到前面已经飞过的那一截上。末尾之外按末段的方向延伸。
     */
    double project(Vec3 p, double from) {
        if (points.length == 1) {
            return 0;
        }
        double best = Double.POSITIVE_INFINITY;
        double bestS = from;
        for (int i = 0; i < points.length - 1; i++) {
            if (arc[i + 1] < from - BACK) {
                continue;
            }
            Vec3 d = direction(i);
            double lo = Math.max(0, from - BACK - arc[i]);
            double hi = i == points.length - 2 ? Double.POSITIVE_INFINITY : arc[i + 1] - arc[i];
            double t = Math.max(lo, Math.min(hi, p.subtract(points[i]).dot(d)));
            double distance = points[i].add(d.scale(t)).distanceToSqr(p);
            if (distance < best - 1.0E-9) {
                best = distance;
                bestS = arc[i] + t;
            }
        }
        return bestS;
    }

    /** 往回退这么多格还认得出身体在哪。 */
    private static final double BACK = 2.0;

    /** 点 {@code p} 离折线在 {@code s} 处那一点有多远。 */
    double offset(Vec3 p, double s) {
        return p.distanceTo(at(s));
    }

    private int segment(double s) {
        int i = 0;
        while (i < points.length - 2 && s > arc[i + 1]) {
            i++;
        }
        return i;
    }

    private Vec3 direction(int i) {
        return points[i + 1].subtract(points[i]).normalize();
    }
}
