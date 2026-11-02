package com.dwinovo.numen.pathing.drive;

import java.util.List;

import com.dwinovo.numen.pathing.body.Body;
import com.dwinovo.numen.api.entity.BodyAction;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.search.SearchClock;
import com.dwinovo.numen.pathing.spec.RouteSpec;

/**
 * 一趟行程:由几段组成({@link Segment}),每段一种移动方式,共用一本账、一份行程日志和同一套重规划原因({@link Rig})。
 * 走路的一段({@link Driver})自己搜、自己重搜;滑翔的一段({@link Sortie})飞一次。段与段只通过"这一段到了没有"相连:
 * <ul>
 *   <li>出发时问一次要不要飞({@link Glide#plan}):不飞,整趟就是一个走路的段;飞,是 走到起飞点 → 滑翔 → 走到目标;</li>
 *   <li>走到起飞点的段走不通,或滑翔中止,都不是整趟的失败:从身体此刻的位置照原目标走(走路的段),原因记进日志与账;</li>
 *   <li>整趟的结局只由最后一个走路的段定。</li>
 * </ul>
 * 寻路从不替她穿脱装备、去箱子里拿烟花:身上有什么就用什么。
 */
public final class Voyage {

    private enum Phase { TO_TAKEOFF, SORTIE, FINAL }

    private final Rig rig;
    private final RouteSpec spec;
    private final int budget;
    private final SearchClock clock;
    private Goal goal;
    private Segment current;
    private Phase phase;
    private Driver.State state = Driver.State.RUNNING;
    private Halt halt;
    private final Route seed;
    private final boolean passing;
    private Glide.Plan plan;

    /**
     * 参数同 {@link Driver}。路线是调用方交进来的({@code seed})或路过({@code passing})的,不排滑翔。
     * 要不要飞到第一刻才问:那时身体才真站在起点上(世界与身体都是第一刻的样子)。
     */
    public Voyage(Body body, TerrainPolicy terrain, Materials materials, Threats threats, Goal goal, RouteSpec spec, int budget,
                  Route seed, boolean passing, SearchClock clock) {
        this.rig = new Rig(body, terrain, materials, threats);
        this.goal = goal;
        this.spec = spec;
        this.budget = budget;
        this.clock = clock;
        this.seed = seed;
        this.passing = passing;
    }

    /** 第一刻:问要不要飞,决定这一趟从哪一段开始。 */
    private void begin() {
        plan = seed == null && !passing ? Glide.plan(rig, goal) : null;
        if (plan == null) {
            current = new Driver(rig, goal, spec, budget, seed, passing, clock);
            phase = Phase.FINAL;
        } else if (rig.entity.blockPosition().equals(plan.takeoff())) {
            startSortie(plan);
        } else {
            PathLog.info("{} 排滑翔 先走到起飞点 {},水平 {} 格,估 {} 刻(走路下界 {} 刻)", rig.who, PathLog.pos(plan.takeoff()), PathLog.num(plan.blocks()),
                    PathLog.num(plan.estimate()), PathLog.num(plan.walkBound()));
            current = new Driver(rig, Goals.at(plan.takeoff()), spec, 0, null, false, clock);
            phase = Phase.TO_TAKEOFF;
        }
    }

    // ==================== 推进 ====================

    public Driver.State tick() {
        if (state != Driver.State.RUNNING) {
            return state;
        }
        if (current == null) {
            begin();
        }
        Driver.State s = current.tick();
        if (s == Driver.State.RUNNING) {
            return state;
        }
        switch (phase) {
            case TO_TAKEOFF -> {
                if (s == Driver.State.ARRIVED) {
                    Glide.Plan fresh = Glide.plan(rig, goal);
                    if (fresh != null) {
                        startSortie(fresh);
                        return state;
                    }
                    PathLog.info("{} 到了起飞点,滑翔不排了,照原目标走", rig.who);
                } else {
                    PathLog.info("{} 走不到起飞点 {},照原目标走", rig.who, PathLog.pos(plan.takeoff()));
                }
                walkOn();
            }
            case SORTIE -> walkOn();
            case FINAL -> {
                state = s;
                halt = current.halt();
            }
        }
        return state;
    }

    private void startSortie(Glide.Plan flight) {
        current = new Sortie(rig, flight);
        phase = Phase.SORTIE;
    }

    /** 从身体此刻的位置照目标走到底:最后一个走路的段。 */
    private void walkOn() {
        current = new Driver(rig, goal, spec, budget, null, false, clock);
        phase = Phase.FINAL;
    }

    // ==================== 对外(同 Driver) ====================

    public Driver.State state() {
        return state;
    }

    /** 收场的原因;没收场为 null。 */
    public Halt halt() {
        return halt;
    }

    public EditLedger ledger() {
        return rig.ledger;
    }

    public Journal journal() {
        return rig.journal;
    }

    public List<BodyAction> actions() {
        return rig.actions();
    }

    public List<DiveLog.Dive> dives() {
        return rig.dives.dives(rig.entity);
    }

    public Blockage nearestRefusal() {
        return current == null ? null : current.nearestRefusal();
    }

    public boolean progressing() {
        return current == null || current.progressing();
    }

    public boolean waiting() {
        return state == Driver.State.RUNNING && current != null && current.waiting();
    }

    public List<Route.Leg> remaining() {
        return current == null ? List.of() : current.remaining();
    }

    public boolean plannedFall() {
        return state == Driver.State.RUNNING && current != null && current.plannedFall();
    }

    public boolean plannedDive() {
        return state == Driver.State.RUNNING && current != null && current.plannedDive();
    }

    /** 换目标:之后的走路的段按新目标走;正走着最后一段的话马上换。 */
    public void retarget(Goal next) {
        goal = next;
        if (phase == Phase.FINAL && current != null) {
            current.retarget(next);
        }
    }

    /** 叫停:松开所有键。 */
    public void stop() {
        if (current != null) {
            current.stop();
        }
        if (state == Driver.State.RUNNING) {
            state = Driver.State.HALTED;
        }
    }

    @Override
    public String toString() {
        return "Voyage[" + phase + " " + current + "]";
    }
}
