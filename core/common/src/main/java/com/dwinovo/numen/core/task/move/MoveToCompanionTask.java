package com.dwinovo.numen.core.task.move;

import java.util.List;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.nav.BoatNav;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.core.nav.Trip;
import com.dwinovo.numen.core.route.Description;
import com.dwinovo.numen.core.route.Plan;
import com.dwinovo.numen.core.route.Planning;
import com.dwinovo.numen.core.route.RouteText;
import com.dwinovo.numen.core.route.Stop;
import com.dwinovo.numen.core.route.Target;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.permission.ConsentItem;
import com.dwinovo.numen.permission.Listing;
import com.dwinovo.numen.task.Preparation;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.task.TaskState;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

/**
 * {@code numen.move.go(plan)} (and the library function {@code numen.move.to}, which plans first) on the companion body: walk a plan
 * from wherever she stands. Planning and walking are two things ({@link Planning} → {@link Trip} / {@link BoatNav}):
 * <ol>
 *   <li><b>hold it against the promise</b> — the plan she saw is the promise. Standing where it was made, she sets off
 *       on it. Standing elsewhere (the program moved her, or the body was still busy), she plans again from here before
 *       the walk is accepted ({@link #preparation}) and holds that against the promise: a way that would break, place or
 *       ask about any cell the promise does not is refused on the spot with the difference;</li>
 *   <li><b>walk leg by leg</b> — each leg is driven under its spec bound to the promise ({@link Plan#bind}): when the
 *       world changes on the way and the engine re-searches, it only finds ways inside the promise; finding none it
 *       stops, and a fresh plan from where she stands says which cells lie outside it. A leg seen only in part (or not
 *       planned because the one before it was seen only in part) is driven toward its own goal all the same; a through
 *       stop is passed without stopping; a boat leg steers the boat along its chart;</li>
 *   <li><b>ask at the crossing</b> — a cell needing the owner's consent is asked about when her hands get to it
 *       ({@link Trip}): yes, and the walk goes on; no, and it stops there, refused, with a plan around it as the next
 *       step.</li>
 * </ol>
 * Arrival is the goal's own membership, decided by the pathing module. Results always echo the ACTUAL position reached
 * (and the real ground height) so the model learns the terrain.
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
        /** 从脚下重新规划(受理之前没准备过,而她不在计划的起点上)。 */
        PLANNING,
        /** 一段一段地走(或驾船)。 */
        DRIVING,
        /** 半路停下了,从这里规划剩下几段,看是不是承诺外的格挡着。 */
        EXPLAINING
    }

    /** Ceiling for lease renewals on the work clock (start + {@link #CHECK_IN_CAP_TICKS}); 0 = unset. */
    private long leaseCapWork;

    private Phase phase;
    /** 承诺:她看过的那份计划。 */
    private Plan promise;
    private Planning planning;
    /** 这一趟照着走的那一份:她站在计划起点上就是承诺本身,否则是从脚下重新规划、没超出承诺的那一份。 */
    private Plan planned;
    /** 在走第几段(从 0 数)。 */
    private int leg;
    /** 这一段驾船时的船腿;走路的一段为 null。 */
    private BoatNav boat;
    /** 半路停下的那一段与那句话,等从这里规划的结论回来再说。 */
    private int stoppedLeg;
    private String stoppedWhy;
    private FailureType stoppedType;

    public MoveToCompanionTask(NumenPlayer player, MoveToTaskRecord record) {
        super(player, record);
    }

    /**
     * 受理之前:她站在计划的起点上就照它走;站在别处,从脚下照同一份描述重新规划,结论与开走前的判断同一处({@link #hold}):
     * 走不通、超出承诺就是这次调用的错误结果;走得通就受理。
     */
    @Override
    protected Preparation preparation() {
        promise = r.plan;
        if (atStart()) {
            planned = promise;
            return Preparation.READY;
        }
        Planning again = Planning.of(player, promise.id(), promise.description());
        com.dwinovo.numen.core.Constants.LOG.info("[numen-task] go {} 不在起点,受理前从脚下重新规划", promise.id());
        return new Preparation() {
            @Override
            public Preparation.Readiness poll() {
                Plan fresh = again.poll();
                if (fresh == null) {
                    return null;
                }
                Blocked blocked = hold(fresh);
                return blocked != null ? Preparation.Readiness.refused(TaskResult.fail(blocked.type().kind(),
                        blocked.why(), blocked.hint(), value())) : Preparation.Readiness.READY;
            }

            @Override
            public void cancel() {
                again.cancel();
            }
        };
    }

    /** 她此刻站在计划的起点上。 */
    private boolean atStart() {
        return Feet.cell(player).equals(r.plan.from());
    }

    @Override
    protected void onStart() {
        promise = r.plan;
        extendDeadline();
        if (planned != null) {
            setOff();
            return;
        }
        if (atStart()) {
            planned = promise;
            setOff();
            return;
        }
        // 没准备过,而她不在起点上:从这里重新规划,拿那份计划当承诺比
        planning = Planning.of(player, promise.id(), promise.description());
        phase = Phase.PLANNING;
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
            case PLANNING -> {
                awaitSearch();
                Plan fresh = planning.poll();
                if (fresh == null) {
                    yield TaskState.RUNNING;
                }
                planning = null;
                Blocked blocked = hold(fresh);
                yield blocked != null ? end(blocked.why(), blocked.type(), blocked.hint()) : setOff();
            }
            case DRIVING -> drive();
            case EXPLAINING -> {
                awaitSearch();
                Plan fresh = planning.poll();
                yield fresh == null ? TaskState.RUNNING : explained(fresh);
            }
        };
    }

    /** 不走的那句话、归到哪一种失败、能照抄的下一步(没有为 null)。 */
    private record Blocked(String why, FailureType type, String hint) {}

    /**
     * 从脚下规划出来的这一份拿来比:有走不通的段,或超出承诺,就是不走的原因;否则记下这一份,返回 null。受理之前的准备与开走前的
     * 规划都经这里,说法只此一处。
     */
    private Blocked hold(Plan fresh) {
        int bad = fresh.unreachable();
        if (bad >= 0) {
            return new Blocked("blocked on " + legName(bad) + ": " + fresh.legs().get(bad).why() + ".",
                    FailureType.NO_PATH, null);
        }
        Plan.Difference diff = fresh.beyond(promise);
        if (!diff.isEmpty()) {
            return new Blocked("the way from here goes beyond the plan (made from " + Listing.coords(promise.from())
                    + "), so I did not set off: it would also " + RouteText.beyond(diff) + ". numen.route.plan plans it "
                    + "from here and shows it; then numen.move.go keeps to that plan.", FailureType.TERRAIN_BLOCKED,
                    "numen.move.go(numen.route.plan(plan.spec))");
        }
        planned = fresh;
        return null;
    }

    /** 照 {@link #planned} 从第一段开走。 */
    private TaskState setOff() {
        leg = 0;
        return startLeg();
    }

    /**
     * 开走第 {@link #leg} 段。驾船的一段照航线开;走路的一段照这一段自己的目标走,规格绑上承诺:整段看清了就照规划出的路走,
     * 只看清一截就拿那一截当开头、后面由执行层边走边算,没规划(前一段没看清)就从脚下算起。路过的一站走进去就算到,不停下。
     */
    private TaskState startLeg() {
        if (leg >= planned.legs().size()) {
            return TaskState.SUCCESS;
        }
        Plan.Leg target = planned.legs().get(leg);
        phase = Phase.DRIVING;
        if (target.chart() != null || planned.description().mode() == com.dwinovo.numen.core.route.Description.Mode.BOAT) {
            BoatNav.Chart chart = target.chart() != null ? target.chart() : chart(target);
            boat = new BoatNav(player, chart);
            com.dwinovo.numen.core.Constants.LOG.info("[numen-task] go {} 第 {} 段驾船 {} 个航点", planned.id(),
                    leg + 1, chart.path().size());
            return TaskState.RUNNING;
        }
        RouteSpec spec = promise.bind(target.spec());
        Goal goal = target.to().goal();
        nav = target.route() == null ? Trip.to(player, goal, spec, target.to().toward())
                : Trip.following(player, goal, spec, target.route(), target.to().toward());
        nav.spending(planned.description().materials());
        if (target.stop().through()) {
            nav.passing();
        }
        com.dwinovo.numen.core.Constants.LOG.info("[numen-task] go {} 第 {} 段{}{} dig={} place={} consent={}",
                planned.id(), leg + 1, target.reach() == Plan.Reach.REACHED ? "" : "(计划只看清一截或没规划,边走边算)",
                target.stop().through() ? "(路过)" : "", spec.dig(), spec.place(), spec.consent());
        return TaskState.RUNNING;
    }

    /** 没规划的一段水路:从船此刻所在的地方现画航线。 */
    private BoatNav.Chart chart(Plan.Leg target) {
        BlockPos from = player.getVehicle() instanceof Boat ship ? ship.blockPosition() : player.blockPosition();
        return BoatNav.chart(player, from, target.to().toward());
    }

    private TaskState drive() {
        if (boat != null) {
            return sail();
        }
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
                stoppedLeg = leg;
                stoppedWhy = nav.failReason();
                stoppedType = nav.failType();
                stopNav();
                planning = Planning.rest(player, planned, leg);
                phase = Phase.EXPLAINING;
                yield TaskState.RUNNING;
            }
        };
    }

    /** 驾船的一段:到了接下一段;搁浅、下了船照实收场。船留在原地,那是她的船,不是垃圾。 */
    private TaskState sail() {
        if (boat.progressing()) {
            renewLease();
        }
        BoatNav.Status status = boat.tick();
        if (status == BoatNav.Status.RUNNING) {
            return TaskState.RUNNING;
        }
        String why = boat.failReason();
        boat.stop();
        boat = null;
        if (status == BoatNav.Status.FAILED) {
            return end("stopped sailing on " + legName(leg) + " at " + here(player.blockPosition().getY()) + ": "
                    + why, FailureType.BOXED_IN, null);
        }
        leg++;
        return startLeg();
    }

    /** 半路停下之后从这里规划的结论:要承诺外的格就说是哪几格;不然照寻路的结局说。 */
    private TaskState explained(Plan fresh) {
        planning = null;
        Plan.Difference diff = fresh.beyond(promise);
        if (!diff.isEmpty()) {
            return end("stopped on " + legName(stoppedLeg) + " at " + here(player.blockPosition().getY())
                    + ": the way on from here needs cells outside the plan I keep to — it would "
                    + RouteText.beyond(diff) + ". numen.route.plan plans it from here and shows it; then numen.move.go keeps to "
                    + "that plan.", FailureType.TERRAIN_BLOCKED, "numen.move.go(numen.route.plan(plan.spec))");
        }
        return end("blocked on " + legName(stoppedLeg) + ": got within " + String.format("%.1f", repDistance())
                + " blocks of " + destination().words() + " (now on the ground at y="
                + player.blockPosition().getY() + "). " + stoppedWhy + ".", stoppedType, null);
    }

    /** 这一趟以失败收场。 */
    private TaskState end(String why, FailureType type, String hint) {
        fail(why, type, hint);
        return TaskState.FAILED;
    }

    /**
     * 主人不答应路上那一格:绕开它再规划。下一步写成原来那份描述加上避开那几格——{@code avoid} 收格子,原来写的避开照旧。
     */
    @Override
    protected String refusedHint(List<ConsentItem> refused) {
        com.dwinovo.numen.core.route.Description d = promise.description();
        Description.Places avoid = d.avoid().plus(refused.stream().map(ConsentItem::pos).toList());
        return "numen.move.go(" + com.dwinovo.numen.sdk.Call.of("numen.route.plan", d.written().avoiding(avoid)) + ")";
    }

    /** "第 2 段(共 3 段)"的英文:只有一段就说这一趟。 */
    private String legName(int index) {
        int legs = promise.legs().size();
        return legs == 1 ? "the walk" : "leg " + (index + 1) + " of " + legs;
    }

    /** 续约:期限保持在租约窗口里,但这一程干活的刻数不超过 {@link #CHECK_IN_CAP_TICKS}。 */
    private void renewLease() {
        if (leaseCapWork <= 0) {
            return;
        }
        long capLeft = leaseCapWork - workTicks();
        r.extendDeadlineTo(player.level().getGameTime() + Math.min(PROGRESS_LEASE_TICKS, capLeft));
    }

    /** 终点那一站。 */
    private Stop destination() {
        List<Stop> stops = r.plan.description().stops();
        return stops.get(stops.size() - 1);
    }

    /** 终点那一段编好的去处给人说"朝哪儿"的那一格;那一站没编成(计划走不通)为 null。 */
    private BlockPos toward() {
        Plan plan = planned != null ? planned : r.plan;
        Plan.Leg last = plan.legs().get(plan.legs().size() - 1);
        return last.to() == null ? null : last.to().toward();
    }

    /** Representative remaining distance (blocks) to the destination, for the deadline estimate and the replies. */
    private double repDistance() {
        BlockPos toward = toward();
        if (toward == null) {
            return 0;
        }
        if (destination().to() instanceof Target.Height height) {
            return Math.abs(player.getY() - height.y());
        }
        if (destination().to() instanceof Target.Column) {
            double dx = toward.getX() + 0.5 - player.getX();
            double dz = toward.getZ() + 0.5 - player.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }
        return Math.sqrt(player.distanceToSqr(Vec3.atBottomCenterOf(toward)));
    }

    /** {@code numen.move.go} 交回的值。 */
    @com.dwinovo.numen.sdk.Doc("Where a walk ended.")
    public record Walked(@com.dwinovo.numen.sdk.Doc("Where you stand now (decimals).") Vec3 pos,
                        @com.dwinovo.numen.sdk.Doc("Blocks from the destination; 0 or so when there.")
                        double distanceLeft) {}

    /** 走完(或停下)时她在哪、离终点还有多远。 */
    @Override
    protected Walked value() {
        return new Walked(player.position(), Math.round(repDistance() * 10.0) / 10.0);
    }

    /** Success copy — always names the real position so the model learns the terrain. */
    @Override
    protected String successMessage() {
        int gy = player.blockPosition().getY();
        Stop d = destination();
        String where = d.to().words();
        String reached = switch (d.arrive()) {
            case AT -> switch (d.to()) {
                case Target.Height h -> "reached elevation y=" + gy;
                case Target.Column c -> "arrived at location x=" + c.x() + " z=" + c.z()
                        + ", standing on the ground at y=" + gy;
                default -> "reached " + here(gy);
            };
            case NEAR -> "arrived within " + d.range() + " blocks of " + where + ", standing at " + here(gy);
            case AWAY -> "got at least " + d.range() + " blocks away from " + where + ", standing at " + here(gy);
            case USE -> "standing at " + here(gy) + ", with " + used() + " in sight and in reach — use it from here";
            case DIG -> d.to() instanceof Target.Cell c
                    ? "standing at " + here(gy) + ", with the " + NavText.name(player.level().getBlockState(c.pos()))
                            + " at " + Listing.coords(c.pos()) + " within reach — `numen.work.dig(" + com.dwinovo.numen.sdk.LuaCodecs.literal(c.pos())
                            + ")` digs it from here"
                    : "standing at " + here(gy) + ", within reach of " + where + " — `numen.work.dig` digs what is in reach "
                            + "from here";
            case PLACE -> "standing at " + here(gy) + ", with " + where + " within reach to build into";
        };
        return reached + (planned.description().mode() == com.dwinovo.numen.core.route.Description.Mode.BOAT
                ? " in the boat" : "") + ".";
    }

    /**
     * 到了 {@code use}:她在看的是哪一个方块——终点那一段的目标说停在这里要看得见的是谁({@link Goal#sight},多个取其一时是站在这里
     * 满足的那个成员的)。
     */
    private String used() {
        Plan.Leg last = planned.legs().get(planned.legs().size() - 1);
        Feet feet = Feet.of(player);
        Goal.Sighting sight = feet == null || last.to() == null ? null : last.to().goal().sight(feet.node().getX(),
                feet.node().getY(), feet.node().getZ(), feet.stance());
        return sight == null ? "one of its blocks" : "the " + NavText.name(player.level().getBlockState(sight.target()))
                + " at " + Listing.coords(sight.target());
    }

    @Override
    protected String timeoutMessage() {
        int gy = player.blockPosition().getY();
        // Two different stories for the model: a stall (progress dried up — something is wrong, reconsider) vs a
        // check-in (journey healthy but longer than the cap — resuming is the right move).
        boolean stalled = nav == null || !nav.progressing();
        return "timed out " + String.format("%.1f", repDistance()) + " blocks from target (now at " + here(gy) + "); "
                + (stalled
                        ? "progress had stopped — likely blocked; plan again from here (numen.route.plan) and look at the "
                                + "way, or add a stop in another direction."
                        : "the journey was still progressing and simply exceeded its check-in budget; plan again from "
                                + "here and go on.");
    }

    @Override
    protected String cancelledMessage() {
        return "cancelled before reaching target";
    }

    private String here(int gy) {
        return String.format("%.0f,%d,%.0f", player.getX(), gy, player.getZ());
    }

    /** Release the nav (base), drop the planning in flight, ship the oars. */
    @Override
    protected void cleanup() {
        super.cleanup();
        if (planning != null) {
            planning.cancel();
            planning = null;
        }
        if (boat != null) {
            boat.stop();   // 中途被取消/让位:收桨,别让船带着按下的前进键漂走
            boat = null;
        }
    }
}
