package com.dwinovo.numen.core.task.move;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.nav.BoatNav;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.nav.RouteBook;
import com.dwinovo.numen.core.nav.Terrain;
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
 * {@code goto} on the companion player body — a walk whose goal is chosen by which coordinates were supplied
 * ({@link MoveToTaskRecord.Kind}, the goal itself built in {@link MoveToTaskRecord#goal}):
 * <ul>
 *   <li>{@link MoveToTaskRecord.Kind#COLUMN} → that (x,z) column at any height — the default "go there", a wrong or
 *       absent Y can never make it unreachable;</li>
 *   <li>{@link MoveToTaskRecord.Kind#BLOCK} → exactly that cell; whatever occupies it has to be dug out, which only a
 *       spec with {@code alter:natural} allows. A y in mid-air or inside a block is no place to stand: the walk fails and
 *       says so, and the model omits y or gives {@code near};</li>
 *   <li>{@link MoveToTaskRecord.Kind#YLEVEL} → that height;</li>
 *   <li>{@link MoveToTaskRecord.Kind#FIND} → within reach of the nearest block of a kind ({@link NearestBlockFinder});</li>
 *   <li>{@link MoveToTaskRecord.Kind#ROUTE} → a route from the body's {@link RouteBook}: goal and spec are the route's
 *       own, the route itself is walked first and re-searched under the same spec when it no longer holds.</li>
 * </ul>
 * Arrival is the goal's own membership, decided by the pathing module; there is no "close enough" here — the caller
 * asks for {@code near} instead. Results always echo the ACTUAL position reached (and the real ground height) so the
 * model learns the terrain.
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

    private final int bx;
    private final int by;
    private final int bz;
    private final BlockPos blockTarget;   // only meaningful for BLOCK kind

    /** Ceiling for lease renewals on the work clock (start + {@link #CHECK_IN_CAP_TICKS}); 0 = unset. */
    private long leaseCapWork;

    /** FIND(就近方块)子系统:扫描/入册/目标/轮换全在组件里,此处只驱动。 */
    private NearestBlockFinder finder;

    /** ROUTE 形态:从路线簿取走的那条路(onStart 取,取不到即失败)。 */
    private RouteBook.Entry route;

    /** 这次走路的规格:坐标/FIND 形态是记录里解析好的那份,ROUTE 形态是路线自己的。 */
    private RouteSpec spec;

    /** 船腿:开工时坐在船上就先驾船,靠岸(或搁浅)后接步行。null = 没有/已交棒。 */
    private BoatNav boatLeg;

    public MoveToCompanionTask(NumenPlayer player, MoveToTaskRecord record) {
        super(player, record);
        this.bx = record.x != null ? record.x : 0;
        this.by = record.y != null ? record.y : 0;
        this.bz = record.z != null ? record.z : 0;
        this.blockTarget = new BlockPos(bx, by, bz);
        this.spec = record.spec;
    }

    @Override
    protected void onStart() {
        if (r.kind == MoveToTaskRecord.Kind.ROUTE) {
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
        // 载具处置:坐在船上且有明确去处,先驾船——船腿走到离目标最近的水格,靠岸后接步行(见 tickBoatLeg)。其余情况
        // (矿车没有舵、马的寻路仍按步行物理算、FIND 要先扫描)直接走步行段;下座驾是步行导航自己的事,记进身体动作。
        if (player.isPassenger()
                && player.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat
                && (r.kind == MoveToTaskRecord.Kind.BLOCK || r.kind == MoveToTaskRecord.Kind.COLUMN)
                && !standingInGoal()) {
            boatLeg = new BoatNav(player, blockTarget);
            extendDeadline();
            com.dwinovo.numen.core.Constants.LOG.info(
                    "[numen-task] goto start kind={} target={},{},{} 驾船先行", r.kind, bx, by, bz);
            return;
        }
        if (r.kind == MoveToTaskRecord.Kind.FIND) {
            // 就近方块:解析 id → 离线扫描附近候选;导航等首批候选到手再建
            var id = net.minecraft.resources.ResourceLocation.tryParse(r.block);
            var b = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
            if (b == null || b == net.minecraft.world.level.block.Blocks.AIR) {
                fail("unknown block id '" + r.block
                        + "' — use a namespaced block id like minecraft:crafting_table",
                        FailureType.NO_PATH);
                return;
            }
            finder = new NearestBlockFinder(player, b);
            long findExtra = Math.min(MAX_EXTRA_TICKS,
                    600 + (long) NearestBlockFinder.BUDGET_BLOCKS * TICKS_PER_BLOCK);
            r.extendDeadlineTo(player.level().getGameTime() + findExtra);
            leaseCapWork = workTicks() + CHECK_IN_CAP_TICKS;
            finder.kickScan();
            com.dwinovo.numen.core.Constants.LOG.info("[numen-task] goto start kind=FIND block={}", r.block);
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
        com.dwinovo.numen.core.Constants.LOG.info("[numen-task] goto start kind={} target={},{},{} near={} alter={}",
                r.kind, bx, by, bz, r.near, spec.alter());
    }

    /** 坐标形态的目标,只在 {@link MoveToTaskRecord#goal} 一处成形,规划与到达同一份。 */
    private Goal goal() {
        return MoveToTaskRecord.goal(r.kind, bx, by, bz, r.near);
    }

    /** 给人说"朝哪儿"的那一格。 */
    private BlockPos toward() {
        return switch (r.kind) {
            case BLOCK, COLUMN, YLEVEL -> MoveToTaskRecord.toward(r.kind, bx, by, bz, player.blockPosition());
            case FIND, ROUTE -> player.blockPosition();
        };
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
        if (r.kind == MoveToTaskRecord.Kind.FIND && nav == null) {
            TaskState pre = tickFindDiscovery();
            if (pre != null) {
                return pre;
            }
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
                // FIND:打不通就近候选 -> 除名,朝余下候选重开导航
                if (r.kind == MoveToTaskRecord.Kind.FIND && finder.rotateAfterFailure()) {
                    stopNav();
                    nav = Trip.to(player, finder.goal(), spec, finder.nearest()).probing();
                    yield TaskState.RUNNING;
                }
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

    private double horizontalDistSqr(int cellX, int cellZ) {
        double dx = (cellX + 0.5) - player.getX();
        double dz = (cellZ + 0.5) - player.getZ();
        return dx * dx + dz * dz;
    }

    /** Representative remaining distance (blocks) for the deadline estimate. */
    private double repDistance() {
        return switch (r.kind) {
            case BLOCK -> Math.sqrt(player.distanceToSqr(bx + 0.5, by, bz + 0.5));
            case COLUMN -> Math.sqrt(horizontalDistSqr(bx, bz));
            case YLEVEL -> Math.abs(player.getY() - by);
            case FIND -> {
                BlockPos n = finder.nearest();
                yield n == null ? NearestBlockFinder.BUDGET_BLOCKS
                        : Math.sqrt(player.distanceToSqr(n.getX() + 0.5, n.getY() + 0.5, n.getZ() + 0.5));
            }
            case ROUTE -> {
                BlockPos c = route.toward();
                yield Math.sqrt(player.distanceToSqr(c.getX() + 0.5, c.getY(), c.getZ() + 0.5));
            }
        };
    }

    // ==================== FIND(就近方块)驱动 ====================

    /**
     * 候选发现期(导航尚未建立)推进一步:收割扫描 -> 有候选即建导航(返回 null 表示落入正常驱动),扫完仍无候选 -> 失败,
     * 否则继续等。
     */
    private TaskState tickFindDiscovery() {
        finder.drain();
        if (finder.hasCandidates()) {
            nav = Trip.to(player, finder.goal(), spec, finder.nearest()).probing();
            return null;
        }
        if (finder.exhausted()) {
            String capped = finder.capNote();
            fail("no " + r.block + " found in the loaded area around me"
                    + (capped == null ? "" : " (" + capped + ")") + " — explore"
                    + " closer to one, or give exact coordinates (scan_blocks/locate can find some).",
                    FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        awaitSearch();   // 候选还没回来:站着等搜索,不算走路的刻
        return TaskState.RUNNING;
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
        return switch (r.kind) {
            case BLOCK -> r.near == null
                    ? "reached the exact cell " + bx + "," + by + "," + bz + "."
                    : "arrived within " + r.near + " blocks of " + bx + "," + by + "," + bz + ", standing at "
                            + here(gy) + ".";
            case COLUMN -> r.near == null
                    ? "arrived at location x=" + bx + " z=" + bz + ", standing on the ground at y=" + gy + "."
                    : "arrived within " + r.near + " blocks of location x=" + bx + " z=" + bz + ", standing at "
                            + here(gy) + ".";
            case YLEVEL -> "reached elevation y=" + gy + ".";
            case FIND -> {
                BlockPos n = finder.nearest();
                yield n == null
                        ? "arrived beside the target block."
                        : "arrived beside " + r.block + " at " + n.getX() + "," + n.getY()
                                + "," + n.getZ() + " — within reach to use.";
            }
            case ROUTE -> "arrived via route " + route.id() + ", standing at " + here(gy) + ".";
        };
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
                                + (r.kind == MoveToTaskRecord.Kind.ROUTE
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

    /** Release the nav (base) and drop a FIND lookup that is still walking rings for a destination nobody is going to
     *  any more. */
    @Override
    protected void cleanup() {
        super.cleanup();
        if (finder != null) {
            finder.cancelScan();
        }
        if (boatLeg != null) {
            boatLeg.stop();   // 中途被取消/让位:收桨,别让船带着按下的前进键漂走
            boatLeg = null;
        }
    }

    /**
     * 没走到时的回执:离哪儿还有多远、此刻站在哪儿,接着是寻路给的原因。精确的一格本身站不住时(给的 y 在半空或在方块里),
     * 教她省掉 y 或者给 near。
     */
    private String blockedMessage(String failReason) {
        int gy = player.blockPosition().getY();
        double remaining = repDistance();
        String where = switch (r.kind) {
            case BLOCK, COLUMN -> "location x=" + bx + " z=" + bz;
            case YLEVEL -> "elevation y=" + by;
            case FIND -> "the nearest " + r.block;
            case ROUTE -> "the destination of route " + route.id() + " (" + route.toward().toShortString() + ")";
        };
        String advice = "";
        if (r.kind == MoveToTaskRecord.Kind.BLOCK && r.near == null && !standable(blockTarget)) {
            advice = " " + bx + "," + by + "," + bz + " itself is no place to stand (in mid-air or inside a block):"
                    + " for a location omit y, or give near to stop close by.";
        } else if (nav != null && nav.failType() == FailureType.NO_PATH) {
            advice = " Try a nearer waypoint or scan_blocks for a way through.";
        }
        return "blocked: got within " + String.format("%.1f", remaining) + " blocks of " + where
                + " (now on the ground at y=" + gy + "). " + failReason + "." + advice;
    }

    /** 这一格身体待得住(站着、攀着或浮着)。 */
    private boolean standable(BlockPos cell) {
        return Terrain.of(player).standable(cell);
    }
}
