package com.dwinovo.numen.core.nav;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.api.NavRequest;
import com.dwinovo.numen.pathing.api.NavStatus;
import com.dwinovo.numen.pathing.api.Navigation;
import com.dwinovo.numen.pathing.api.Navigator;
import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.api.Report;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.permission.ConsentItem;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/**
 * 执行:同伴的一趟路,从这里走到一个目标,经寻路的门面({@link Navigator#drive})完成,路上边走边细算。规划是另一件事
 * ({@link Survey})。交进来一条规划好的路就先照它走({@link #following}),走不下去按同样的目标与规格重搜。
 *
 * <h2>越过边界才问</h2>
 * 规格把要问主人的格算能走时({@link RouteSpec#consent}),那几格在路上照常规划;权限的拍板在动手那一刻:她的手({@link CompanionHands})
 * 走到那一格要挖、要放时问权限层,还没得到主人点头就停下、交回要问的那一条。这一趟就扣在那里({@link #consentNeeded}),由任务发起征询;
 * 主人答应了,授权进了权限快照,照没走完的那截路接着走;不答应,任务以被拒收场。开走之前不整条问一遍:要问的格可能根本走不到,
 * 走到之前世界也可能变了。
 *
 * <p>另外几件:端口每开一次导航按此刻组一份({@link CompanionPorts},权限快照里有主人刚答应的);结局渲染成给模型的英文、归到
 * 哪一种失败,只在 {@link NavText};这一趟开过的每一次导航交出的实际账({@link #reports}),停下时交给任务并进旅程账;
 * 她此刻在走的这一趟({@link #current})让反射看得见:脱困反射读"在推进",摔落反射认计划内的坠落。
 * 到达只看目标自己的判定,没有"差不多到了";要"靠近就行"由调用方编一个靠近的目标。没有路就照实收场,放不放宽规格是模型的决定。
 */
public final class Trip {

    public enum Status { RUNNING, ARRIVED, FAILED }

    private enum Phase {
        /** 还没开始。 */
        IDLE,
        /** 走到要问主人的那一格前停下,等主人答复。 */
        CONSENT,
        /** 在走。 */
        DRIVING,
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
    private List<Item> materials = ThrowawayBlocks.factory();
    /** 从目标里路过,不停稳。 */
    private boolean passing;
    /** 先照这条走(规划好交进来的,或停下问主人时没走完的那截);没有为 null。 */
    private Route seed;

    private Phase phase = Phase.IDLE;
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

    /** 照规划好的 {@code route} 走到 {@code goal}:它只是第一段,走不下去按同样的目标与规格重搜。 */
    public static Trip following(NumenPlayer player, Goal goal, RouteSpec spec, Route route, BlockPos toward) {
        Trip trip = new Trip(player, goal, spec, toward);
        trip.seed = route;
        return trip;
    }

    /**
     * 照受理之前准备时规划好的 {@code seed} 走到 {@code goal}:她还站在它的起点上才照它走({@link #following});准备期间身体
     * 挪了地方(还在干上一件活),那条路从别处起,就从脚下重新搜。没有 {@code seed} 就是从脚下搜。
     */
    public static Trip prepared(NumenPlayer player, Goal goal, RouteSpec spec, Route seed, BlockPos toward) {
        Feet here = Feet.of(player);
        return seed != null && here != null && here.node().equals(seed.start())
                ? following(player, goal, spec, seed, toward) : to(player, goal, spec, toward);
    }

    /** 这一趟要避开的生物换成 {@code threats}(战斗走位按它自己的那一份)。开走之前设。 */
    public Trip avoiding(Threats threats) {
        this.threats = threats;
        return this;
    }

    /** 这一趟垫路从 {@code materials} 里挑(路线描述写的那几种)。开走之前设。 */
    public Trip spending(List<Item> materials) {
        this.materials = List.copyOf(materials);
        return this;
    }

    /** 这一趟从目标里路过,走进去就算到了,不停稳。开走之前设。 */
    public Trip passing() {
        this.passing = true;
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
            case IDLE -> drive();
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
            case DONE -> {
            }
        }
        return status;
    }

    /** 从身体脚下开一次导航(先照 {@link #seed} 走,没有就搜);端口按此刻取,权限快照里已经有主人刚答应的。 */
    private void drive() {
        NavRequest request = NavRequest.to(goal, spec);
        if (seed != null) {
            request = request.following(seed);
            seed = null;
        }
        navigation = navigator().drive(passing ? request.passing() : request);
        phase = Phase.DRIVING;
    }

    /** 收一次导航:交出它的实际账。 */
    private void retire() {
        if (navigation != null) {
            done.add(navigation.stop());
            navigation = null;
        }
    }

    /**
     * 一次导航没走到:手走到一格要问主人的地方停下了,就扣住没走完的那截路、交出要问的那一条,等主人答复;其余照实收场。
     */
    private void conclude(Outcome outcome) {
        if (outcome instanceof Outcome.Denied denied && denied.reason() instanceof CompanionHands.Unasked unasked) {
            List<Route.Leg> rest = navigation.remaining();
            retire();
            seed = rest.isEmpty() ? null
                    : new Route(rest.get(0).maneuver().from(), rest.get(0).maneuver().start(), rest);
            consent = covered(unasked.item(), rest);
            phase = Phase.CONSENT;
            com.dwinovo.numen.core.Constants.LOG.info("[numen-task] 走到 {} 要问主人({}),停下等答复",
                    denied.cell().toShortString(), unasked.verdict().reason());
            return;
        }
        retire();
        this.outcome = outcome;
        fail(NavText.failure(outcome, player, Feet.cell(player), toward, spec, materials), NavText.type(outcome));
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
        return CompanionPorts.navigator(player, threats, materials);
    }

    // ==================== 对外 ====================

    /**
     * 换目标(跟着的东西挪了):在走的这一次导航照走或按新目标重搜,由门面判。还是同一个目标就什么都不变。
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
        if (phase == Phase.DRIVING) {
            navigation.retarget(next);
        }
    }

    /** 叫停:在飞的搜索作废、松开所有键,交出这一趟的全部实际账。 */
    public List<Report> stop() {
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

    /**
     * 停在 {@code at} 前问主人时,问的是哪几条:这一格,加上没走完的那截路上主人这一声答应同样放行的格(同一个动作、同一行规则、
     * 同一种东西,{@link ConsentItem#covers(ConsentItem)})。答应覆盖到哪儿,卡片上就列到哪儿;别的要问的格走到时再问。
     */
    private static List<ConsentItem> covered(ConsentItem at, List<Route.Leg> rest) {
        List<ConsentItem> items = new ArrayList<>(List.of(at));
        for (Route.Leg leg : rest) {
            for (Edit edit : leg.maneuver().edits()) {
                Permit permit = switch (edit) {
                    case Edit.Dig dig -> dig.permit();
                    case Edit.Place place -> place.permit();
                    case Edit.Catch caught -> caught.permit();
                    case Edit.Door door -> null;
                };
                if (permit instanceof Permit.Ask ask && ask.credential() instanceof ConsentItem item
                        && at.covers(item) && items.stream().noneMatch(i -> java.util.Objects.equals(i.pos(), item.pos()))) {
                    items.add(item);
                }
            }
        }
        return List.copyOf(items);
    }

    /** 停在要问主人的那一格前,要问的那几条;没停着是空表。 */
    public List<ConsentItem> consentNeeded() {
        return phase == Phase.CONSENT ? consent : List.of();
    }

    /** 主人答应了:权限快照按此刻重取(答应下来的那一格从此放行),照没走完的那截路接着走。 */
    public void consentGranted() {
        if (phase == Phase.CONSENT) {
            consent = List.of();
            drive();
        }
    }

    /** 身体此刻站着等搜索的结论(诊断为什么没路、等搜索回来),不在走。 */
    public boolean waiting() {
        return phase == Phase.DRIVING && navigation.waiting();
    }

    /** 在推进:在走的这一次导航说的;等主人答复时不算卡住。 */
    public boolean progressing() {
        return phase != Phase.DRIVING || navigation.progressing();
    }

    /** 身体此刻是计划内的坠落。 */
    public boolean plannedFall() {
        return phase == Phase.DRIVING && navigation.plannedFall();
    }

    /** 身体此刻在计划内的一段水下(规划时算过,此刻的氧气也撑得到这一段走完)。 */
    public boolean plannedDive() {
        return phase == Phase.DRIVING && navigation.plannedDive();
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

    /** 没走到时寻路给的结局;还在走、到了、或被叫停为 null。 */
    public Outcome outcome() {
        return outcome;
    }

    /** 给人说"朝哪儿"的那一格。 */
    public BlockPos toward() {
        return toward;
    }
}
