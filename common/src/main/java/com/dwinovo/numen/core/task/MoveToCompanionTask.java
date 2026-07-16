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
 * {@code move_to} on the companion player body — a coordinate walk whose goal
 * type is chosen by which coordinates were supplied
 * ({@link MoveToTaskRecord.Kind}):
 * <ul>
 *   <li>{@link MoveToTaskRecord.Kind#COLUMN} → {@link NavGoal#column}:
 *       reach the (x,z) location at any height — the default "go there", a wrong/
 *       absent Y can never make it unreachable;</li>
 *   <li>{@link MoveToTaskRecord.Kind#BLOCK} → {@link NavGoal#exact}:
 *       one exact cell;</li>
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

    public MoveToCompanionTask(NumenPlayer player, MoveToTaskRecord record) {
        super(player, record);
        this.bx = record.x != null ? (int) Math.floor(record.x) : 0;
        this.by = record.y != null ? (int) Math.floor(record.y) : 0;
        this.bz = record.z != null ? (int) Math.floor(record.z) : 0;
        this.blockTarget = new BlockPos(bx, by, bz);
    }

    @Override
    protected void onStart() {
        // Already there: don't build a nav (and don't extend the deadline). The first
        // onTick observes reached() and returns SUCCESS — same outcome as the old
        // start-time short-circuit, one tick later per the base's lifecycle.
        if (reached()) return;
        // Initial budget from straight-line distance (terrain difficulty is unknowable
        // here — the progress lease below takes over once the journey is under way).
        long extra = Math.min(MAX_EXTRA_TICKS, 600 + (long) (repDistance() * TICKS_PER_BLOCK));
        r.extendDeadlineTo(player.level().getGameTime() + extra);
        leaseCapGameTime = player.level().getGameTime() + CHECK_IN_CAP_TICKS;
        nav = PlayerNav.toGoal(player, this::goal, r.speed, this::reached, r.modifyTerrain);
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
            case BLOCK -> NavGoal.exact(blockTarget);
            case COLUMN -> NavGoal.column(bx, bz);
            case YLEVEL -> NavGoal.yLevel(by);
        };
    }

    /** Live arrival — exact, matching each goal's own membership test:
     *  BLOCK (feet == cell), COLUMN (feet x/z == target), YLEVEL (feet y == level).
     *  YLEVEL additionally requires being ON THE GROUND: a pillar reaches the target y at
     *  the jump APEX a tick before its support block is placed, so without the onGround
     *  gate we'd declare success mid-air, pre-empt the place, and fall back (the stray
     *  extra hop). onGround makes the body actually settle on the placed block. */
    /** Slab-aware feet cell — the pathing node, not raw blockPosition (standing on a
     *  bottom slab counts as the cell above it, like the planner sees it). */
    private BlockPos feet() {
        return com.dwinovo.numen.core.pathing.util.BlockHelper.playerFeet(
                player.level(), player.getX(), player.getY(), player.getZ());
    }

    private boolean reached() {
        BlockPos feet = feet();
        return switch (r.kind) {
            case BLOCK -> feet.equals(blockTarget);
            case COLUMN -> feet.getX() == bx && feet.getZ() == bz;
            case YLEVEL -> feet.getY() == by && player.onGround();
        };
    }

    @Override
    protected TaskState onTick() {
        // reached() is checked BEFORE the nav==null guard so an already-at-target start
        // (which never builds a nav) lands on SUCCESS rather than the defensive FAILED.
        if (reached()) return TaskState.SUCCESS;
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
                        && r.kind != MoveToTaskRecord.Kind.YLEVEL) {
                    nearRetried = true;
                    stopNav();
                    NavGoal retry = nearRetryGoal();
                    nav = PlayerNav.toGoal(player, () -> retry, r.speed, this::closeEnoughToSucceed,
                            r.modifyTerrain);
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

    /** Did we get close enough to the destination to call it done (teaching success)? */
    private boolean closeEnoughToSucceed() {
        return switch (r.kind) {
            case BLOCK, COLUMN -> horizontalDistSqr(bx, bz) <= NEAR_SUCCESS_RADIUS * NEAR_SUCCESS_RADIUS;
            case YLEVEL -> Math.abs(feet().getY() - by) <= 1;
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
        };
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
                        ? "progress had stopped — likely blocked; call move_to again to retry, or"
                                + " try a nearer waypoint / scan_blocks for a way through."
                        : "the journey was still progressing and simply exceeded its check-in budget;"
                                + " call move_to again with the same target to resume.");
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
        };
        // Two different next steps for the model: under force-break everything breakable
        // was already on the table, so the fix is geometry (waypoints/scanning); under
        // normal breaking the failReason may name a block nothing we carry harvests —
        // then the fix is the right tool, or the flag.
        String advice = r.modifyTerrain
                ? ". Try a nearer waypoint or scan_blocks for a way through."
                : ". Try a nearer waypoint or scan_blocks for a way through; if the reason above"
                        + " names a block I can't harvest, give me the right tool for it, or"
                        + " re-run with modify_terrain:true to force-dig through anyway"
                        + " (slow, and those blocks drop nothing).";
        return "blocked: got within " + String.format("%.1f", remaining) + " blocks of " + where
                + " (now on the ground at y=" + gy + "). " + failReason + advice;
    }
}
