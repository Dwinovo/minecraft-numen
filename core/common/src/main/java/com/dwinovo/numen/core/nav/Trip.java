package com.dwinovo.numen.core.nav;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.api.Bill;
import com.dwinovo.numen.pathing.api.NavRequest;
import com.dwinovo.numen.pathing.api.NavStatus;
import com.dwinovo.numen.pathing.api.Navigation;
import com.dwinovo.numen.pathing.api.Navigator;
import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.api.PlanQuery;
import com.dwinovo.numen.pathing.api.PlanResult;
import com.dwinovo.numen.pathing.api.Planning;
import com.dwinovo.numen.pathing.api.Report;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.permission.ConsentItem;
import com.dwinovo.numen.permission.Verdict;

import net.minecraft.core.BlockPos;

/**
 * 同伴的一趟路:一件活从这里走到一个目标,经寻路的门面({@link Navigator})完成。门面外面补上同伴这一侧的几件事:
 * <ul>
 *   <li><b>端口</b>:每开一次导航按此刻组一份({@link CompanionPorts}),权限快照里有主人刚答应的;</li>
 *   <li><b>开走之前问主人</b>:规格许动要主人同意的格({@code alter=any})时,先只搜不走出一条路,账上有要问的格就扣住,
 *       由任务发起征询({@link #consentNeeded});主人答应之后照这条路走。走到半路动手时又撞上要问的格(路重搜过),同样停下,
 *       从那里再出一条、再问;</li>
 *   <li><b>候选路线</b>:{@link #probing} 开着时,不许改地形而没有路(结局 {@link Outcome.NeedsAlter}),按要放宽的那一档规划
 *       几条候选,记进路线簿,连同每条要动的格交给任务;</li>
 *   <li><b>结局</b>:渲染成给模型的英文、归到哪一种失败,只在 {@link NavText};</li>
 *   <li><b>实际账</b>:这一趟开过的每一次导航交出的实际账({@link #reports}),停下时交给任务并进旅程账;</li>
 *   <li><b>反射看得见</b>:她此刻在走的这一趟({@link #current}):脱困反射读"在推进",摔落反射认计划内的坠落。</li>
 * </ul>
 * 到达只看目标自己的判定,没有"差不多到了";要"靠近就行"由调用方编一个靠近的目标。
 */
public final class Trip {

    public enum Status { RUNNING, ARRIVED, FAILED }

    private enum Phase {
        /** 还没开始。 */
        IDLE,
        /** 只搜不走,出一条开走前要过目的路。 */
        PLANNING,
        /** 扣着那条路,等主人答复。 */
        CONSENT,
        /** 在走。 */
        DRIVING,
        /** 没有干净的路,在规划放宽一档的候选。 */
        PROBING,
        /** 到了、收场或叫停。 */
        DONE
    }

    /** 同伴身上此刻在走的那一趟;一具身体一份。 */
    private static final class Current {
        Trip trip;
    }

    private final NumenPlayer player;
    private final RouteSpec spec;
    private Goal goal;
    private BlockPos toward;
    private Threats threats;
    private boolean probe;
    /** 先照这条走(路线簿里取的,或开走前过目过的);没有为 null。 */
    private Route seed;

    private Phase phase = Phase.IDLE;
    private Planning planning;
    /** 在规划候选时用的那一档规格。 */
    private RouteSpec relaxed;
    private Route held;
    private List<ConsentItem> consent = List.of();
    private Navigation navigation;
    /** 这一趟没走到时的结局;还在走或到了为 null。 */
    private Outcome outcome;
    /** 这一趟里已经停下的几次导航交出的实际账。 */
    private final List<Report> done = new ArrayList<>();

    private Status status = Status.RUNNING;
    private String failReason = "";
    private FailureType failType = FailureType.UNKNOWN;

    private Trip(NumenPlayer player, Goal goal, RouteSpec spec, BlockPos toward) {
        this.player = player;
        this.goal = goal;
        this.spec = spec;
        this.toward = toward.immutable();
        this.threats = CompanionPorts.dangers(player);
    }

    /**
     * 按 {@code spec} 走到 {@code goal}。
     *
     * @param toward 给人说"朝哪儿"的那一格(失败回执里的方向与距离)
     */
    public static Trip to(NumenPlayer player, Goal goal, RouteSpec spec, BlockPos toward) {
        return new Trip(player, goal, spec, toward);
    }

    /** 沿路线簿里的一条走:目标与规格是它的,它自己的路线先照着走,走不下去按同样的目标与规格重搜。 */
    public static Trip along(NumenPlayer player, RouteBook.Entry route) {
        Trip trip = new Trip(player, route.goal(), route.spec(), route.toward());
        trip.seed = route.route();
        return trip.probing();
    }

    /** 不许改地形而没有路时,规划放宽一档的候选路线、记进路线簿,回执里列出来。开走之前设。 */
    public Trip probing() {
        this.probe = true;
        return this;
    }

    /** 这一趟要避开的生物换成 {@code threats}(战斗走位按它自己的那一份)。开走之前设。 */
    public Trip avoiding(Threats threats) {
        this.threats = threats;
        return this;
    }

    /** 她此刻在走的那一趟;没有为 null。 */
    public static Trip current(NumenPlayer player) {
        return player.state(Current.class, Current::new).trip;
    }

    // ==================== 每刻 ====================

    public Status tick() {
        if (phase == Phase.DONE) {
            return status;
        }
        player.state(Current.class, Current::new).trip = this;
        switch (phase) {
            case IDLE -> start();
            case PLANNING -> pollPlanning();
            case CONSENT -> {
            }
            case DRIVING -> {
                NavStatus s = navigation.tick();
                switch (s.state()) {
                    case RUNNING, STOPPED -> {
                    }
                    case ARRIVED -> finish(Status.ARRIVED);
                    case FAILED -> conclude(s.outcome());
                }
            }
            case PROBING -> pollProbe();
            case DONE -> {
            }
        }
        return status;
    }

    /** 开走:许动要主人同意的格的规格,先拿一条路过目(路线簿里取的就是那一条,否则现出一条);别的规格直接走。 */
    private void start() {
        Route route = seed;
        seed = null;
        if (spec.alter() != RouteSpec.Alter.ANY) {
            drive(route);
        } else if (route != null) {
            hold(route, Bill.of(route));
        } else {
            planning = navigator().plan(PlanQuery.of(goal, spec, 1));
            phase = Phase.PLANNING;
        }
    }

    private void pollPlanning() {
        PlanResult result = planning.poll();
        if (result == null) {
            return;
        }
        planning = null;
        if (result.candidates().isEmpty()) {
            conclude(result.outcome());
            return;
        }
        PlanResult.Candidate first = result.candidates().get(0);
        hold(first.route(), first.bill());
    }

    /** 这条路的账上有要问主人的格就扣住等答复,没有就照它走。 */
    private void hold(Route route, Bill bill) {
        List<ConsentItem> asks = new ArrayList<>();
        for (Bill.Consent c : bill.consents()) {
            if (c.credential() instanceof ConsentItem item) {
                asks.add(item);
            }
        }
        if (asks.isEmpty()) {
            drive(route);
            return;
        }
        held = route;
        consent = List.copyOf(asks);
        phase = Phase.CONSENT;
    }

    /** 从身体脚下开一次导航(照 {@code route} 先走,没有就搜);端口按此刻取,权限快照里已经有主人刚答应的。 */
    private void drive(Route route) {
        NavRequest request = NavRequest.to(goal, spec);
        navigation = navigator().drive(route == null ? request : request.following(route));
        phase = Phase.DRIVING;
    }

    /** 收一次导航:交出它的实际账。 */
    private void retire() {
        if (navigation != null) {
            done.add(navigation.stop());
            navigation = null;
        }
    }

    /** 一次导航或规划没走到:要问主人的格就停下再问;不许改地形就去规划候选;其余照实收场。 */
    private void conclude(Outcome outcome) {
        retire();
        this.outcome = outcome;
        if (outcome instanceof Outcome.Denied denied && denied.reason() instanceof Verdict verdict && verdict.asks()
                && spec.alter() == RouteSpec.Alter.ANY) {
            planning = navigator().plan(PlanQuery.of(goal, spec, 1));
            phase = Phase.PLANNING;
            return;
        }
        if (outcome instanceof Outcome.NeedsAlter needs && probe) {
            relaxed = spec.edit().alter(needs.level()).build();
            planning = navigator().plan(PlanQuery.of(goal, relaxed, PlanQuery.MAX_CANDIDATES));
            phase = Phase.PROBING;
            return;
        }
        fail(NavText.failure(outcome, player, Feet.cell(player), toward, spec), NavText.type(outcome));
    }

    /** 候选出来了:记进路线簿,连同清单收场;一条都没有就照那次规划的结局说。 */
    private void pollProbe() {
        PlanResult result = planning.poll();
        if (result == null) {
            return;
        }
        planning = null;
        if (result.candidates().isEmpty()) {
            outcome = result.outcome();
            fail(NavText.failure(outcome, player, Feet.cell(player), toward, spec), NavText.type(outcome));
            return;
        }
        RouteBook book = RouteBook.of(player);
        long now = player.level().getGameTime();
        List<RouteBook.Entry> listed = new ArrayList<>();
        for (PlanResult.Candidate c : result.candidates()) {
            // 候选是按放宽一档的规格搜出来的,照它走也用那一档
            listed.add(book.add(goal, toward, relaxed, c.route(), now));
        }
        fail(NavText.noCleanRoute(Feet.cell(player), toward, spec.alter().mayAlter(), listed),
                FailureType.TERRAIN_BLOCKED);
    }

    private void finish(Status end) {
        retire();
        status = end;
        phase = Phase.DONE;
        forget();
    }

    private void fail(String reason, FailureType type) {
        failReason = reason;
        failType = type;
        finish(Status.FAILED);
    }

    private void forget() {
        Current slot = player.state(Current.class, Current::new);
        if (slot.trip == this) {
            slot.trip = null;
        }
    }

    private Navigator navigator() {
        return CompanionPorts.navigator(player, threats);
    }

    // ==================== 对外 ====================

    /**
     * 换目标(跟着的东西挪了):在走的这一次导航照走或按新目标重搜,由门面判;还在出路线时按新目标重新出。还是同一个目标就什么都不变。
     *
     * @param toward 给人说"朝哪儿"的那一格
     */
    public void retarget(Goal next, BlockPos toward) {
        if (next.equals(goal)) {
            this.toward = toward.immutable();
            return;
        }
        this.goal = next;
        this.toward = toward.immutable();
        switch (phase) {
            case DRIVING -> navigation.retarget(next);
            case PLANNING -> {
                planning.cancel();
                planning = navigator().plan(PlanQuery.of(goal, spec, 1));
            }
            default -> {
            }
        }
    }

    /** 叫停:在飞的搜索作废、松开所有键,交出这一趟的全部实际账。 */
    public List<Report> stop() {
        if (planning != null) {
            planning.cancel();
            planning = null;
        }
        retire();
        if (phase != Phase.DONE) {
            phase = Phase.DONE;
            forget();
        }
        return List.copyOf(done);
    }

    /** 到此刻为止这一趟的实际账:停下的几次,加上还在走的这一次。 */
    public List<Report> reports() {
        List<Report> out = new ArrayList<>(done);
        if (navigation != null) {
            out.add(navigation.report());
        }
        return out;
    }

    /** 扣着等主人点头的那条路要问的清单;没扣着是空表。 */
    public List<ConsentItem> consentNeeded() {
        return phase == Phase.CONSENT ? consent : List.of();
    }

    /** 主人答应了:照扣着的那条路走,权限快照按此刻重取,答应下来的格从此放行。 */
    public void consentGranted() {
        if (phase == Phase.CONSENT) {
            consent = List.of();
            drive(held);
            held = null;
        }
    }

    /** 身体此刻站着等搜索的结论(出路线、诊断为什么没路、等搜索回来),不在走。 */
    public boolean waiting() {
        return switch (phase) {
            case PLANNING, PROBING -> true;
            case DRIVING -> navigation.waiting();
            default -> false;
        };
    }

    /** 在推进:在走的这一次导航说的;不在走(出路线、等主人)时不算卡住。 */
    public boolean progressing() {
        return phase != Phase.DRIVING || navigation.progressing();
    }

    /** 身体此刻是计划内的坠落。 */
    public boolean plannedFall() {
        return phase == Phase.DRIVING && navigation.plannedFall();
    }

    /** 还没走完的那几步(排障画路线用)。 */
    public List<Route.Leg> remaining() {
        return navigation == null ? List.of() : navigation.remaining();
    }

    public Status status() {
        return status;
    }

    /** 收场时给模型的那句话。 */
    public String failReason() {
        return failReason;
    }

    public FailureType failType() {
        return failType;
    }

    /** 没走到时寻路给的结局(连同为了列候选而规划的那一次);还在走、到了、或被叫停为 null。 */
    public Outcome outcome() {
        return outcome;
    }

    /** 给人说"朝哪儿"的那一格。 */
    public BlockPos toward() {
        return toward;
    }
}
