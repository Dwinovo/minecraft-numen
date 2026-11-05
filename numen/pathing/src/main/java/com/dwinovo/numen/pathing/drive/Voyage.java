package com.dwinovo.numen.pathing.drive;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.dwinovo.numen.pathing.api.Snapshots;
import com.dwinovo.numen.pathing.api.Travel;
import com.dwinovo.numen.pathing.body.Body;
import com.dwinovo.numen.api.entity.BodyAction;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.Mobility;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.search.SearchClock;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;

/**
 * 一趟行程:由几段组成({@link Segment}),每段一种移动方式,共用一本账、一份行程日志和同一套重规划原因({@link Rig})。
 * 走路的一段({@link Driver})自己搜、自己重搜;滑翔的一段({@link Sortie})飞一次;身体在飞而要走路或滑翔时先落地({@link Alight})。
 * 段与段只通过"这一段到了没有"相连。用哪一类移动方式由调用方说({@link Travel}),寻路不比价钱:
 * <ul>
 *   <li><b>走</b>({@link Travel#WALK}):从不飞,身体许飞也一样(快照按没有飞行能力算)。身体在飞,先落到地上,再走;</li>
 *   <li><b>创造飞行</b>({@link Travel#FLY}):身体许飞才行(受理时就拒绝不许飞的),创造飞行进走路的搜索本身({@link Driver},走法集由身体给);</li>
 *   <li><b>滑翔</b>({@link Travel#GLIDE}):走到起飞点 → 起飞 → 巡航 → 进入目标 {@link Glide#DESCENT_RANGE} 格就降落({@link Glide}),落地就是这一趟的结束,
 *       不再走最后一截。没进目标范围就落了地(中途迫降):离目标超过 {@link Glide#REFLY_RANGE} 格、够用就从此刻的位置再起飞,这一趟里失败过的起飞点不再用;
 *       不超过或不够用就收工({@link Halt.Grounded}),不悄悄改成走路。走不到起飞点、起飞没成,也是换一个起飞点。被方块顶住({@link Sortie#trapped})收场,不再起飞。</li>
 * </ul>
 * 寻路从不替她穿脱装备、去箱子里拿烟花:身上有什么就用什么。
 */
public final class Voyage {

    private enum Phase { ALIGHT, TO_TAKEOFF, SORTIE, FINAL }

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
    /** 受理时问过的结论;没问过为 null,飞的一趟第一刻再问。 */
    private final Admission admission;
    private Glide.Plan plan;
    /** 这一趟里失败过的起飞点:走不到、起不了飞、飞到半路就落了地。不再用。 */
    private final Set<BlockPos> spent = new HashSet<>();
    /** 滑翔的一段收了场,身体还在空中(起飞没成、半空中被打断):等落到地上再决定下一步。 */
    private boolean settling;

    /**
     * 参数同 {@link Driver}。路线是调用方交进来的({@code seed})或路过({@code passing})的,走路的段照它走。
     * 飞的一趟要不要、能不能飞到第一刻才定(那时身体才真站在起点上),受理时问过的({@code admission})直接用。
     */
    public Voyage(Body body, Travel travel, Admission admission, TerrainPolicy terrain, Materials materials, Threats threats, Goal goal,
                  RouteSpec spec, int budget, Route seed, boolean passing, SearchClock clock) {
        this.rig = new Rig(body, travel, terrain, materials, threats);
        this.admission = admission;
        this.goal = goal;
        this.spec = spec;
        this.budget = budget;
        this.clock = clock;
        this.seed = seed;
        this.passing = passing;
    }

    /**
     * 受理一次飞({@link Travel#FLY} 或 {@link Travel#GLIDE}):创造飞行要身体许飞;滑翔要穿着可用的鞘翅,排得出一份滑翔计划。
     * 缺什么({@link Lack})当场交回,不占身体。只在世界所在的线程上调用。
     */
    public static Admission admit(Body body, Travel travel, TerrainPolicy terrain, Materials materials, Threats threats, Goal goal) {
        Rig rig = new Rig(body, travel, terrain, materials, threats);
        return switch (travel) {
            case FLY -> flightAdmission(rig);
            case GLIDE -> Glide.admit(rig, goal, Set.of());
            case WALK -> throw new IllegalArgumentException("走不需要受理");
        };
    }

    /** 创造飞行的受理:许飞就行;不许飞,{@code have} 带上鞘翅还剩的耐久,宿主据此在提示里加上滑翔。 */
    private static Admission flightAdmission(Rig rig) {
        return flightAdmission(rig.snapshot().mobility());
    }

    /** 同上,对着这一刻的行动能力问。 */
    static Admission flightAdmission(Mobility mobility) {
        return mobility.mayFly() ? Admission.creative() : Admission.lacking(new Lack(Lack.Why.NO_FLIGHT, 0, mobility.gliderLife()));
    }

    /** 第一刻:决定这一趟从哪一段开始。 */
    private void begin() {
        if (rig.travel == Travel.FLY) {
            Admission asked = flightAdmission(rig);
            if (asked.lack() != null) {
                grounded(asked.lack());
            } else {
                walkOn();
            }
            return;
        }
        if (Snapshots.flying(rig.entity)) {
            current = new Alight(rig);
            phase = Phase.ALIGHT;
        } else {
            afoot();
        }
    }

    /** 身体在地上:走的一趟照路线走,滑翔的一趟排起飞。 */
    private void afoot() {
        if (rig.travel == Travel.WALK) {
            walkOn();
            return;
        }
        Admission asked = admission != null ? admission : Glide.admit(rig, goal, spent);
        if (asked.lack() != null) {
            grounded(asked.lack());
        } else {
            start(asked.plan());
        }
    }

    // ==================== 推进 ====================

    public Driver.State tick() {
        if (state != Driver.State.RUNNING) {
            return state;
        }
        if (current == null) {
            begin();
            if (state != Driver.State.RUNNING) {
                return state;
            }
        }
        if (settling) {
            if (rig.entity.onGround()) {
                settling = false;
                afterSortie();
            }
            return state;
        }
        Driver.State s = current.tick();
        if (s == Driver.State.RUNNING) {
            return state;
        }
        switch (phase) {
            case ALIGHT -> afoot();
            case TO_TAKEOFF -> {
                if (s == Driver.State.ARRIVED) {
                    // 到了起飞点:按此刻的身体与世界再问一遍,起飞点还能用就起飞
                    Admission asked = Glide.admit(rig, goal, spent);
                    if (asked.lack() != null) {
                        grounded(asked.lack());
                    } else {
                        if (!asked.plan().takeoff().equals(plan.takeoff())) {
                            // 走到了才发现这个起飞点不行:它这一趟不再用,别在两个起飞点之间来回走
                            spent.add(plan.takeoff());
                        }
                        start(asked.plan());
                    }
                } else {
                    PathLog.info("{} 走不到起飞点 {},换一个", rig.who, PathLog.pos(plan.takeoff()));
                    spent.add(plan.takeoff());
                    again();
                }
            }
            case SORTIE -> {
                Sortie sortie = (Sortie) current;
                if (s == Driver.State.ARRIVED && sortie.descended()) {
                    // 进了目标范围、降到落地:这一趟到了,落在哪里、离目标多远交给回执
                    state = s;
                    return state;
                }
                if (sortie.trapped()) {
                    // 被方块顶住:收工,接下来怎么办(挖开、换个方向)由她定
                    state = s;
                    halt = sortie.halt();
                    return state;
                }
                spent.add(sortie.takeoffCell());
                if (rig.entity.onGround()) {
                    afterSortie();
                } else {
                    settling = true;
                }
            }
            case FINAL -> {
                state = s;
                halt = current.halt();
            }
        }
        return state;
    }

    /**
     * 滑翔的一段没进目标范围就落了地(起飞没成、迫降、中途被打断):离目标超过 {@link Glide#REFLY_RANGE} 格就从这里再排起飞(缺什么就收工,说缺什么);
     * 不超过就收工,说离目标还有多远。
     */
    private void afterSortie() {
        Goal.Anchor target = goal.anchor(rig.entity.position());
        double left = target == null ? 0 : target.distance(rig.entity.position());
        if (left > Glide.REFLY_RANGE) {
            again();
        } else {
            grounded(new Lack(Lack.Why.SHORT, (int) Glide.REFLY_RANGE, (int) Math.round(left)));
        }
    }

    /** 再排一次起飞:没用过的起飞点里排得成就去,排不成(缺烟花、耐久、起飞的地方……)收工。 */
    private void again() {
        Admission next = Glide.admit(rig, goal, spent);
        if (next.lack() != null) {
            grounded(next.lack());
        } else {
            start(next.plan());
        }
    }

    private void start(Glide.Plan flight) {
        plan = flight;
        if (rig.entity.blockPosition().equals(flight.takeoff())) {
            current = new Sortie(rig, flight);
            phase = Phase.SORTIE;
        } else {
            PathLog.info("{} 飞 先走到起飞点 {},水平 {} 格", rig.who, PathLog.pos(flight.takeoff()), PathLog.num(flight.blocks()));
            current = new Driver(rig, Goals.at(flight.takeoff()), spec, 0, null, false, clock);
            phase = Phase.TO_TAKEOFF;
        }
    }

    /** 收工:飞不下去了,原因是 {@code lack}。 */
    private void grounded(Lack lack) {
        PathLog.info("{} 飞不下去了:{} {}", rig.who, lack, PathLog.body(rig.entity));
        halt = new Halt.Grounded(lack);
        state = Driver.State.HALTED;
    }

    /** 从身体此刻的位置照目标走(创造飞行进同一个搜索)到底:照调用方交进来的路线,没有就搜。 */
    private void walkOn() {
        current = new Driver(rig, goal, spec, budget, seed, passing, clock);
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
