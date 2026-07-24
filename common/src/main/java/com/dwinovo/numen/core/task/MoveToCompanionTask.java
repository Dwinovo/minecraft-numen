package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code goto} on the companion player body — a coordinate walk whose goal
 * type is chosen by which coordinates were supplied
 * ({@link MoveToTaskRecord.Kind}):
 * <ul>
 *   <li>{@link MoveToTaskRecord.Kind#COLUMN} → {@link NavGoal#column}:
 *       reach the (x,z) location at any height — the default "go there", a wrong/
 *       absent Y can never make it unreachable;</li>
 *   <li>{@link MoveToTaskRecord.Kind#BLOCK} → {@link NavGoal#exact} when the
 *       cell is enterable, {@link NavGoal#getToBlock} when a solid block occupies
 *       it — the caller means "get to that block" (a chest, a crafting table),
 *       so standing adjacent counts as arrival and the block stays untouched;</li>
 *   <li>{@link MoveToTaskRecord.Kind#YLEVEL} → {@link NavGoal#yLevel}:
 *       reach a target elevation.</li>
 * </ul>
 * The planner is untouched; only the goal/arrival/result semantics differ per kind.
 * Results always echo the ACTUAL position reached (and the real ground height) so
 * the model learns the terrain and which intent to use next time.
 *
 * <p>Nav-only reactive task: it drives {@link PlayerNav} with a custom settle loop
 * (no "act" step), so it grows on {@link AbstractCompanionTask} directly rather than
 * {@code GoToThenDoTask}.
 */
public final class MoveToCompanionTask extends AbstractCompanionTask<MoveToTaskRecord> {

    private static final long TICKS_PER_BLOCK = 20;
    private static final long MAX_EXTRA_TICKS = 5 * 60 * 20;
    /** Progress lease: while the journey is consuming its plan, the deadline is kept
     *  this far ahead — a healthy multi-minute dig route never times out mid-stride,
     *  and a stalled one still returns the body within one lease. */
    private static final long PROGRESS_LEASE_TICKS = 30 * 20;
    /** How recent "progress" must be to renew the lease. Generous enough to span one
     *  slow legitimate move (a long bare-hand dig holds the executor's progress clock
     *  at 0 anyway; this covers place maneuvers and replan gaps). */
    private static final int PROGRESS_GRACE_TICKS = 100;
    /** Hard check-in cap: even a healthy marathon yields (with a resumable result) after
     *  this long, bounding how long the LLM goes without control. Renewals never push
     *  the deadline past start + this. */
    private static final long CHECK_IN_CAP_TICKS = 5 * 60 * 20;
    /** When the planner CAN'T reach the exact goal, a stop within this of the
     *  requested column still counts as "got there" (a teaching success, not a
     *  thrash). This is the only tolerance — arrival itself is exact. */
    private static final double WALK_SPEED = 1.0;
    private static final double NEAR_SUCCESS_RADIUS = 3.0;
    /** Once the planner can't get closer (e.g. it stopped at the water surface above an
     *  underwater goal), keep the task alive this many ticks of NO progress before giving
     *  up — long enough for the body to passively drift onto a reachable underwater target,
     *  short enough to bail under an out-of-reach above-water one. */
    private static final int MAX_SETTLE_TICKS = 60;

    private final int bx;
    private final int by;
    private final int bz;
    private final BlockPos blockTarget;   // only meaningful for BLOCK kind

    private double bestDist = Double.MAX_VALUE;   // closest we've gotten to the goal
    private int settleTicks = 0;                  // ticks of no progress after the planner gave up
    /** The one near-retry recovery rung has been consumed (ladder state — survives suspend). */
    private boolean nearRetried;
    /** Absolute ceiling for lease renewals (start + {@link #CHECK_IN_CAP_TICKS}); 0 = unset. */
    private long leaseCapGameTime;

    // ==================== FIND(就近方块)状态 ====================
    /** 候选上限、扫描同层判据/最远环半径、离线扫描的放弃时限、行程预算基准。 */
    private static final int FIND_MAX_CANDIDATES = 64;
    private static final int FIND_Y_THRESHOLD = 10;
    private static final int FIND_MAX_CHUNK_RADIUS = 32;
    private static final long FIND_SCAN_TIMEOUT_TICKS = 40;
    private static final int FIND_BUDGET_BLOCKS = 128;
    /** 解析出的目标方块(FIND 专用)。 */
    private net.minecraft.world.level.block.Block findTarget;
    /** 仍在册的候选格(打不通的会被逐个除名)。 */
    private final java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
    /** 在飞的离线扫描;完成/超时后归 null。 */
    private java.util.concurrent.CompletableFuture<java.util.List<com.dwinovo.numen.core.scan.BlockScanner.Hit>> findScan;
    private long findScanDeadline;
    private boolean findScanDrained;
    /** 候选集编译出的导航契约(候选变动时重建)。 */
    private com.dwinovo.numen.core.pathing.goal.GoalCompiler.Compiled findContract;

    public MoveToCompanionTask(NumenPlayer player, MoveToTaskRecord record) {
        super(player, record);
        this.bx = record.x != null ? (int) Math.floor(record.x) : 0;
        this.by = record.y != null ? (int) Math.floor(record.y) : 0;
        this.bz = record.z != null ? (int) Math.floor(record.z) : 0;
        this.blockTarget = new BlockPos(bx, by, bz);
    }

    @Override
    protected void onStart() {
        if (r.kind == MoveToTaskRecord.Kind.FIND) {
            // 就近方块:解析 id → 离线扫描附近候选;导航等首批候选到手再建
            var id = net.minecraft.resources.ResourceLocation.tryParse(r.block);
            var b = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(id);
            if (b == null || b == net.minecraft.world.level.block.Blocks.AIR) {
                fail("unknown block id '" + r.block
                        + "' — use a namespaced block id like minecraft:crafting_table",
                        FailureType.NO_PATH);
                return;
            }
            findTarget = b;
            long findExtra = Math.min(MAX_EXTRA_TICKS, 600 + (long) FIND_BUDGET_BLOCKS * TICKS_PER_BLOCK);
            r.extendDeadlineTo(player.level().getGameTime() + findExtra);
            leaseCapGameTime = player.level().getGameTime() + CHECK_IN_CAP_TICKS;
            kickFindScan();
            com.dwinovo.numen.Constants.LOG.info(
                    "[numen-task] goto start kind=FIND block={}", r.block);
            return;
        }
        // Already there: don't build a nav (and don't extend the deadline). The first
        // onTick observes reached() and returns SUCCESS — same outcome as the old
        // start-time short-circuit, one tick later per the base's lifecycle.
        if (reached()) return;
        // Initial budget from straight-line distance (terrain difficulty is unknowable
        // here — the progress lease below takes over once the journey is under way).
        long extra = Math.min(MAX_EXTRA_TICKS, 600 + (long) (repDistance() * TICKS_PER_BLOCK));
        r.extendDeadlineTo(player.level().getGameTime() + extra);
        leaseCapGameTime = player.level().getGameTime() + CHECK_IN_CAP_TICKS;
        // BLOCK targets go through the compiled front door so the target cell is
        // SACRED when solid — the route may neither dig through nor bury the very
        // block it was asked to reach. COLUMN/YLEVEL have no block objective.
        nav = r.kind == MoveToTaskRecord.Kind.BLOCK
                ? PlayerNav.to(player, this::blockCompiled, WALK_SPEED, this::reached)
                : PlayerNav.toGoal(player, this::goal, WALK_SPEED, this::reached);
        com.dwinovo.numen.Constants.LOG.info(
                "[numen-task] goto start kind={} target={},{},{} solid={}",
                r.kind, bx, by, bz,
                r.kind == MoveToTaskRecord.Kind.BLOCK && targetCellSolid());
        // Highlight the ACTUAL requested cell (not the path's best-effort end) so the overlay
        // box sits on the real target — e.g. a BLOCK goal under/over water that the path can
        // only approach to the surface. The goal itself is always rendered, not the plan's end.
        if (r.kind == MoveToTaskRecord.Kind.BLOCK) {
            nav.setHighlights(() -> java.util.List.of(blockTarget));
        }
    }

    /** The navigation goal for this move's kind. */
    private NavGoal goal() {
        return switch (r.kind) {
            case BLOCK -> blockGoal();
            case COLUMN -> NavGoal.column(bx, bz);
            case YLEVEL -> NavGoal.yLevel(by);
            case FIND -> findContract == null ? null : findContract.goal();
        };
    }

    /**
     * BLOCK auto-typing: an enterable target cell means "stand exactly there"
     * ({@link NavGoal#exact}); a cell occupied by a solid means "get to that
     * block" ({@link NavGoal#getToBlock} — beside/on top counts, the block stays
     * untouched). Re-evaluated per replan, so a cell that opens up mid-journey
     * (the occupant broke) tightens back to exact.
     */
    private NavGoal blockGoal() {
        return blockCompiled().goal();
    }

    /** The BLOCK kind's navigation contract: bare coordinates mean occupy
     *  exactly that cell, digging out whatever is there (the block form is
     *  the way to say "walk up beside it instead"). */
    private com.dwinovo.numen.core.pathing.goal.GoalCompiler.Compiled blockCompiled() {
        return com.dwinovo.numen.core.pathing.goal.GoalCompiler.standOn(blockTarget);
    }

    /** Does a collision shape occupy the target cell (feet can't go there)? */
    private boolean targetCellSolid() {
        return !player.level().getBlockState(blockTarget)
                .getCollisionShape(player.level(), blockTarget).isEmpty();
    }

    /** Slab-aware feet cell — the pathing node, not raw blockPosition (standing on a
     *  bottom slab counts as the cell above it, like the planner sees it). */
    private BlockPos feet() {
        return com.dwinovo.numen.core.pathing.util.BlockHelper.playerFeet(
                player.level(), player.getX(), player.getY(), player.getZ());
    }

    /**
     * Live arrival — DOUBLE membership: the feet cell AND the supported
     * fake-start cell must both satisfy the goal. The second gate is what keeps
     * transient cell-entry from counting as arrival: a pillar's final jump puts
     * the feet in the goal cell at the APEX a tick before its support block is
     * placed, and a bridge's final backplace hovers the feet into the goal cell
     * while sneak-clinging to the previous block's edge — in both states the
     * body has no support under the goal cell yet, pathStart resolves to the
     * neighbouring supported cell, and arrival is (correctly) withheld until
     * the block is actually placed and stood on. Declaring success on the
     * feet-only test stopped the nav mid-move: the place never fired and the
     * halt released the sneak that was holding the body on the edge — the
     * "one block short, one step too far" fall.
     */
    private boolean reached() {
        return inGoalCell(feet())
                && inGoalCell(com.dwinovo.numen.core.pathing.moves.Movement.pathStart(player));
    }

    /** ONE membership definition per kind, shared with the search:
     *  BLOCK (cell == target per arrival mode), COLUMN (x/z match),
     *  YLEVEL (y match + on the ground). */
    private boolean inGoalCell(BlockPos cell) {
        return switch (r.kind) {
            case BLOCK -> blockGoal().isAt(cell);
            case COLUMN -> cell.getX() == bx && cell.getZ() == bz;
            case YLEVEL -> cell.getY() == by && player.onGround();
            case FIND -> findContract != null && findContract.goal().isAt(cell);
        };
    }

    @Override
    protected TaskState onTick() {
        // reached() is checked BEFORE the nav==null guard so an already-at-target start
        // (which never builds a nav) lands on SUCCESS rather than the defensive FAILED.
        if (reached()) return TaskState.SUCCESS;
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
        // Progress lease: while the nav is consuming its plan (steps advancing / digging),
        // keep the deadline PROGRESS_LEASE ahead — never past the check-in cap. Plan
        // consumption, NOT goal distance, is the liveness signal: healthy routes routinely
        // move away from the goal (skirting a lake, spiraling down), and the flat budget
        // above can't price terrain (a dig-heavy route once died 1 block short).
        if (nav.stallTicks() <= PROGRESS_GRACE_TICKS && leaseCapGameTime > 0) {
            long now = player.level().getGameTime();
            r.extendDeadlineTo(Math.min(now + PROGRESS_LEASE_TICKS, leaseCapGameTime));
        }
        // Track passive progress toward the goal: the planner stops at the water surface
        // above an underwater target, but the body keeps drifting toward it on its own (it
        // sinks). Reset the settle timer whenever we get closer.
        double d = repDistance();
        if (d < bestDist - 0.1) {
            bestDist = d;
            settleTicks = 0;
        } else {
            settleTicks++;
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> TaskState.SUCCESS;
            case FAILED -> {
                // FIND:打不通就近候选 -> 除名,朝余下候选重开导航
                if (r.kind == MoveToTaskRecord.Kind.FIND && candidates.size() > 1) {
                    int nearest = nearestCandidateIndex();
                    if (nearest >= 0) {
                        candidates.remove(nearest);
                    }
                    rebuildFindContract();
                    stopNav();
                    nav = PlayerNav.to(player, () -> findContract, WALK_SPEED, this::reached);
                    nav.setHighlights(() -> java.util.List.copyOf(candidates));
                    yield TaskState.RUNNING;
                }
                // The planner can't get closer. In water, keep waiting while the body is
                // still drifting toward the goal (sinking onto an underwater target); give
                // up only once it's stopped making progress (bobbing at the surface below an
                // out-of-reach above-water target). So the body settles onto an underwater
                // goal but bails under an unreachable air one. On land a failure is final.
                if (player.isInWater() && settleTicks < MAX_SETTLE_TICKS) {
                    yield TaskState.RUNNING;
                }
                // Otherwise: as close as the terrain allows → (teaching) success or fail.
                if (closeEnoughToSucceed()) yield TaskState.SUCCESS;
                // Recovery ladder — ONE retry rung, land nav only: re-plan accepting
                // anywhere within NEAR_SUCCESS_RADIUS of the destination. Goal-consistent,
                // not scope creep: a stop within that radius already counts as arrival
                // (closeEnoughToSucceed above), the retry just lets the SEARCH aim for it.
                // YLEVEL has no looser near-equivalent (its goal is already any-x/z), and
                // the water-settle path above is untouched.
                if (!nearRetried && !player.isInWater()
                        && r.kind != MoveToTaskRecord.Kind.YLEVEL
                        && r.kind != MoveToTaskRecord.Kind.FIND) {
                    nearRetried = true;
                    stopNav();
                    NavGoal retry = nearRetryGoal();
                    nav = PlayerNav.toGoal(player, () -> retry, WALK_SPEED, this::closeEnoughToSucceed);
                    if (r.kind == MoveToTaskRecord.Kind.BLOCK) {
                        nav.setHighlights(() -> java.util.List.of(blockTarget));
                    }
                    yield TaskState.RUNNING;
                }
                String also = nearRetried
                        ? " (also retried accepting anywhere within "
                                + (int) NEAR_SUCCESS_RADIUS + " blocks — no path either)"
                        : "";
                fail(blockedMessage(nav.failReason() + also), nav.failType());
                yield TaskState.FAILED;
            }
        };
    }

    /** The retry rung's loosened goal — the destination widened to the SAME radius that
     *  already counts as arrival ({@link #NEAR_SUCCESS_RADIUS}), never wider. */
    private NavGoal nearRetryGoal() {
        if (r.kind == MoveToTaskRecord.Kind.BLOCK) {
            return NavGoal.near(blockTarget, NEAR_SUCCESS_RADIUS);
        }
        // COLUMN: within the radius HORIZONTALLY at any height (NavGoal.near is 3D and
        // needs a Y this kind doesn't have; heuristic/center reuse the column's own).
        NavGoal column = NavGoal.column(bx, bz);
        double radiusSqr = NEAR_SUCCESS_RADIUS * NEAR_SUCCESS_RADIUS;
        return new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                double dx = feet.getX() - bx;
                double dz = feet.getZ() - bz;
                return dx * dx + dz * dz <= radiusSqr;
            }
            @Override public double heuristic(BlockPos from) {
                return column.heuristic(from);
            }
            @Override public BlockPos center() {
                return column.center();
            }
        };
    }

    /** Did we get close enough to the destination to call it done (teaching success)?
     *  Requires solid footing (or water — the settle path): as a live arrival
     *  predicate on the near-retry nav this must not fire during a mid-air jump
     *  or a sneak-hover over the edge, for the same reason as {@link #reached}. */
    private boolean closeEnoughToSucceed() {
        if (!player.onGround() && !player.isInWater()) {
            return false;
        }
        return switch (r.kind) {
            case BLOCK, COLUMN -> horizontalDistSqr(bx, bz) <= NEAR_SUCCESS_RADIUS * NEAR_SUCCESS_RADIUS;
            case YLEVEL -> Math.abs(feet().getY() - by) <= 1;
            // FIND 候选众多,失败梯已在候选间轮换过,不设贴近成功档
            case FIND -> false;
        };
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
                BlockPos n = nearestCandidate();
                yield n == null ? FIND_BUDGET_BLOCKS
                        : Math.sqrt(player.distanceToSqr(n.getX() + 0.5, n.getY() + 0.5, n.getZ() + 0.5));
            }
        };
    }

    // ==================== FIND(就近方块)机制 ====================

    /**
     * 候选发现期(导航尚未建立)推进一步:收割扫描 -> 有候选即建导航
     * (返回 null 表示落入正常驱动),扫完仍无候选 -> 失败,否则继续等。
     */
    private TaskState tickFindDiscovery() {
        drainFindScan();
        if (!candidates.isEmpty()) {
            rebuildFindContract();
            nav = PlayerNav.to(player, () -> findContract, WALK_SPEED, this::reached);
            nav.setHighlights(() -> java.util.List.copyOf(candidates));
            return null;
        }
        if (findScan == null && findScanDrained) {
            fail("no " + r.block + " found in the loaded area around me — explore"
                    + " closer to one, or give exact coordinates (scan_blocks/locate can find some).",
                    FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        return TaskState.RUNNING;
    }

    /** 踢一次离线扫描:主线程环形捕获身体周围的已加载 chunk 引用(捕获止于
     *  加载区边缘),后台按环序由近及远扫,凑够即提前收工。 */
    private void kickFindScan() {
        var level = player.level();
        // 圆心用寻路口径的脚位格(0.1251 上抬 + 台阶取上格)——同层判据与
        // section 遍历序都从它导出。
        var cap = com.dwinovo.numen.core.scan.BlockScanner.captureRings(level,
                com.dwinovo.numen.core.pathing.util.BlockHelper.playerFeet(
                        level, player.getX(), player.getY(), player.getZ()));
        findScan = com.dwinovo.numen.core.scan.ScanExecutor.submit(
                () -> com.dwinovo.numen.core.scan.BlockScanner.scanRings(
                        level, cap, java.util.Set.of(findTarget),
                        FIND_MAX_CANDIDATES, FIND_Y_THRESHOLD, FIND_MAX_CHUNK_RADIUS));
        findScanDeadline = level.getGameTime() + FIND_SCAN_TIMEOUT_TICKS;
    }

    /** 收割离线扫描:按距离取最近的前 {@link #FIND_MAX_CANDIDATES} 个入册。 */
    private void drainFindScan() {
        if (findScan == null) {
            return;
        }
        if (!findScan.isDone()) {
            if (player.level().getGameTime() > findScanDeadline) {
                findScan.cancel(false);
                findScan = null;
                findScanDrained = true;
            }
            return;
        }
        java.util.List<com.dwinovo.numen.core.scan.BlockScanner.Hit> hits;
        try {
            hits = findScan.join();
        } catch (Exception e) {
            hits = java.util.List.of();
        }
        findScan = null;
        findScanDrained = true;
        // 入册前过与 mine 同一道目标剪枝:挖不动/禁挖(贴液体等)/基岩上下
        // 夹死的格不作候选——省得选中一个走近了也没法处置的目标。
        var ctx = com.dwinovo.numen.core.pathing.bridge.ContextFactory.forExecution(player);
        hits.stream()
                .sorted(java.util.Comparator.comparingDouble(
                        com.dwinovo.numen.core.scan.BlockScanner.Hit::distance))
                .map(h -> h.pos().immutable())
                .filter(p -> MineCompanionTask.plausibleToBreak(
                        ctx, p, ctx.get(p.getX(), p.getY(), p.getZ())))
                .limit(FIND_MAX_CANDIDATES)
                .forEach(candidates::add);
    }

    private void rebuildFindContract() {
        findContract = candidates.isEmpty() ? null
                : com.dwinovo.numen.core.pathing.goal.GoalCompiler.anyOf(candidates);
    }

    /** 离身体最近的在册候选;无候选返回 null。 */
    private BlockPos nearestCandidate() {
        int i = nearestCandidateIndex();
        return i < 0 ? null : candidates.get(i);
    }

    private int nearestCandidateIndex() {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            BlockPos c = candidates.get(i);
            double d = player.distanceToSqr(c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
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
            case BLOCK -> {
                if (feet().equals(blockTarget)) {
                    yield "reached the exact cell " + bx + "," + by + "," + bz + ".";
                }
                // Got to the column but not the exact y (the usual "guessed Y was in
                // the air" case) — teach the model to drop Y for a location.
                int dy = by - gy;
                yield "arrived at location x=" + bx + " z=" + bz + ", standing on the ground at y=" + gy
                        + ". The exact cell y=" + by + " wasn't reachable (" + Math.abs(dy) + " blocks "
                        + (dy > 0 ? "up — likely mid-air" : "down — likely blocked")
                        + "); for a location, omit y and I resolve the surface.";
            }
            case COLUMN -> "arrived at location x=" + bx + " z=" + bz
                    + ", standing on the ground at y=" + gy + ".";
            case YLEVEL -> "reached elevation y=" + gy
                    + (gy == by ? "." : " (requested y=" + by + ").");
            case FIND -> {
                BlockPos n = nearestCandidate();
                yield n == null
                        ? "arrived beside the target block."
                        : "arrived beside " + r.block + " at " + n.getX() + "," + n.getY()
                                + "," + n.getZ() + " — within reach to use.";
            }
        };
    }

    @Override
    protected String timeoutMessage() {
        int gy = player.blockPosition().getY();
        double remaining = repDistance();
        // Two different stories for the model: a stall (progress dried up — something is
        // wrong, reconsider) vs a check-in (journey healthy but longer than the cap —
        // resuming is the right move).
        boolean stalled = nav == null || nav.stallTicks() > PROGRESS_GRACE_TICKS;
        return "timed out " + String.format("%.1f", remaining) + " blocks from target (now at "
                + bx(gy) + "); "
                + (stalled
                        ? "progress had stopped — likely blocked; call goto again to retry, or"
                                + " try a nearer waypoint / scan_blocks for a way through."
                        : "the journey was still progressing and simply exceeded its check-in budget;"
                                + " call goto again with the same target to resume.");
    }

    @Override
    protected String cancelledMessage() {
        return "cancelled before reaching target";
    }

    private String bx(int gy) {
        return String.format("%.0f,%d,%.0f", player.getX(), gy, player.getZ());
    }

    /** The give-up message for a planner failure that wasn't close enough to count as arrival.
     *  Captured at the fail site (nav still alive) so its {@code failReason} is readable before
     *  the base's {@code cleanup()} releases the nav. */
    private String blockedMessage(String failReason) {
        int gy = player.blockPosition().getY();
        double remaining = repDistance();
        String where = switch (r.kind) {
            case BLOCK, COLUMN -> "location x=" + bx + " z=" + bz;
            case YLEVEL -> "elevation y=" + by;
            case FIND -> "the nearest " + r.block;
        };
        // Everything breakable is already on the table (priced by dig time), so the
        // fix for a block is always geometry: nearer waypoint or scanning.
        String advice = ". Try a nearer waypoint or scan_blocks for a way through.";
        return "blocked: got within " + String.format("%.1f", remaining) + " blocks of " + where
                + " (now on the ground at y=" + gy + "). " + failReason + advice;
    }
}
