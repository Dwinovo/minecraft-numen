package com.dwinovo.numen.core.pathing.exec.drive;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavContext;
import com.dwinovo.numen.core.pathing.movement.Movement;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import net.minecraft.core.BlockPos;

/**
 * One movement's driver: the object that owns EVERYTHING kind-specific about
 * executing a single {@link Movement} — per-tick input pressing ({@link #drive}),
 * arrival detection ({@link #arrived}), interruption safety ({@link #safeToCancel}),
 * world-premise validation ({@link #premiseBroken}), and any per-move mutable state
 * (sprint carry, tick counters, in-flight maneuvers). The executor never branches
 * on {@link Movement.Kind}: it runs a kind-blind watchdog pipeline and delegates
 * every kind-specific question to the current driver.
 *
 * <p>Lifecycle: constructed when its movement becomes current (one driver per
 * movement occurrence — state dies with the driver, nothing to remember to reset),
 * {@link #stop()} released when the movement ends or the path is abandoned.
 *
 * <p>The single remaining kind-switch is the {@link #of} factory. The cross-move
 * sprint/splice optimizer (in the executor) is the one sanctioned puncture of this
 * boundary: it may downcast the current driver to poke sprint carry flags — an
 * optimization channel, never a correctness dependency.
 */
public abstract class MoveDriver {

    protected final NumenPlayer player;
    protected final Movement mv;
    protected final double speed;

    protected MoveDriver(NumenPlayer player, Movement mv, double speed) {
        this.player = player;
        this.mv = mv;
        this.speed = speed;
    }

    /** The one place execution maps a {@link Movement.Kind} to behavior. */
    public static MoveDriver of(NumenPlayer player, Movement mv, double speed) {
        return switch (mv.kind) {
            case TRAVERSE -> new TraverseDriver(player, mv, speed);
            case DIAGONAL -> new DiagonalDriver(player, mv, speed);
            case ASCEND -> new AscendDriver(player, mv, speed);
            case DESCEND -> new DescendDriver(player, mv, speed);
            case FALL -> new FallDriver(player, mv, speed);
            case PILLAR -> new PillarDriver(player, mv, speed);
            case DIG_DOWN -> new DigDownDriver(player, mv, speed);
            case PARKOUR -> new ParkourDriver(player, mv, speed);
        };
    }

    /** The movement this driver executes. */
    public final Movement movement() {
        return mv;
    }

    // ---- the per-kind contract ----

    /** Press this tick's inputs (the executor has already cleared sneak and handled
     *  doors/obstructions/scaffold phases — this is pure locomotion). */
    public abstract void drive();

    /** Has the move completed (feet where they should be, world as it should be)? */
    public abstract boolean arrived();

    /**
     * May the executor abandon this move right now (for a replan / path switch)
     * without dropping the body into a bad state (mid-air, mid-bridge, mid-place)?
     *
     * @param scaffoldCommitted the executor's scaffold phase has started or finished
     *                          placing this move's {@code toPlace}
     * @param ticksOnCurrent    ticks this move has been current (0 = not yet driven)
     */
    public boolean safeToCancel(boolean scaffoldCommitted, int ticksOnCurrent) {
        return true;
    }

    /**
     * Cheap per-tick world-premise check: does the world still satisfy what this
     * move's plan assumed (floors that must exist, etc.)? A broken premise means
     * driving can only grind a timeout — the executor surrenders the move for a
     * replan immediately. {@code null} = premise holds.
     */
    public String premiseBroken() {
        return null;
    }

    /** May the body legitimately be fully submerged during this move? (The executor
     *  treats sustained submersion as off-plan unless the driver claims it.) */
    public boolean allowsSubmersion() {
        return false;
    }

    /**
     * The cell the executor's scaffold phase must make solid BEFORE driving this move,
     * or {@code null} when nothing is needed — derived from the LIVE world each tick,
     * not from the plan. The plan's {@code toPlace} is a pricing prediction; by
     * execution time the floor it assumed may have been dug away (the path's own
     * earlier breaks included), or the hole it predicted may already be filled. The
     * dig phase has always been live-world-driven (re-digs whatever is still solid);
     * this is the placing phase's symmetric contract.
     *
     * <p>Default: the plan's {@code toPlace}, while it still isn't walkable. Moves
     * that stand on {@code dest} override with a live floor check.
     */
    public BlockPos scaffoldCell() {
        return mv.toPlace != null && !BlockHelper.canWalkOn(player.level(), mv.toPlace)
                ? mv.toPlace : null;
    }

    /** Live floor-under-dest need: the shared {@link #scaffoldCell()} for moves that
     *  end standing on {@code dest} — cheap, common scaffolding is spent to restore a
     *  floor the plan assumed, whoever removed it. */
    protected final BlockPos floorUnderDest() {
        BlockPos floor = mv.dest.below();
        return BlockHelper.canWalkOn(player.level(), floor) ? null : floor;
    }

    /** Kind-specific inputs while the executor's dig phase holds the body (e.g. a
     *  traverse keeps approaching the breaking block). Default: none. */
    public void duringBreak() {}

    /** Extra per-kind state for the one-line diagnostics ("" = nothing to add). */
    public String debugFlags() {
        return "";
    }

    /** Release anything held (sneak, in-flight maneuvers). Called when the move
     *  ends, is rewound, or the path is abandoned. */
    public void stop() {}

    // ---- kind knowledge queried without a driver instance ----

    /** Kinds whose execution changes the world such that regenerating them mid-move
     *  would spuriously report them gone (place-under / break-floor) — exempt from
     *  the executor's live re-costing. */
    public static boolean selfMutating(Movement.Kind kind) {
        return kind == Movement.Kind.PILLAR
                || kind == Movement.Kind.DIG_DOWN;
    }

    /** The inventory slot of a scaffold block (cobble/dirt/…), or -1 if none. */
    public static int scaffoldSlot(NumenPlayer player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (NavContext.isScaffold(inv.getItem(i))) return i;
        }
        return -1;
    }

    /** The net move vector (dest − src). */
    public static BlockPos dirOf(Movement mv) {
        return mv.dest.subtract(mv.src);
    }

    // ---- shared geometry helpers ----

    /** The feet cell, nudged up 0.1251 (so soul-sand /
     *  farmland sink doesn't read us a block low), and — when that cell is a SLAB — taken
     *  as the cell ABOVE it. That slab adjustment is what lets standing on a bottom slab
     *  read as the move's dest (moves onto a slab target the cell above the slab). */
    protected final BlockPos feet() {
        return BlockHelper.playerFeet(player.level(), player.getX(), player.getY(), player.getZ());
    }

    /** Horizontal distance from the body to a cell's centre. */
    protected final double horizontalDistTo(BlockPos cell) {
        double dx = (cell.getX() + 0.5) - player.getX();
        double dz = (cell.getZ() + 0.5) - player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Chebyshev (max-axis) horizontal distance to a cell centre — the
     *  walk-while-breaking "pressed against the block" gate uses this, not Euclidean. */
    protected final double chebyshevDistTo(BlockPos cell) {
        return Math.max(Math.abs((cell.getX() + 0.5) - player.getX()),
                        Math.abs((cell.getZ() + 0.5) - player.getZ()));
    }

    /** Horizontal speed² this tick — used to gate jump timing on measured motion. */
    protected final double horizontalSpeedSqr() {
        var v = player.getDeltaMovement();
        return v.x * v.x + v.z * v.z;
    }

    /** Something to avoid in the cell(s) one step PAST the destination — the block we'd
     *  carry into if we over-committed. Descend safe mode + sprint suppression both
     *  gate on {@code avoidWalkingInto} (any fluid + the hazard block set) for exactly this. */
    protected final boolean hazardJustPast() {
        int dx = Integer.signum(mv.dest.getX() - mv.src.getX());
        int dz = Integer.signum(mv.dest.getZ() - mv.src.getZ());
        if (dx == 0 && dz == 0) return false;
        BlockPos into = mv.dest.offset(dx, 0, dz);
        for (int y = 0; y <= 2; y++) {
            if (BlockHelper.avoidWalkingInto(player.level(), into.above(y))) return true;
        }
        return false;
    }

    /** Base sprint gate: never in water, nor when about to run into a hazard just
     *  past the destination (momentum could carry us into lava/cactus/etc). */
    protected final boolean sprintBase() {
        return speed >= 1.0 && !player.isInWater() && !hazardJustPast();
    }
}
