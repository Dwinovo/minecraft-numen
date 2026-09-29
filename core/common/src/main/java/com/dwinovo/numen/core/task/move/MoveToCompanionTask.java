package com.dwinovo.numen.core.task.move;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.permission.ConsentItem;
import com.dwinovo.numen.permission.Listing;
import com.dwinovo.numen.task.TaskState;

import net.minecraft.core.BlockPos;

/**
 * {@code move go <route>} (and its shorthand {@code move goto}) on the companion body: walk a route from wherever she
 * stands. Planning and walking are two things ({@link RoutePlanning} → {@link Trip}):
 * <ol>
 *   <li><b>plan from here</b> — every leg once, only searching;</li>
 *   <li><b>hold it against the promise</b> — the plan she saw on the route is the promise: when this plan would break,
 *       place or ask about any cell the promise does not, she does not set off and says the difference. A route never
 *       planned takes this plan as its promise and walks straight away;</li>
 *   <li><b>ask once</b> — every cell needing the owner's consent is asked before the first step;</li>
 *   <li><b>walk leg by leg</b> — each leg is driven under its spec bound to the promise ({@link Plan#bind}): when the
 *       world changes on the way and the engine re-searches, it only finds ways inside the promise; finding none it
 *       stops, and a fresh plan from where she stands says which cells lie outside it. A leg only partly seen when
 *       planning is walked to the end of the seen part and the walk stops there, saying the rest is still unknown.</li>
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
    /** 在走的这一段只走到计划看清的那一截的尽头。 */
    private boolean edge;
    /** 半路停下的那一段与结局,等从这里规划的结论回来再说。 */
    private int stoppedLeg;
    private Outcome stopped;
    /** 船腿:开工时坐在船上就先驾船,靠岸(或搁浅)后接规划与步行。null = 没有/已交棒。 */
    private BoatNav boatLeg;
    /** 出发那一刻(主世界游戏刻),记进走过的记录。 */
    private long startedAt;
    /** 这一趟已经记进路线了。 */
    private boolean recorded;

    public MoveToCompanionTask(NumenPlayer player, MoveToTaskRecord record) {
        super(player, record);
    }

    private Routes routes() {
        return Routes.of(player.getServer(), player.getOwnerUuid());
    }

    private long now() {
        return player.getServer().overworld().getGameTime();
    }

    @Override
    protected void onStart() {
        startedAt = now();
        route = routes().get(r.route);
        if (route == null) {
            recorded = true;
            fail("there is no route named " + r.route + " any more; route list shows the routes you have",
                    FailureType.TARGET_LOST);
            return;
        }
        if (!route.dimension().equals(player.level().dimension().location())) {
            recorded = true;
            fail("route " + route.name() + " lies in " + route.dimension() + ", and I am in "
                    + player.level().dimension().location() + "; its coordinates mean nothing here",
                    FailureType.TARGET_LOST);
            return;
        }
        extendDeadline();
        // 载具处置:坐在船上而第一个途经点有 x、z,先驾船——船腿走到离它最近的水格,靠岸后接规划与步行(见 tickBoatLeg)。
        // 其余情况(矿车没有舵、马的寻路仍按步行物理算)直接规划;下座驾是步行导航自己的事,记进身体动作。
        Destination.Stop first = route.legs().get(0).to();
        if (player.isPassenger() && player.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat
                && first.x() != null) {
            boatLeg = new BoatNav(player, first.toward(player.blockPosition()));
            phase = Phase.BOAT;
            com.dwinovo.numen.core.Constants.LOG.info("[numen-task] go {} 驾船先行", route.name());
            return;
        }
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
                    yield end(answer.refusal(asks), FailureType.REFUSED);
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

    /** 规划回来了:走不通就说;超出承诺就说差别、不走;否则先问主人,再开走。 */
    private TaskState decide(RoutePlanning.Result result) {
        planning = null;
        Plan fresh = result.plan();
        Plan saw = route.plan();
        if (saw == null) {
            // 还没规划过:这一趟照它走,它就是承诺
            routes().plan(route, fresh);
        }
        int bad = fresh.unreachable();
        if (bad >= 0) {
            FailureType type = bad < result.found().size() && !result.found().get(bad).reached()
                    ? NavText.type(result.found().get(bad).outcome()) : FailureType.NO_PATH;
            return end("blocked on " + legName(bad) + ": got within " + String.format("%.1f", repDistance())
                    + " blocks of " + route.destination().words() + " (now on the ground at y="
                    + player.blockPosition().getY() + "). " + fresh.legs().get(bad).why() + ".", type);
        }
        if (saw != null) {
            Plan.Difference diff = fresh.beyond(saw);
            if (!diff.isEmpty()) {
                return end("the way from here goes beyond the plan of route " + route.name() + " (made from "
                        + Listing.coords(saw.from()) + "), so I did not set off: it would also "
                        + RouteText.beyond(diff) + ". route plan " + route.name() + " plans it from here and shows it; "
                        + "then move go " + route.name() + " keeps to that plan.", FailureType.TERRAIN_BLOCKED);
            }
        }
        promise = saw == null ? fresh : saw;
        planned = result;
        asks = result.consents();
        if (!asks.isEmpty()) {
            phase = Phase.CONSENT;
            return TaskState.RUNNING;
        }
        leg = 0;
        return startLeg();
    }

    /** 开走第 {@link #leg} 段:照规划出的路走,规格绑上承诺;只看清一截的走到那一截的尽头。 */
    private TaskState startLeg() {
        if (leg >= route.legs().size()) {
            return arrived();
        }
        RoutePlanning.Leg target = planned.legs().get(leg);
        Survey.Found found = leg < planned.found().size() ? planned.found().get(leg) : null;
        RouteSpec spec = promise.bind(target.spec());
        if (found != null && found.reached()) {
            nav = Trip.following(player, target.goal(), spec, found.route(), target.toward());
            edge = false;
        } else if (found != null && found.partial() != null) {
            BlockPos end = found.partial().end();
            nav = Trip.following(player, Goals.at(end), spec, found.partial(), end);
            edge = true;
        } else {
            return uncharted();
        }
        phase = Phase.DRIVING;
        com.dwinovo.numen.core.Constants.LOG.info("[numen-task] go {} 第 {} 段{} alter={}", route.name(), leg + 1,
                edge ? "(走到看清的尽头)" : "", spec.alter());
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
                if (edge) {
                    yield uncharted();
                }
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
                    + RouteText.beyond(diff) + ". route plan " + route.name() + " plans it from here and shows it; "
                    + "then move go " + route.name() + " keeps to that plan.", FailureType.TERRAIN_BLOCKED);
        }
        RoutePlanning.Leg target = planned.legs().get(stoppedLeg);
        String why = NavText.failure(stopped, player, Feet.cell(player), target.toward(), target.spec(),
                new NavText.OnRoute(route.name(), stoppedLeg + 1, route.legs().size()));
        return end("blocked on " + legName(stoppedLeg) + ": got within " + String.format("%.1f", repDistance())
                + " blocks of " + route.destination().words() + " (now on the ground at y="
                + player.blockPosition().getY() + "). " + why + ".", NavText.type(stopped));
    }

    /** 走到了计划看清的尽头:后面是什么还不知道,停下。 */
    private TaskState uncharted() {
        Plan.Leg seen = planned.plan().legs().get(leg);
        return end("stopped at " + here(player.blockPosition().getY()) + ", where the plan of " + legName(leg)
                + " ends: past it the way is still unknown (" + seen.why() + "). move go " + route.name()
                + " again plans on from here.", FailureType.UNCHARTED);
    }

    /** 最后一段到了。 */
    private TaskState arrived() {
        record(true, "arrived");
        return TaskState.SUCCESS;
    }

    /** 这一趟以失败收场:记进路线,交给任务的收场。 */
    private TaskState end(String why, FailureType type) {
        record(false, "failed (" + type.name().toLowerCase(java.util.Locale.ROOT) + ")");
        fail(why, type);
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
        if (d.x() == null) {
            return Math.abs(player.getY() - d.y());
        }
        double dx = d.x() + 0.5 - player.getX();
        double dz = d.z() + 0.5 - player.getZ();
        double dy = d.y() == null ? 0 : d.y() - player.getY();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    protected Map<String, Object> resultData() {
        int gy = player.blockPosition().getY();
        Map<String, Object> data = new HashMap<>();
        data.put("final_x", player.getX());
        data.put("final_y", player.getY());
        data.put("final_z", player.getZ());
        data.put("ground_y", gy);
        if (route != null) {
            data.put("route", route.name());
        }
        return data;
    }

    /** Success copy — always names the real position so the model learns the terrain. */
    @Override
    protected String successMessage() {
        int gy = player.blockPosition().getY();
        Destination.Stop d = route.destination();
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
        };
        return reached + ", via route " + route.name() + ".";
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
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
                        ? "progress had stopped — likely blocked; move go " + route.name() + " tries again from here,"
                                + " or add a waypoint (route via " + route.name() + ") or scan_blocks for a way through."
                        : "the journey was still progressing and simply exceeded its check-in budget; move go "
                                + route.name() + " goes on from here.");
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
