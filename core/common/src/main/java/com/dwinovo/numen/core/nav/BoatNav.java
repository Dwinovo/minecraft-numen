package com.dwinovo.numen.core.nav;

import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.world.Semantics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 水面导航:她驾着船,把船开到离目标最近的可达水格。规划({@link #chart})与驾驶(实例)分开:路线描述写了 {@code mode = "boat"}
 * 时,{@code numen.route.plan} 照当时的水面画出航线,{@code numen.move.go} 照航线驾船;与 {@link Trip} 同一份契约(tick → RUNNING/ARRIVED/FAILED)。
 *
 * <h2>为什么不并进步行 A*</h2>
 * 步行图的动作集(跳、垫、挖、贴边)对船一条都不成立;船的图是<b>同一水面高度的二维平面 + 岸线障碍</b>。硬塞进主引擎要给每个
 * 动作加"在船上吗"的分支,那是把两种物理搅成一锅。这里独立做一次有预算的平面 A*(8 邻接,对角要求两正交都可行,防贴角),
 * 驾驶按"当前能直线水路看到的最远航点"贪心推进——网格锯齿交给船的动量抹平,不需要平滑器。
 *
 * <h2>目标在岸上是常态</h2>
 * 启发朝目标的水平投影;预算内到不了就取<b>离目标最近的已访问水格</b>当靠岸点({@link Chart#reached} 为假),开到那儿就到了——
 * 下船走路是她的下一步({@code numen.move.dismount}、再规划步行),这里不越界。
 *
 * <p>驾驶输入走 {@link InputDriver#steerVehicle}(原版桨物理);服务端能动船的前提是载具权威开关(numen-api 的
 * MixinEntityVehicleControl)。
 *
 * <p>哪一格是水、船过不过得去,问她身边的地形({@link Terrain},寻路模块的第 0 层):水是语义种类里的静水与流水,过得去
 * 是水面上方放得下她的身体,与步行寻路是同一份几何。
 */
public final class BoatNav {

    public enum Status { RUNNING, ARRIVED, FAILED }

    /** 平面 A* 的节点预算:半径 ~60 格的湖面全覆盖。 */
    private static final int NODE_BUDGET = 4096;
    /** 对当前航点无进展多少刻判搁浅。 */
    private static final int STUCK_TICKS = 60;
    /** 航点算到达的水平距离平方。 */
    private static final double WAYPOINT_REACHED_SQ = 1.2 * 1.2;
    /** 离目标水平这么近的水格就算终点(船身宽,贴不到格心)。 */
    public static final int GOAL_NEAR = 2;
    /** 直线水路检查的采样步长(格)。 */
    private static final double LINE_STEP = 0.5;

    /** 水:静水与流水。 */
    private static final Set<Semantics.Kind> WATER = EnumSet.of(Semantics.Kind.WATER, Semantics.Kind.FLOWING_WATER);

    /**
     * 一条航线。
     *
     * @param path    航点,水面上的格,按先后;起点就在终点水域里时为空
     * @param reached 终点在目标水域里({@link #GOAL_NEAR} 格内);否则是离目标最近的靠岸点
     * @param why     画不出航线时为什么;画出来了为 null
     */
    public record Chart(List<BlockPos> path, boolean reached, String why) {

        public Chart {
            path = List.copyOf(path);
        }

        /** 航线的终点;画不出来、或起点就在终点水域里为 null。 */
        public BlockPos end() {
            return path.isEmpty() ? null : path.get(path.size() - 1);
        }
    }

    private final NumenPlayer player;
    private final List<BlockPos> path;
    private int wpIndex;
    private double bestWpDistSq = Double.MAX_VALUE;
    private int noProgressTicks;
    private String failReason = "boat navigation failed";

    /** 照 {@code chart} 驾船。 */
    public BoatNav(NumenPlayer player, Chart chart) {
        this.player = player;
        this.path = chart.path();
    }

    public String failReason() {
        return failReason;
    }

    /** 船这一刻在消耗它的航线吗——任务层的续约(progress lease)读它。 */
    public boolean progressing() {
        return noProgressTicks <= 20;
    }

    public Status tick() {
        Entity vehicle = player.getVehicle();
        if (!(vehicle instanceof Boat boat)) {
            failReason = "no longer in a boat";
            return Status.FAILED;
        }
        // 贪心推进:直线水路能看到的最远航点就是当前航点——网格锯齿在这一步消失
        Vec3 at = boat.position();
        for (int i = path.size() - 1; i > wpIndex; i--) {
            if (clearWaterLine(Terrain.of(player), at, path.get(i))) {
                wpIndex = i;
                bestWpDistSq = Double.MAX_VALUE;
                break;
            }
        }
        if (wpIndex >= path.size()) {
            InputDriver.haltVehicle(player);
            return Status.ARRIVED;
        }
        BlockPos wp = path.get(wpIndex);
        double distSq = horizontalDistSq(at, wp);
        if (distSq <= WAYPOINT_REACHED_SQ) {
            wpIndex++;
            bestWpDistSq = Double.MAX_VALUE;
            if (wpIndex >= path.size()) {
                InputDriver.haltVehicle(player);
                return Status.ARRIVED;
            }
            wp = path.get(wpIndex);
            distSq = horizontalDistSq(at, wp);
        }
        // 搁浅判定:对当前航点的最近距离长期不缩短。撞上冰面、被推上岸都落在这儿。
        if (distSq < bestWpDistSq - 0.05) {
            bestWpDistSq = distSq;
            noProgressTicks = 0;
        } else if (++noProgressTicks > STUCK_TICKS) {
            InputDriver.haltVehicle(player);
            failReason = "the boat stopped making headway (beached or blocked)";
            return Status.FAILED;
        }
        InputDriver.steerVehicle(player, Vec3.atBottomCenterOf(wp));
        return Status.RUNNING;
    }

    /** 收桨。任务层中途弃船(取消/让位)时调,别让船带着按下的前进键漂走。 */
    public void stop() {
        InputDriver.haltVehicle(player);
    }

    // ------------------------------------------------------------------
    // 规划
    // ------------------------------------------------------------------

    /**
     * 从船所在的 {@code from} 开往 {@code target} 的航线:平面 A*,key = (x<<32|z),8 邻接,对角要求两正交都可行。在世界线程上调。
     */
    public static Chart chart(NumenPlayer player, BlockPos from, BlockPos target) {
        Terrain terrain = Terrain.of(player);
        Integer surface = waterSurfaceAt(terrain, from);
        if (surface == null) {
            return new Chart(List.of(), false, "the boat is not on water");
        }
        int surfaceY = surface;
        int sx = from.getX();
        int sz = from.getZ();
        if (near(target, sx, sz)) {
            return new Chart(List.of(), true, null);
        }
        Map<Long, long[]> nodes = new HashMap<>();   // key -> {parentKey, gCost(双精度位)}
        PriorityQueue<long[]> open = new PriorityQueue<>(
                (a, b) -> Double.compare(Double.longBitsToDouble(a[1]), Double.longBitsToDouble(b[1])));
        long startKey = key(sx, sz);
        nodes.put(startKey, new long[]{startKey, Double.doubleToLongBits(0)});
        open.add(new long[]{startKey, Double.doubleToLongBits(heuristic(target, sx, sz))});
        long bestKey = startKey;
        double bestH = heuristic(target, sx, sz);
        int expanded = 0;
        Long goalKey = null;

        while (!open.isEmpty() && expanded < NODE_BUDGET) {
            long cur = open.poll()[0];
            expanded++;
            int cx = unpackX(cur);
            int cz = unpackZ(cur);
            if (near(target, cx, cz)) {
                goalKey = cur;
                break;
            }
            double h = heuristic(target, cx, cz);
            if (h < bestH) {
                bestH = h;
                bestKey = cur;
            }
            double g = Double.longBitsToDouble(nodes.get(cur)[1]);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = cx + dx;
                    int nz = cz + dz;
                    if (!cruisable(terrain, surfaceY, nx, nz)) continue;
                    if (dx != 0 && dz != 0
                            && (!cruisable(terrain, surfaceY, cx + dx, cz)
                                    || !cruisable(terrain, surfaceY, cx, cz + dz))) continue;
                    double ng = g + (dx != 0 && dz != 0 ? 1.4142 : 1.0);
                    long nk = key(nx, nz);
                    long[] seen = nodes.get(nk);
                    if (seen != null && Double.longBitsToDouble(seen[1]) <= ng) continue;
                    nodes.put(nk, new long[]{cur, Double.doubleToLongBits(ng)});
                    open.add(new long[]{nk, Double.doubleToLongBits(ng + heuristic(target, nx, nz))});
                }
            }
        }

        long endKey = goalKey != null ? goalKey : bestKey;
        if (endKey == startKey) {
            return new Chart(List.of(), false, "no open water leads anywhere from here");
        }
        List<BlockPos> cells = new ArrayList<>();
        for (long k = endKey; k != startKey; k = nodes.get(k)[0]) {
            cells.add(new BlockPos(unpackX(k), surfaceY, unpackZ(k)));
        }
        java.util.Collections.reverse(cells);
        com.dwinovo.numen.core.Constants.LOG.debug(
                "[numen-boat] 航线 {} 点,{} 展开,{}", cells.size(), expanded,
                goalKey != null ? "直达目标水域" : "至最近靠岸点");
        return new Chart(cells, goalKey != null, null);
    }

    /** 这一格水面能过船吗:这一格是水,水面上方放得下她坐着的身体(船身连同她的头)。 */
    private static boolean cruisable(Terrain terrain, int surfaceY, int x, int z) {
        return terrain.isAny(new BlockPos(x, surfaceY, z), WATER) && terrain.fits(x, surfaceY + 1, z);
    }

    /** 船脚下的水面 y。往下扫三格:被浮力弹起、载人下压的瞬间,船位会短暂离面。 */
    private static Integer waterSurfaceAt(Terrain terrain, BlockPos feet) {
        for (int dy = 0; dy <= 3; dy++) {
            BlockPos at = feet.below(dy);
            if (terrain.isAny(at, WATER)) {
                return at.getY();
            }
        }
        return null;
    }

    /** 两点之间是不是一条干净的直线水路(按步长采样格子),水面在航点那一层。 */
    private static boolean clearWaterLine(Terrain terrain, Vec3 from, BlockPos to) {
        double tx = to.getX() + 0.5;
        double tz = to.getZ() + 0.5;
        double dx = tx - from.x;
        double dz = tz - from.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) (dist / LINE_STEP));
        for (int i = 1; i <= steps; i++) {
            double fx = from.x + dx * i / steps;
            double fz = from.z + dz * i / steps;
            if (!cruisable(terrain, to.getY(), (int) Math.floor(fx), (int) Math.floor(fz))) {
                return false;
            }
        }
        return true;
    }

    private static boolean near(BlockPos target, int x, int z) {
        return Math.abs(x - target.getX()) <= GOAL_NEAR && Math.abs(z - target.getZ()) <= GOAL_NEAR;
    }

    private static double heuristic(BlockPos target, int x, int z) {
        double dx = x - target.getX();
        double dz = z - target.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double horizontalDistSq(Vec3 at, BlockPos cell) {
        double dx = cell.getX() + 0.5 - at.x;
        double dz = cell.getZ() + 0.5 - at.z;
        return dx * dx + dz * dz;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) key;
    }
}
