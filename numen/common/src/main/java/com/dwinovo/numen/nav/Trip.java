package com.dwinovo.numen.nav;

import com.dwinovo.numen.permission.Verb;
import com.dwinovo.numen.api.entity.Mouse;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import com.dwinovo.numen.FailureType;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.pathing.api.NavRequest;
import com.dwinovo.numen.pathing.api.NavStatus;
import com.dwinovo.numen.pathing.api.Navigation;
import com.dwinovo.numen.pathing.api.Navigator;
import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.api.Report;
import com.dwinovo.numen.pathing.drive.Blockage;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.api.permission.Action;
import com.dwinovo.numen.api.permission.ConsentItem;
import com.dwinovo.numen.api.permission.Listing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/**
 * 执行:同伴的一趟路,从这里走到一个目标,经寻路的门面({@link Navigator#drive})完成,路上边走边细算。规划是另一件事
 * ({@link Survey})。交进来一条规划好的路就先照它走({@link #following}),走不下去按同样的目标与规格重搜。
 *
 * <h2>越过边界才问,被拒的格这一趟里当墙</h2>
 * 规格把要问主人的格算能走时({@link RouteSpec#consent}),那几格在路上照常规划;权限的拍板在动手那一刻:她的鼠标({@link com.dwinovo.numen.api.entity.Mouse})
 * 走到那一格要挖、要放时问权限层,还没得到主人点头就停下、交回要问的那一条。这一趟就扣在那里({@link #consentNeeded}),由任务发起征询;
 * 主人答应了,授权进了权限快照,照没走完的那截路接着走;不答应({@link #consentRefused}),那一格在这一趟里当墙,从她此刻的位置
 * 重新规划绕开。动手那一刻权限层或服务端当场不许的格同样当墙、同样重新规划。重新规划之后还是撞上同一堵墙,或绕不开,这一趟才
 * 以被拒收场。开走之前不整条问一遍:要问的格可能根本走不到,走到之前世界也可能变了。
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
    /** 这一趟的规格;被拒的格当墙之后换成加了墙的那一份。 */
    private RouteSpec spec;
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
    /** 路上权限层与主人对哪几格怎么裁的;被拒的格已经当了墙。 */
    private final List<Ruling> rulings = new ArrayList<>();
    /** 主人不答应过的征询:同一行规则问出来的同一种东西({@link ConsentItem#covers})不再问第二遍,直接当墙。 */
    private final List<ConsentItem> refused = new ArrayList<>();

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
     * 一次导航没走到:手走到一格要问主人的地方停下了,就扣住没走完的那截路、交出要问的那一条,等主人答复;动手那一刻被拒了(权限层
     * 当场不许,或服务端退回),那一格当墙、重新规划;其余照实收场。
     */
    private void conclude(Outcome outcome) {
        if (outcome instanceof Outcome.Denied denied && denied.reason() instanceof Mouse.Refusal refusal) {
            if (refusal instanceof Mouse.Refusal.Asks unasked && refused.stream().noneMatch(r -> r.covers(unasked.item()))) {
                List<Route.Leg> rest = navigation.remaining();
                retire();
                seed = rest.isEmpty() ? null
                        : new Route(rest.get(0).maneuver().from(), rest.get(0).maneuver().start(), rest);
                consent = covered(unasked.item(), rest);
                phase = Phase.CONSENT;
                com.dwinovo.numen.Constants.LOG.info("[numen-task] 走到 {} 要问主人({}),停下等答复",
                        denied.cell().toShortString(), unasked.verdict().reason());
                return;
            }
            retire();
            EnumSet<PositionCosts.Use> uses;
            if (refusal instanceof Mouse.Refusal.Asks unasked) {
                // 主人对这一种东西已经说过不:不再问,这一格也当墙
                rulings.addAll(Ruling.refusedByOwner(List.of(unasked.item()), "refused earlier on this walk"));
                uses = wallsFor(unasked.item());
            } else {
                rulings.add(Ruling.refusedByRules(denied.cell(), NavText.name(player.level().getBlockState(denied.cell())),
                        NavText.reason(refusal)));
                uses = EnumSet.of(PositionCosts.Use.DIG, PositionCosts.Use.PLACE);
            }
            if (wall(denied.cell(), uses)) {
                com.dwinovo.numen.Constants.LOG.info("[numen-task] {} 动手被拒,这一趟里当墙,重新规划",
                        denied.cell().toShortString());
                seed = null;
                drive();
                return;
            }
        }
        retire();
        this.outcome = outcome;
        fail(NavText.failure(outcome, player, Feet.cell(player), toward, spec, materials) + aroundWhatWasRefused(outcome),
                refusedAround(outcome) ? FailureType.REFUSED : NavText.type(outcome));
    }

    /**
     * 这一格在这一趟里当墙({@code uses} 那几样都不许动它),从没有路可走的位置重新规划才会绕开。这些样子它已经全是墙了(规划绕不
     * 开它)返回 false。
     */
    private boolean wall(BlockPos cell, EnumSet<PositionCosts.Use> uses) {
        PositionCosts.Builder walls = PositionCosts.builder();
        boolean added = false;
        for (PositionCosts.Use use : uses) {
            if (!spec.positions().forbids(use, cell.asLong())) {
                walls.forbid(use, cell.asLong());
                added = true;
            }
        }
        if (added) {
            spec = spec.edit().positions(spec.positions().plus(walls.build())).build();
        }
        return added;
    }

    /** 主人不答应的这一条,在这一趟里不许动哪样:放就不许放,挖或别的就不许挖也不许放。 */
    private static EnumSet<PositionCosts.Use> wallsFor(ConsentItem item) {
        return item.kind() == Verb.PLACE ? EnumSet.of(PositionCosts.Use.PLACE)
                : EnumSet.of(PositionCosts.Use.DIG, PositionCosts.Use.PLACE);
    }

    /** 路上有被拒的格,而这一趟现在是因为没有别的路才没走到的:被拒的格就是没走成的原因,按被拒收场。 */
    private boolean refusedAround(Outcome outcome) {
        return rulings.stream().anyMatch(r -> !r.allowed())
                && (outcome instanceof Outcome.NoRoute || outcome instanceof Outcome.NeedsChanges
                        || outcome instanceof Outcome.NoMaterials);
    }

    /** 绕不开被拒的格时,在没走到的那句话后补上缘由与下一步;不是这样收场的是空串。 */
    private String aroundWhatWasRefused(Outcome outcome) {
        return refusedAround(outcome)
                ? "; the cells refused on the way were kept out of this walk and no other way was found: ask your owner"
                        + " to allow them, or pick another destination"
                : "";
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

    /** 路上权限层与主人对哪几格怎么裁的,按先后。 */
    public List<Ruling> rulings() {
        return List.copyOf(rulings);
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
                if (edit.permit() instanceof Permit.Ask ask && ask.credential() instanceof ConsentItem item
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
            rulings.addAll(Ruling.allowed(consent));
            consent = List.of();
            drive();
        }
    }

    /**
     * 主人不答应({@code words} 是他的原话):问的那几格在这一趟里当墙,没走完的那截路作废,从她此刻的位置重新规划绕开。问的
     * 那几格在规格里已经全是墙了(规划还是绕不开它们),这一趟以被拒收场。
     */
    public void consentRefused(String words) {
        if (phase != Phase.CONSENT) {
            return;
        }
        rulings.addAll(Ruling.refusedByOwner(consent, words));
        refused.addAll(consent);
        boolean walled = false;
        for (ConsentItem item : consent) {
            if (item.pos() != null) {
                walled |= wall(item.pos(), wallsFor(item));
            }
        }
        consent = List.of();
        seed = null;
        if (walled) {
            drive();
            return;
        }
        fail("changing " + Listing.coords(rulings.get(rulings.size() - 1).pos()) + " is refused by your owner: " + words
                + " and no way around it was found; pick another destination, or ask your owner to allow it",
                FailureType.REFUSED);
    }

    /** 身体此刻站着等搜索的结论(诊断为什么没路、等搜索回来),不在走。 */
    public boolean waiting() {
        return phase == Phase.DRIVING && navigation.waiting();
    }

    /** 在走的这一次导航里,最近一次"离目标最近处被拒"的那一步;没有为 null。 */
    public Blockage nearestRefusal() {
        return navigation == null ? null : navigation.nearestRefusal();
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
