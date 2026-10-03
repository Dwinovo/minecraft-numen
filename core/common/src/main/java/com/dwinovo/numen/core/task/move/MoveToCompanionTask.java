package com.dwinovo.numen.core.task.move;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.cli.Shapes;
import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.nav.BoatNav;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.core.nav.Survey;
import com.dwinovo.numen.core.nav.Trip;
import com.dwinovo.numen.core.route.Itinerary;
import com.dwinovo.numen.core.route.Plan;
import com.dwinovo.numen.core.route.RoutePlanning;
import com.dwinovo.numen.core.route.RouteText;
import com.dwinovo.numen.core.route.Routes;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.permission.ConsentItem;
import com.dwinovo.numen.permission.Listing;
import com.dwinovo.numen.task.Preparation;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.task.TaskState;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * {@code numen.move.go(route)} (and the library function {@code numen.move.goto_}, which writes her own route first) on the companion body: walk a route from wherever she
 * stands. Planning and walking are two things ({@link RoutePlanning} → {@link Trip}):
 * <ol>
 *   <li><b>plan from here</b> — every leg once, only searching, before the walk is accepted ({@link #preparation}):
 *       a route that can't be walked or goes beyond its promise is refused on the spot, with no task id; the receipt of
 *       an accepted one carries the plan. When the body moved while the plan was being made (it was still busy with
 *       the work before), it plans again from where it stands and holds that against the plan made then, the way
 *       {@code move go} does from anywhere else;</li>
 *   <li><b>hold it against the promise</b> — the plan she saw on the route is the promise: when this plan would break,
 *       place or ask about any cell the promise does not, she does not set off and says the difference. A route never
 *       planned takes this plan as its promise and walks straight away;</li>
 *   <li><b>ask once</b> — every cell needing the owner's consent is asked before the first step;</li>
 *   <li><b>walk leg by leg</b> — each leg is driven under its spec bound to the promise ({@link Plan#bind}): when the
 *       world changes on the way and the engine re-searches, it only finds ways inside the promise; finding none it
 *       stops, and a fresh plan from where she stands says which cells lie outside it. A leg only partly seen when
 *       planning (or not planned because the one before it was only partly seen) is driven toward its own goal all the
 *       same, the seen part first: the engine works out the rest on the way, and the promise keeps it from changing
 *       any cell the plan did not list — a walk that changes nothing goes as far as it likes.</li>
 * </ol>
 * Arrival is the goal's own membership, decided by the pathing module. Results always echo the ACTUAL position reached
 * (and the real ground height) so the model learns the terrain. Every walk is written onto the route.
 */
public final class MoveToCompanionTask extends AbstractCompanionTask<MoveToTaskRecord> {

    private static final long TICKS_PER_BLOCK = 20;
    private static final long MAX_EXTRA_TICKS = 5 * 60 * 20;
    /** Progress lease: while the journey is making progress, the deadline is kept this far ahead — a healthy
     *  multi-minute dig route never times out mid-stride, and a stalled one still returns the body within one lease. */
    private static final long PROGRESS_LEASE_TICKS = 30 * 20;
    /** Hard check-in cap: even a healthy marathon yields (with a resumable result) after this many ticks of walking
     *  ({@link #workTicks()} — ticks spent waiting on the planner don't count), bounding how long the LLM goes without
     *  control. Renewals never extend the journey past this. */
    private static final long CHECK_IN_CAP_TICKS = 5 * 60 * 20;

    private enum Phase {
        /** 坐在船上先驾船。 */
        BOAT,
        /** 从脚下规划整条。 */
        PLANNING,
        /** 开走前问主人。 */
        CONSENT,
        /** 一段一段地走。 */
        DRIVING,
        /** 半路停下了,从这里规划剩下几段,看是不是承诺外的格挡着。 */
        EXPLAINING
    }

    /** Ceiling for lease renewals on the work clock (start + {@link #CHECK_IN_CAP_TICKS}); 0 = unset. */
    private long leaseCapWork;

    private Phase phase;
    /** 走的这条路线(受理后从存档里取)。 */
    private Itinerary route;
    /** 承诺:她看过的那份计划。 */
    private Plan promise;
    private RoutePlanning planning;
    /** 这一趟出发前从脚下规划出来的。 */
    private RoutePlanning.Result planned;
    private List<ConsentItem> asks = List.of();
    /** 在走第几段(从 0 数)。 */
    private int leg;
    /** 半路停下的那一段与结局,等从这里规划的结论回来再说。 */
    private int stoppedLeg;
    private Outcome stopped;
    /** 船腿:开工时坐在船上就先驾船,靠岸(或搁浅)后接规划与步行。null = 没有/已交棒。 */
    private BoatNav boatLeg;
    /** 出发那一刻(主世界游戏刻),记进走过的记录。 */
    private long startedAt;
    /** 这一趟已经记进路线了。 */
    private boolean recorded;
    /**
     * 终点是一块区域时,量"离终点多远"的那一格:出发时离她最近的一格,这一趟里不变。区域那时就不在,规划当场拒绝、不出发,
     * 用不上它,为 null。
     */
    private BlockPos areaCell;

    public MoveToCompanionTask(NumenPlayer player, MoveToTaskRecord record) {
        super(player, record);
    }

    private Routes routes() {
        return Routes.of(player.getServer(), player.getOwnerUuid());
    }

    private long now() {
        return player.getServer().overworld().getGameTime();
    }

    /**
     * 受理之前:路线得在、在她这个维度里;坐在船上先驾船的,规划留到靠岸以后。其余从她此刻脚下规划整条,结论与开走前的判断
     * 同一处({@link #hold}):走不通、超出承诺就是这次调用的错误结果;走得通就受理,回执带上这份计划(几步、要改的格、要问主人
     * 的格)。
     */
    @Override
    protected Preparation preparation() {
        TaskResult missing = locate();
        if (missing != null) {
            return Preparation.refused(missing);
        }
        if (boatTo() != null) {
            return Preparation.READY;
        }
        RoutePlanning planning = RoutePlanning.of(player, route);
        com.dwinovo.numen.core.Constants.LOG.info("[numen-task] go {} 受理前规划 {} 段", route.name(),
                route.legs().size());
        return new Preparation() {
            @Override
            public Preparation.Readiness poll() {
                RoutePlanning.Result result = planning.poll();
                if (result == null) {
                    return null;
                }
                Blocked blocked = hold(result);
                return blocked != null ? Preparation.Readiness.refused(TaskResult.fail(blocked.type().kind(),
                        blocked.why(), blocked.hint(), resultData()))
                        : Preparation.Readiness.ready(RouteText.accepted(route, result.plan()));
            }

            @Override
            public void cancel() {
                planning.cancel();
            }
        };
    }

    /**
     * 从存档里取这条路线,认出终点是区域时量距离的那一格;路线不在了、还没规划过(走只照计划走,计划是 {@code numen.route.plan} 的事)、
     * 不在她这个维度里,返回那个失败,否则 null。
     */
    private TaskResult locate() {
        route = routes().get(r.route);
        if (route == null) {
            return TaskResult.fail(ErrorKind.NOT_FOUND, "there is no route named " + r.route + " any more",
                    "numen.route.list()");
        }
        if (route.plan() == null) {
            return TaskResult.fail(ErrorKind.FAILED, "route " + route.name() + " has no plan yet, and numen.move.go only "
                    + "walks a planned route: numen.route.plan plans it from where you stand without moving and lists every "
                    + "block it changes; then numen.move.go walks it", "numen.route.plan(\"" + route.name() + "\")");
        }
        if (!route.dimension().equals(player.level().dimension().location())) {
            return TaskResult.fail(ErrorKind.FAILED, "route " + route.name() + " lies in " + route.dimension()
                    + ", and I am in " + player.level().dimension().location() + "; its coordinates mean nothing here",
                    null);
        }
        if (route.destination().area() != null) {
            areaCell = Destination.toward(player, route.destination(), Feet.cell(player));
        }
        return null;
    }

    /**
     * 载具处置:坐在船上而第一个途经点有 x、z(或是一块此刻在的区域),先驾船——船腿走到离它最近的水格,靠岸后接规划与步行
     * (见 tickBoatLeg)。其余情况(矿车没有舵、马的寻路仍按步行物理算)直接规划;下座驾是步行导航自己的事,记进身体动作。
     *
     * @return 船腿驶向的那一格;不先驾船为 null
     */
    private BlockPos boatTo() {
        if (!(player.isPassenger() && player.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat)) {
            return null;
        }
        Destination.Stop first = route.legs().get(0).to();
        return first.x() == null && first.area() == null ? null
                : Destination.toward(player, first, player.blockPosition());
    }

    @Override
    protected void onStart() {
        startedAt = now();
        if (!prepared()) {
            TaskResult missing = locate();
            if (missing != null) {
                recorded = true;
                fail(missing.message(), FailureType.TARGET_LOST, missing.hint());
                return;
            }
        }
        extendDeadline();
        BlockPos boatTo = boatTo();
        if (boatTo != null) {
            boatLeg = new BoatNav(player, boatTo);
            phase = Phase.BOAT;
            com.dwinovo.numen.core.Constants.LOG.info("[numen-task] go {} 驾船先行", route.name());
            return;
        }
        if (planned != null && Feet.cell(player).equals(planned.plan().from())) {
            // 受理前的规划就是从她此刻站的这一格做的:照它开走
            setOff();
            return;
        }
        // 没准备过,或准备期间身体还在干上一件活、挪了地方:和 move go 从别处出发一样,从这里重新规划,拿准备时的那份当承诺比
        plan();
    }

    /** 从脚下规划整条。 */
    private void plan() {
        planning = RoutePlanning.of(player, route);
        phase = Phase.PLANNING;
        com.dwinovo.numen.core.Constants.LOG.info("[numen-task] go {} 规划 {} 段", route.name(), route.legs().size());
    }

    /** 初始期限按直线距离给(地形难度这时不知道,上路之后由进度租约接手)。 */
    private void extendDeadline() {
        long extra = Math.min(MAX_EXTRA_TICKS, 600 + (long) (repDistance() * TICKS_PER_BLOCK));
        r.extendDeadlineTo(player.level().getGameTime() + extra);
        leaseCapWork = workTicks() + CHECK_IN_CAP_TICKS;
    }

    @Override
    protected TaskState onTick() {
        return switch (phase) {
            case BOAT -> tickBoatLeg();
            case PLANNING -> {
                awaitSearch();
                RoutePlanning.Result result = planning.poll();
                yield result == null ? TaskState.RUNNING : decide(result);
            }
            case CONSENT -> {
                player.controls().stop();
                ConsentAnswer answer = consult(asks);
                if (answer == null) {
                    yield TaskState.RUNNING;
                }
                if (!answer.allowed()) {
                    yield end(answer.refusal(asks), FailureType.REFUSED, null);
                }
                leg = 0;
                yield startLeg();
            }
            case DRIVING -> drive();
            case EXPLAINING -> {
                awaitSearch();
                RoutePlanning.Result result = planning.poll();
                yield result == null ? TaskState.RUNNING : explained(result);
            }
        };
    }

    /** 规划回来了:走不通、超出承诺就照那句话收场、不走;否则先问主人,再开走。 */
    private TaskState decide(RoutePlanning.Result result) {
        planning = null;
        Blocked blocked = hold(result);
        return blocked != null ? end(blocked.why(), blocked.type(), blocked.hint()) : setOff();
    }

    /** 不走的那句话、归到哪一种失败、能照抄的下一步(没有为 null)。 */
    private record Blocked(String why, FailureType type, String hint) {}

    /**
     * 从脚下规划出来的这一份拿来比:有走不通的段,或超出承诺(准备时定下的那份;还没有就是路线上她看过的那份),就是不走的原因;
     * 否则记下承诺、这一份规划与要问主人的格,返回 null。受理之前的准备与开走前的规划都经这里,说法只此一处。
     */
    private Blocked hold(RoutePlanning.Result result) {
        Plan fresh = result.plan();
        Plan saw = promise != null ? promise : route.plan();
        int bad = fresh.unreachable();
        if (bad >= 0 && bad >= result.legs().size()) {
            // 那一段此刻就编不成目标(去处写不通、点名的区域不在了):没有搜过,照编不成的原话说
            return new Blocked("can't walk " + legName(bad) + " as it stands: " + fresh.legs().get(bad).why(),
                    FailureType.NO_PATH, null);
        }
        if (bad >= 0) {
            FailureType type = bad < result.found().size() && !result.found().get(bad).reached()
                    ? NavText.type(result.found().get(bad).outcome()) : FailureType.NO_PATH;
            return new Blocked("blocked on " + legName(bad) + ": got within " + String.format("%.1f", repDistance())
                    + " blocks of " + route.destination().words() + " (now on the ground at y="
                    + player.blockPosition().getY() + "). " + fresh.legs().get(bad).why() + ".", type, null);
        }
        Plan.Difference diff = fresh.beyond(saw);
        if (!diff.isEmpty()) {
            return new Blocked("the way from here goes beyond the plan of route " + route.name() + " (made from "
                    + Listing.coords(saw.from()) + "), so I did not set off: it would also "
                    + RouteText.beyond(diff) + ". numen.route.plan plans it from here and shows it; then numen.move.go keeps to "
                    + "that plan.", FailureType.TERRAIN_BLOCKED, "numen.route.plan(\"" + route.name() + "\")");
        }
        promise = saw;
        planned = result;
        asks = result.consents();
        return null;
    }

    /** 照 {@link #planned} 开走:有要问主人的格先问(运行中等答复),再一段一段走。 */
    private TaskState setOff() {
        if (!asks.isEmpty()) {
            phase = Phase.CONSENT;
            return TaskState.RUNNING;
        }
        leg = 0;
        return startLeg();
    }

    /**
     * 开走第 {@link #leg} 段:照这一段自己的目标走,规格绑上承诺。整段看清了就照规划出的路走;只看清一截就拿那一截当开头,
     * 后面由执行层边走边算;没规划(前一段没看清)就从脚下算起。未知的部分要改承诺外的格时,重搜找不到路,停下再说是哪几格。
     */
    private TaskState startLeg() {
        if (leg >= route.legs().size()) {
            return arrived();
        }
        RoutePlanning.Leg target = planned.legs().get(leg);
        Survey.Found found = leg < planned.found().size() ? planned.found().get(leg) : null;
        RouteSpec spec = promise.bind(target.way().spec());
        var seed = found == null ? null : found.reached() ? found.route() : found.partial();
        nav = seed == null ? Trip.to(player, target.way().goal(), spec, target.toward())
                : Trip.following(player, target.way().goal(), spec, seed, target.toward());
        phase = Phase.DRIVING;
        com.dwinovo.numen.core.Constants.LOG.info("[numen-task] go {} 第 {} 段{} alter={}", route.name(), leg + 1,
                found != null && found.reached() ? "" : "(计划只看清一截或没规划,边走边算)", spec.alter());
        return TaskState.RUNNING;
    }

    private TaskState drive() {
        // Progress lease: while the walk is making progress, keep the deadline PROGRESS_LEASE ahead — never past the
        // check-in cap. Progress, NOT goal distance, is the liveness signal: healthy routes routinely move away from
        // the goal (skirting a lake, spiraling down), and the flat budget above can't price terrain.
        if (nav.progressing()) {
            renewLease();
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> {
                stopNav();
                leg++;
                yield startLeg();
            }
            case FAILED -> {
                stopped = nav.outcome();
                stoppedLeg = leg;
                stopNav();
                planning = RoutePlanning.from(player, route, leg);
                phase = Phase.EXPLAINING;
                yield TaskState.RUNNING;
            }
        };
    }

    /** 半路停下之后从这里规划的结论:要承诺外的格就说是哪几格;不然照寻路的结局说。 */
    private TaskState explained(RoutePlanning.Result result) {
        planning = null;
        Plan.Difference diff = result.plan().beyond(promise);
        if (!diff.isEmpty()) {
            return end("stopped on " + legName(stoppedLeg) + " at " + here(player.blockPosition().getY())
                    + ": the way on from here needs cells outside the plan I keep to — it would "
                    + RouteText.beyond(diff) + ". numen.route.plan plans it from here and shows it; then numen.move.go keeps to "
                    + "that plan.", FailureType.TERRAIN_BLOCKED, "numen.route.plan(\"" + route.name() + "\")");
        }
        RoutePlanning.Leg target = planned.legs().get(stoppedLeg);
        String why = NavText.failure(stopped, player, Feet.cell(player), target.toward(), target.way().spec(),
                new NavText.OnRoute(route.name(), stoppedLeg + 1, route.legs().size()));
        return end("blocked on " + legName(stoppedLeg) + ": got within " + String.format("%.1f", repDistance())
                + " blocks of " + route.destination().words() + " (now on the ground at y="
                + player.blockPosition().getY() + "). " + why + ".", NavText.type(stopped), null);
    }

    /** 最后一段到了。 */
    private TaskState arrived() {
        record(true, "arrived");
        return TaskState.SUCCESS;
    }

    /** 这一趟以失败收场:记进路线,交给任务的收场。 */
    private TaskState end(String why, FailureType type, String hint) {
        record(false, "failed (" + type.name().toLowerCase(java.util.Locale.ROOT) + ")");
        fail(why, type, hint);
        return TaskState.FAILED;
    }

    /** 这一趟记进路线走过的记录(路线被删了就不记)。 */
    private void record(boolean arrived, String end) {
        if (recorded) {
            return;
        }
        recorded = true;
        Routes routes = routes();
        Itinerary latest = routes.get(route.name());
        if (latest != null) {
            routes.put(latest.walked(new Itinerary.Walk(player.getGameProfile().getName(), startedAt,
                    now() - startedAt, arrived, end)));
        }
    }

    /** "第 2 段(共 3 段)的 home 路线"的英文:只有一段就只说路线。 */
    private String legName(int index) {
        return route.legs().size() == 1 ? "route " + route.name()
                : "leg " + (index + 1) + " of " + route.legs().size() + " of route " + route.name();
    }

    /** 续约:期限保持在租约窗口里,但这一程干活的刻数不超过 {@link #CHECK_IN_CAP_TICKS}。 */
    private void renewLease() {
        if (leaseCapWork <= 0) {
            return;
        }
        long capLeft = leaseCapWork - workTicks();
        r.extendDeadlineTo(player.level().getGameTime() + Math.min(PROGRESS_LEASE_TICKS, capLeft));
    }

    /**
     * 船腿的一刻:驾船朝第一个途经点推进,终态(靠岸或搁浅)都走同一条接力——到不了的水路不算失败,只是"这条腿到此为止",
     * 剩下的路归规划与步行(步行导航起步自会下船,并记进身体动作)。船留在原地,那是她的船,不是垃圾。
     */
    private TaskState tickBoatLeg() {
        if (boatLeg.progressing()) {
            renewLease();
        }
        var status = boatLeg.tick();
        if (status == BoatNav.Status.RUNNING) {
            return TaskState.RUNNING;
        }
        String how = status == BoatNav.Status.ARRIVED ? "靠岸" : boatLeg.failReason();
        boatLeg.stop();
        boatLeg = null;
        com.dwinovo.numen.core.Constants.LOG.info(
                "[numen-task] 船腿结束({}),接规划 feet={}", how, player.blockPosition().toShortString());
        plan();
        return TaskState.RUNNING;
    }

    /** Representative remaining distance (blocks) to the destination, for the deadline estimate and the replies. */
    private double repDistance() {
        Destination.Stop d = route.destination();
        if (d.area() != null) {
            return areaCell == null ? 0 : Math.sqrt(player.distanceToSqr(Vec3.atBottomCenterOf(areaCell)));
        }
        if (d.x() == null) {
            return Math.abs(player.getY() - d.y());
        }
        double dx = d.x() + 0.5 - player.getX();
        double dz = d.z() + 0.5 - player.getZ();
        double dy = d.y() == null ? 0 : d.y() - player.getY();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 走完(或停下)时她在哪(Pos,小数)、走的哪条路线、离终点还有多远。 */
    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("pos", Shapes.pos(player.position()));
        if (route != null) {
            data.put("route", route.name());
            data.put("distance_left", Math.round(repDistance() * 10.0) / 10.0);
        }
        return data;
    }

    /** Success copy — always names the real position so the model learns the terrain. */
    @Override
    protected String successMessage() {
        int gy = player.blockPosition().getY();
        Destination.Stop d = route.destination();
        if (d.area() != null) {
            String inArea = switch (d.arrive()) {
                case AT -> "reached area " + d.area() + ", standing at " + here(gy);
                case USE -> "standing at " + here(gy) + ", with " + usedBlock() + " of area " + d.area()
                        + " in sight and in reach — use it from here";
                case NEAR -> "arrived within " + d.near() + " blocks of area " + d.area() + ", standing at " + here(gy);
                case DIG -> "standing at " + here(gy) + ", within reach of a block of area " + d.area()
                        + " — `numen.work.dig(\"" + d.area() + "\")` digs it from here";
                case REACH -> "standing at " + here(gy) + ", within reach of a cell of area " + d.area()
                        + " to build into";
            };
            return inArea + ", via route " + route.name() + ".";
        }
        BlockPos cell = d.cell();
        String reached = switch (d.arrive()) {
            case AT -> cell != null ? "reached the exact cell " + coords(cell)
                    : d.x() != null ? "arrived at location x=" + d.x() + " z=" + d.z() + ", standing on the ground at y="
                            + gy
                    : "reached elevation y=" + gy;
            case USE -> "standing at " + here(gy) + ", with the " + block(cell) + " at " + coords(cell)
                    + " in sight and in reach — use it from here";
            case NEAR -> "arrived within " + d.near() + " blocks of " + (cell != null ? coords(cell)
                    : "location x=" + d.x() + " z=" + d.z()) + ", standing at " + here(gy);
            case DIG -> "standing at " + here(gy) + ", with the " + block(cell) + " at " + coords(cell)
                    + " within reach — `numen.work.dig({x = " + cell.getX() + ", y = " + cell.getY() + ", z = "
                    + cell.getZ() + "})` digs it from here";
            case REACH -> "standing at " + here(gy) + ", with " + coords(cell) + " within reach to build into";
        };
        return reached + ", via route " + route.name() + ".";
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /**
     * 到了一块区域的 {@code use}:她在看的是哪一个方块——终点那一段的目标说停在这里要看得见的是谁({@link Goal#sight},
     * 多个取其一时是站在这里满足的那个成员的)。
     */
    private String usedBlock() {
        Goal goal = planned.legs().get(planned.legs().size() - 1).way().goal();
        Feet feet = Feet.of(player);
        Goal.Sighting sight = feet == null ? null : goal.sight(feet.node().getX(), feet.node().getY(),
                feet.node().getZ(), feet.stance());
        return sight == null ? "one of its blocks" : "the " + block(sight.target()) + " at " + coords(sight.target());
    }

    private String block(BlockPos pos) {
        return NavText.name(player.level().getBlockState(pos));
    }

    @Override
    protected String timeoutMessage() {
        int gy = player.blockPosition().getY();
        // Two different stories for the model: a stall (progress dried up — something is wrong, reconsider) vs a
        // check-in (journey healthy but longer than the cap — resuming is the right move).
        boolean stalled = nav == null || !nav.progressing();
        return "timed out " + String.format("%.1f", repDistance()) + " blocks from target (now at " + here(gy) + "); "
                + (stalled
                        ? "progress had stopped — likely blocked; numen.move.go(\"" + route.name() + "\") tries again from "
                                + "here, or add a waypoint (numen.route.via(\"" + route.name() + "\", {at = ...})) or "
                                + "`numen.scan.blocks` for a way through."
                        : "the journey was still progressing and simply exceeded its check-in budget; numen.move.go(\""
                                + route.name() + "\") goes on from here.");
    }

    @Override
    protected String cancelledMessage() {
        return "cancelled before reaching target";
    }

    private String here(int gy) {
        return String.format("%.0f,%d,%.0f", player.getX(), gy, player.getZ());
    }

    /** Release the nav (base), drop the planning in flight, ship the oars; a walk that ends here is still recorded. */
    @Override
    protected void cleanup() {
        super.cleanup();
        if (planning != null) {
            planning.cancel();
            planning = null;
        }
        if (boatLeg != null) {
            boatLeg.stop();   // 中途被取消/让位:收桨,别让船带着按下的前进键漂走
            boatLeg = null;
        }
        if (route != null) {
            record(false, lastFailure() == FailureType.UNKNOWN ? "stopped before the end"
                    : "failed (" + lastFailure().name().toLowerCase(java.util.Locale.ROOT) + ")");
        }
    }
}
