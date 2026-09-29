package com.dwinovo.numen.core.task.move;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.nav.BoatNav;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.nav.RouteBook;
import com.dwinovo.numen.core.nav.Trip;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.task.TaskState;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code goto} on the companion player body — a walk to a {@link Destination} (coordinates plus how arrival counts, the
 * goal built when the call was accepted), or along a route from the body's {@link RouteBook}: goal and spec are the
 * route's own, the route itself is walked first and re-searched under the same spec when it no longer holds.
 * Arrival is the goal's own membership, decided by the pathing module; there is no "close enough" here — the caller
 * asks for {@code arrive:near} instead. Results always echo the ACTUAL position reached (and the real ground height) so
 * the model learns the terrain.
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

    /** Ceiling for lease renewals on the work clock (start + {@link #CHECK_IN_CAP_TICKS}); 0 = unset. */
    private long leaseCapWork;

    /** ROUTE 形态:从路线簿取走的那条路(onStart 取,取不到即失败)。 */
    private RouteBook.Entry route;

    /** 这次走路的规格:按坐标走是记录里解析好的那份,走路线是路线自己的。 */
    private RouteSpec spec;

    /** 船腿:开工时坐在船上就先驾船,靠岸(或搁浅)后接步行。null = 没有/已交棒。 */
    private BoatNav boatLeg;

    public MoveToCompanionTask(NumenPlayer player, MoveToTaskRecord record) {
        super(player, record);
        this.spec = record.spec;
    }

    @Override
    protected void onStart() {
        if (r.route != null) {
            // 路线簿里的一条:取走即划掉;目标与规格都是它的
            route = RouteBook.of(player).take(r.route);
            if (route == null) {
                fail("unknown route id '" + r.route + "' — ids come from a move_goto refusal or a move route"
                        + " reply, and a route is dropped once walked or when newer plans push it out."
                        + " Run move route again, or move_goto the destination coordinates.",
                        FailureType.NO_PATH);
                return;
            }
            spec = route.spec();
            extendDeadline();
            nav = Trip.along(player, route);
            com.dwinovo.numen.core.Constants.LOG.info(
                    "[numen-task] goto start kind=ROUTE id={} toward={} alter={}",
                    route.id(), route.toward().toShortString(), spec.alter());
            return;
        }
        // 载具处置:坐在船上而去处有 x、z,先驾船——船腿走到离目标最近的水格,靠岸后接步行(见 tickBoatLeg)。其余情况
        // (矿车没有舵、马的寻路仍按步行物理算)直接走步行段;下座驾是步行导航自己的事,记进身体动作。
        Destination d = r.destination;
        if (player.isPassenger()
                && player.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat
                && d.x() != null && !standingInGoal()) {
            boatLeg = new BoatNav(player, d.toward(player.blockPosition()));
            extendDeadline();
            com.dwinovo.numen.core.Constants.LOG.info(
                    "[numen-task] goto start {} 驾船先行", d.goal());
            return;
        }
        startWalkingNav();
    }

    /** 初始期限按直线距离给(地形难度这时不知道,上路之后由进度租约接手)。 */
    private void extendDeadline() {
        long extra = Math.min(MAX_EXTRA_TICKS, 600 + (long) (repDistance() * TICKS_PER_BLOCK));
        r.extendDeadlineTo(player.level().getGameTime() + extra);
        leaseCapWork = workTicks() + CHECK_IN_CAP_TICKS;
    }

    /** 步行段的启动:开工时走它,船腿靠岸后接力也走它——两个入口一份逻辑。 */
    private void startWalkingNav() {
        extendDeadline();
        nav = Trip.to(player, goal(), spec, toward()).probing();
        com.dwinovo.numen.core.Constants.LOG.info("[numen-task] goto start {} alter={}", goal(), spec.alter());
    }

    /** 去处的目标,受理时就在 {@link Destination#of} 编好,规划与到达同一份。 */
    private Goal goal() {
        return r.destination.goal();
    }

    /** 给人说"朝哪儿"的那一格。 */
    private BlockPos toward() {
        return r.destination.toward(player.blockPosition());
    }

    /** 她此刻已经待在目标里了。 */
    private boolean standingInGoal() {
        Feet feet = Feet.of(player);
        return feet != null && feet.in(goal());
    }

    @Override
    protected TaskState onTick() {
        if (boatLeg != null) {
            return tickBoatLeg();
        }
        if (nav == null) {
            fail(blockedMessage("no path"), FailureType.NO_PATH);
            return TaskState.FAILED;
        }
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
                fail(blockedMessage(nav.failReason()), nav.failType());
                yield TaskState.FAILED;
            }
        };
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
     * 船腿的一刻:驾船朝目标推进,终态(靠岸或搁浅)都走同一条接力——到不了目标的水路不算失败,只是"这条腿到此为止",
     * 剩下的路归步行段(步行导航起步自会下船,并记进身体动作)。目标就在水上时她留在船里,不往水里跳。船留在原地,那是她的船,
     * 不是垃圾。
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
        if (standingInGoal()) {
            com.dwinovo.numen.core.Constants.LOG.info(
                    "[numen-task] 船腿结束({}),目标已在船下 feet={}", how, player.blockPosition().toShortString());
            return TaskState.SUCCESS;
        }
        com.dwinovo.numen.core.Constants.LOG.info(
                "[numen-task] 船腿结束({}),接步行 feet={}", how, player.blockPosition().toShortString());
        startWalkingNav();
        return TaskState.RUNNING;
    }

    /** Representative remaining distance (blocks) for the deadline estimate. */
    private double repDistance() {
        if (r.route != null) {
            BlockPos c = route.toward();
            return Math.sqrt(player.distanceToSqr(c.getX() + 0.5, c.getY(), c.getZ() + 0.5));
        }
        Destination d = r.destination;
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
        return data;
    }

    /** Success copy — always names the real position so the model learns the terrain. */
    @Override
    protected String successMessage() {
        int gy = player.blockPosition().getY();
        if (r.route != null) {
            return "arrived via route " + route.id() + ", standing at " + here(gy) + ".";
        }
        Destination d = r.destination;
        BlockPos cell = d.cell();
        return switch (d.arrive()) {
            case AT -> cell != null ? "reached the exact cell " + coords(cell) + "."
                    : d.x() != null ? "arrived at location x=" + d.x() + " z=" + d.z() + ", standing on the ground at y="
                            + gy + "."
                    : "reached elevation y=" + gy + ".";
            case ON -> "standing on the " + block(cell) + " at " + coords(cell) + ".";
            case USE -> "standing at " + here(gy) + ", with the " + block(cell) + " at " + coords(cell)
                    + " in sight and in reach — use it from here.";
            case NEAR -> "arrived within " + d.near() + " blocks of " + (cell != null ? coords(cell)
                    : "location x=" + d.x() + " z=" + d.z()) + ", standing at " + here(gy) + ".";
        };
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private String block(BlockPos pos) {
        return com.dwinovo.numen.core.nav.NavText.name(player.level().getBlockState(pos));
    }

    @Override
    protected String timeoutMessage() {
        int gy = player.blockPosition().getY();
        double remaining = repDistance();
        // Two different stories for the model: a stall (progress dried up — something is wrong, reconsider) vs a
        // check-in (journey healthy but longer than the cap — resuming is the right move).
        boolean stalled = nav == null || !nav.progressing();
        return "timed out " + String.format("%.1f", remaining) + " blocks from target (now at "
                + here(gy) + "); "
                + (stalled
                        ? "progress had stopped — likely blocked; call move_goto again to retry, or"
                                + " try a nearer waypoint / scan_blocks for a way through."
                        : "the journey was still progressing and simply exceeded its check-in budget;"
                                + (r.route != null
                                        ? " move_goto the destination coordinates to resume (a route id is spent once walked)."
                                        : " call move_goto again with the same target to resume."));
    }

    @Override
    protected String cancelledMessage() {
        return "cancelled before reaching target";
    }

    private String here(int gy) {
        return String.format("%.0f,%d,%.0f", player.getX(), gy, player.getZ());
    }

    /** Release the nav (base) and ship the oars. */
    @Override
    protected void cleanup() {
        super.cleanup();
        if (boatLeg != null) {
            boatLeg.stop();   // 中途被取消/让位:收桨,别让船带着按下的前进键漂走
            boatLeg = null;
        }
    }

    /** 没走到时的回执:离哪儿还有多远、此刻站在哪儿,接着是寻路给的原因。 */
    private String blockedMessage(String failReason) {
        int gy = player.blockPosition().getY();
        String where = r.route != null
                ? "the destination of route " + route.id() + " (" + route.toward().toShortString() + ")"
                : r.destination.x() == null ? "elevation y=" + r.destination.y()
                : "location x=" + r.destination.x() + " z=" + r.destination.z();
        String advice = nav != null && nav.failType() == FailureType.NO_PATH
                ? " Try a nearer waypoint or scan_blocks for a way through." : "";
        return "blocked: got within " + String.format("%.1f", repDistance()) + " blocks of " + where
                + " (now on the ground at y=" + gy + "). " + failReason + "." + advice;
    }

}
