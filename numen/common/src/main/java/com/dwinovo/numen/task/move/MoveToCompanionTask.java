package com.dwinovo.numen.task.move;

import java.util.List;

import com.dwinovo.numen.FailureType;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.task.Preparation;
import com.dwinovo.numen.api.task.TaskResult;
import com.dwinovo.numen.api.task.TaskState;
import com.dwinovo.numen.nav.BoatNav;
import com.dwinovo.numen.nav.Feet;
import com.dwinovo.numen.nav.NavText;
import com.dwinovo.numen.nav.Trip;
import com.dwinovo.numen.nav.Walked;
import com.dwinovo.numen.nav.Walking;
import com.dwinovo.numen.task.base.AbstractCompanionTask;
import com.dwinovo.numen.task.base.Precondition;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

/**
 * {@code numen.move.to} 与 {@code numen.move.away} 在同伴身体上:从她站的地方走到一个到达目标({@link Arrival})。
 *
 * <p><b>一趟就是一次导航</b>——目标编成寻路的目标({@link Destination}),规格是走路的出厂规格({@link Walking#spec}:可挖可搭,只看
 * 价钱),交给 {@link Trip}。一截一截规划、走、从当时位置按同一份规格接着规划,都在寻路里;这里不留"计划",也不比对路上动了哪些格:
 * 动不动一格由权限层在动手那一刻决定,主人拒了的格这一趟里当墙、重新规划绕开({@link Trip#consentRefused}),绕不开才被拒收场。
 *
 * <p><b>载具只是输入</b>——坐在船上({@link MoveToTaskRecord#sailing})就把船开到离目标最近的水面,目标在岸上就停在岸边、仍坐着、
 * 照实交回;坐着驾驭不了的东西(别的载具、船上要去的不是一处位置)受理就拒绝,指 {@code numen.use.dismount()}。走路的一趟从不自己
 * 下车。
 *
 * <p>到达只看目标自己的判定,回执如实交代这一路({@link Walked}):停在哪、离目标还差多远、挖了放了多少、问过什么、被拒了什么。
 */
public final class MoveToCompanionTask extends AbstractCompanionTask<MoveToTaskRecord, Walked> {

    private static final long TICKS_PER_BLOCK = 20;
    private static final long MAX_EXTRA_TICKS = 5 * 60 * 20;
    /** Progress lease: while the journey is making progress, the deadline is kept this far ahead — a healthy
     *  multi-minute dig route never times out mid-stride, and a stalled one still returns the body within one lease. */
    private static final long PROGRESS_LEASE_TICKS = 30 * 20;
    /** Hard check-in cap: even a healthy marathon yields (with a resumable result) after this many ticks of walking
     *  ({@link #workTicks()} — ticks spent waiting on the planner don't count), bounding how long the LLM goes without
     *  control. Renewals never extend the journey past this. */
    private static final long CHECK_IN_CAP_TICKS = 5 * 60 * 20;

    /** Ceiling for lease renewals on the work clock (start + {@link #CHECK_IN_CAP_TICKS}); 0 = unset. */
    private long leaseCapWork;

    /** 编好的去处:受理之前编过就用那一份,动手的活派下来的子活开工时现编。 */
    private Destination destination;
    /** 这一趟驾船:航线的船腿;走路的一趟为 null。 */
    private BoatNav boat;
    /** 驾船画出的航线到不了目标所在的水域(目标在岸上):开到岸边就算这一趟走完。 */
    private boolean shore;

    public MoveToCompanionTask(NumenPlayer player, MoveToTaskRecord record) {
        super(player, record);
    }

    /**
     * 坐着载具:船且这一趟许驾船且去的是一处位置,就驾船;别的一律受理就拒绝,说清楚下一步是下来。走路的一趟从不自己下车,下车是
     * 她的决定。
     */
    @Override
    protected List<Precondition> preconditions() {
        return List.of(() -> {
            Entity vehicle = player.getVehicle();
            if (vehicle == null || vehicle instanceof Boat && r.sailing && sails()) {
                return null;
            }
            return Precondition.sitting(player, vehicle instanceof Boat && r.sailing
                    ? "which can only be sailed to a place (a Pos or a column {x = …, z = …}), not " + r.arrival.words()
                    : null);
        });
    }

    /** 船能开到:去的是一处位置(一格、一列),站进去或到它附近。 */
    private boolean sails() {
        return (r.arrival.how() == Arrival.How.AT || r.arrival.how() == Arrival.How.NEAR
                || r.arrival.how() == Arrival.How.BESIDE)
                && (r.arrival.to() instanceof Where.Cell || r.arrival.to() instanceof Where.Column);
    }

    /** 受理之前:去处编得成才受理——站不进去、没东西可用、点名的实体不在了,当场回,不占槽。 */
    @Override
    protected Preparation preparation() {
        try {
            destination = Destination.of(player, r.arrival, Feet.cell(player));
        } catch (Destination.Gone gone) {
            return Preparation.refused(TaskResult.fail(ErrorKind.NOT_FOUND, gone.getMessage(), null, value()));
        } catch (IllegalArgumentException refused) {
            return Preparation.refused(TaskResult.fail(ErrorKind.NO_PATH, refused.getMessage(), null, value()));
        }
        return Preparation.READY;
    }

    @Override
    protected void onStart() {
        if (destination == null) {
            try {
                destination = Destination.of(player, r.arrival, Feet.cell(player));
            } catch (IllegalArgumentException refused) {
                fail(refused.getMessage(), FailureType.NO_PATH);
                return;
            }
        }
        extendDeadline();
        if (player.getVehicle() instanceof Boat) {
            sail();
            return;
        }
        nav = Trip.to(player, destination.goal(), Walking.spec(player), destination.toward());
        com.dwinovo.numen.Constants.LOG.info("[numen-task] {} 去 {}", r.getToolName(), r.arrival.words());
    }

    /** 画出航线开船;画不出就是这一趟走不通。 */
    private void sail() {
        BoatNav.Chart chart = BoatNav.chart(player, player.getVehicle().blockPosition(), destination.toward());
        if (chart.why() != null) {
            fail("can't sail from here: " + chart.why(), FailureType.NO_PATH);
            return;
        }
        shore = !chart.reached();
        boat = new BoatNav(player, chart);
        com.dwinovo.numen.Constants.LOG.info("[numen-task] {} 驾船 {} 个航点去 {}", r.getToolName(), chart.path().size(),
                r.arrival.words());
    }

    /** 初始期限按直线距离给(地形难度这时不知道,上路之后由进度租约接手)。 */
    private void extendDeadline() {
        long extra = Math.min(MAX_EXTRA_TICKS, 600 + (long) (distance() * TICKS_PER_BLOCK));
        r.extendDeadlineTo(player.level().getGameTime() + extra);
        leaseCapWork = workTicks() + CHECK_IN_CAP_TICKS;
    }

    @Override
    protected TaskState onTick() {
        return boat != null ? sailing() : walk();
    }

    /** 走路的一刻。 */
    private TaskState walk() {
        // Progress lease: while the walk is making progress, keep the deadline PROGRESS_LEASE ahead — never past the
        // check-in cap. Progress, NOT goal distance, is the liveness signal: healthy routes routinely move away from
        // the goal (skirting a lake, spiraling down), and the flat budget above can't price terrain.
        if (nav.progressing()) {
            renewLease();
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> TaskState.SUCCESS;
            case FAILED -> {
                String why = nav.failReason();
                FailureType type = nav.failType();
                stopNav();
                fail("blocked: got within " + String.format("%.1f", distance()) + " blocks of " + r.arrival.to().words()
                        + " (now on the ground at y=" + player.blockPosition().getY() + "). " + why + ".", type);
                yield TaskState.FAILED;
            }
        };
    }

    /** 驾船的一刻:到了就是这一趟走完;搁浅、下了船照实收场。船留在原地,那是她的船,不是垃圾。 */
    private TaskState sailing() {
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
            fail("stopped sailing at " + here(player.blockPosition().getY()) + ": " + why, FailureType.BOXED_IN);
            return TaskState.FAILED;
        }
        return TaskState.SUCCESS;
    }

    /** 续约:期限保持在租约窗口里,但这一程干活的刻数不超过 {@link #CHECK_IN_CAP_TICKS}。 */
    private void renewLease() {
        if (leaseCapWork <= 0) {
            return;
        }
        long capLeft = leaseCapWork - workTicks();
        r.extendDeadlineTo(player.level().getGameTime() + Math.min(PROGRESS_LEASE_TICKS, capLeft));
    }

    /** 离目标的直线距离(格):定期限、说回执用。高度只看垂直,一列只看水平。 */
    private double distance() {
        BlockPos toward = destination == null ? null : destination.toward();
        if (toward == null) {
            return 0;
        }
        if (r.arrival.to() instanceof Where.Height height) {
            return Math.abs(player.getY() - height.y());
        }
        if (r.arrival.to() instanceof Where.Column) {
            double dx = toward.getX() + 0.5 - player.getX();
            double dz = toward.getZ() + 0.5 - player.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }
        return Math.sqrt(player.distanceToSqr(Vec3.atBottomCenterOf(toward)));
    }

    /** 还差多远:离目标的距离减去说好的距离({@code near} 里面、{@code away} 外面);到了是 0。 */
    private double left() {
        double distance = distance();
        return switch (r.arrival.how()) {
            case NEAR, BESIDE -> Math.max(0, distance - r.arrival.range());
            case AWAY -> Math.max(0, r.arrival.range() - distance);
            default -> distance;
        };
    }

    /** 走完(或停下)时她在哪、离目标还差多远、这一路做了什么。 */
    @Override
    protected Walked value() {
        return walked(player.position(), Math.round(left() * 10.0) / 10.0);
    }

    /** Success copy — always names the real position so the model learns the terrain. */
    @Override
    protected String successMessage() {
        int gy = player.blockPosition().getY();
        Arrival a = r.arrival;
        String where = a.to().words();
        if (shore) {
            return "sailed as far as the water goes, to " + here(gy) + " — " + String.format("%.1f", distance())
                    + " blocks from " + where + ", still sitting in the boat; `numen.use.dismount()` steps off to go on "
                    + "on foot.";
        }
        String reached = switch (a.how()) {
            case AT -> switch (a.to()) {
                case Where.Height h -> "reached elevation y=" + gy;
                case Where.Column c -> "arrived at location x=" + c.x() + " z=" + c.z() + ", standing on the ground at y="
                        + gy;
                default -> "reached " + here(gy);
            };
            case NEAR, BESIDE -> "arrived within " + a.range() + " blocks of " + where + ", standing at " + here(gy);
            case AWAY -> "got at least " + a.range() + " blocks away from " + where + ", standing at " + here(gy);
            case USE -> "standing at " + here(gy) + ", with " + where + " in sight and in reach";
            case DIG -> "standing at " + here(gy) + ", within reach of " + where;
            case PLACE -> "standing at " + here(gy) + ", with " + where + " within reach to build into";
        };
        return reached + (player.getVehicle() instanceof Boat ? " in the boat" : "") + ".";
    }

    @Override
    protected String timeoutMessage() {
        // 到期是一趟最长的时间到了(一直在推进),还是租约到期前没有进展:看干活的刻数有没有用满,不看到期那一刻的瞬时信号
        boolean checkIn = workTicks() >= leaseCapWork;
        return NavText.timedOut((int) workTicks(), distance(), here(player.blockPosition().getY()),
                nav == null ? null : nav.nearestRefusal(), checkIn);
    }

    @Override
    protected String cancelledMessage() {
        return "cancelled before reaching target";
    }

    private String here(int gy) {
        return String.format("%.0f,%d,%.0f", player.getX(), gy, player.getZ());
    }

    /** Release the nav (base), ship the oars. */
    @Override
    protected void cleanup() {
        super.cleanup();
        if (boat != null) {
            boat.stop();   // 中途被取消/让位:收桨,别让船带着按下的前进键漂走
            boat = null;
        }
    }
}
