package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.search.Route;

import net.minecraft.core.BlockPos;

/**
 * 正在走的路线:一串步子、走到了第几步、是不是已经到目标,和每一步几何上最短要几刻(运动学算的,装进来的时候算一次)。
 * 认步({@link Tracker})、接续({@link Continuation})、段状态机({@link Driver})都读写这一份,路线上的事实只在这里。
 */
final class Course {

    private final List<Route.Leg> legs = new ArrayList<>();
    private final List<Double> minimum = new ArrayList<>();
    private int cur;
    private boolean complete;
    private BlockPos start;
    private Stance startStance;

    /** 换成一条新路线,从第一步起;{@code arrived}:这条路线的终点在目标里。{@code ticks} 给每一步的最短耗时。 */
    void install(Route route, boolean arrived, ToDoubleFunction<Maneuver> ticks) {
        legs.clear();
        minimum.clear();
        cur = 0;
        start = route.start();
        startStance = route.startStance();
        complete = arrived;
        append(route, ticks);
    }

    /** 接在末尾的一段;{@code arrived}:接上之后终点在目标里。 */
    void extend(Route next, boolean arrived, ToDoubleFunction<Maneuver> ticks) {
        append(next, ticks);
        complete = arrived;
    }

    private void append(Route route, ToDoubleFunction<Maneuver> ticks) {
        for (Route.Leg leg : route.legs()) {
            legs.add(leg);
            minimum.add(ticks.applyAsDouble(leg.maneuver()));
        }
    }

    /** 没有路了:起点、步子、进度都清掉。 */
    void clear() {
        legs.clear();
        minimum.clear();
        cur = 0;
        complete = false;
        start = null;
        startStance = null;
    }

    /** 身体落在了第 {@code to} 步之前(含)的节点上:从第 {@code to} 步起接着走。 */
    void advanceTo(int to) {
        cur = to;
    }

    int size() {
        return legs.size();
    }

    boolean isEmpty() {
        return legs.isEmpty();
    }

    /** 正在走的那一步的下标;等于 {@link #size} 是路线走完了。 */
    int cur() {
        return cur;
    }

    /** 路线走完了(不含"终点在不在目标里")。 */
    boolean finished() {
        return cur >= legs.size();
    }

    /** 路线的终点在目标里。 */
    boolean complete() {
        return complete;
    }

    Maneuver at(int index) {
        return legs.get(index).maneuver();
    }

    /** 正在走的那一步。 */
    Maneuver current() {
        return at(cur);
    }

    /** 紧接着正在走的那一步往后的 {@code count} 步(不够就是剩下的),按先后。 */
    List<Maneuver> ahead(int count) {
        List<Maneuver> out = new ArrayList<>();
        for (int i = cur + 1; i < legs.size() && out.size() < count; i++) {
            out.add(at(i));
        }
        return out;
    }

    Route.Leg lastLeg() {
        return legs.get(legs.size() - 1);
    }

    /** 路线的起点。 */
    BlockPos start() {
        return start;
    }

    Stance startStance() {
        return startStance;
    }

    /** 终点节点;空路线是起点。 */
    BlockPos end() {
        return legs.isEmpty() ? start : at(legs.size() - 1).to();
    }

    /** 身体在终点上怎么待着。 */
    Stance endStance() {
        return legs.isEmpty() ? startStance : at(legs.size() - 1).landing();
    }

    /** 从正在走的那一步起,剩下的步子几何上最短要多少刻。 */
    double remainingTicks() {
        double left = 0;
        for (int i = cur; i < legs.size(); i++) {
            left += minimum.get(i);
        }
        return left;
    }

    /** 还没走完的步子,按先后。 */
    List<Route.Leg> remaining() {
        return legs.isEmpty() ? List.of() : List.copyOf(legs.subList(Math.min(cur, legs.size()), legs.size()));
    }

    /** 整条路线(含已经走过的),重搜时拿它给新搜索当偏好。 */
    Route route() {
        return new Route(start, startStance, legs);
    }
}
