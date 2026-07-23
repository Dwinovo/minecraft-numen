package com.dwinovo.numen.core.pathing.goal;

import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The body-level half of arrival: where {@link NavGoal#isAt} judges feet CELLS
 * in the search's node domain, this judges the LIVE body — reach distance,
 * line of sight, on-ground — so a task's {@code reached} predicate and the
 * search goal are derived from the same compiled intent instead of drifting
 * apart in hand-rolled copies (the drift behind "search says arrived, task
 * says not" livelocks).
 *
 * <p>This is an INGREDIENT for a task's {@code reached} supplier, not a
 * replacement: tasks still compose extras only they know (entity liveness,
 * hold timers) around {@link #test}.
 *
 * @param focus       reach/line-of-sight reference cell; null = no focus terms
 * @param reachSqr    squared reach gate on {@code focus}; {@code <= 0} disables
 * @param lineOfSight require a clear eyes→focus sight line (off by default for
 *                    interact-style specs — see {@link #withLineOfSight()})
 * @param grounded    require {@code onGround} (kills "arrived at a jump apex")
 * @param membership  feet-cell membership to require, or null to skip
 */
public record ArrivalSpec(BlockPos focus, double reachSqr, boolean lineOfSight,
                          boolean grounded, NavGoal membership) {

    /** The player-style block interaction reach (matches every task's 4.5). */
    public static final double REACH = 4.5;

    /** Does the live body satisfy this spec right now? */
    public boolean test(NumenPlayer player) {
        if (grounded && !player.onGround()) {
            return false;
        }
        if (membership != null) {
            BlockPos feet = BlockHelper.playerFeet(
                    player.level(), player.getX(), player.getY(), player.getZ());
            if (!membership.isAt(feet)) {
                return false;
            }
        }
        if (focus != null && reachSqr > 0
                && player.distanceToSqr(Vec3.atCenterOf(focus)) > reachSqr) {
            return false;
        }
        if (focus != null && lineOfSight && !hasLineOfSight(player, focus)) {
            return false;
        }
        return true;
    }

    /** Clear sight line from the eyes to the target block's centre (nothing solid
     *  blocks it but the target itself) — the single implementation; tasks that
     *  used private copies delegate here. */
    public static boolean hasLineOfSight(NumenPlayer player, BlockPos target) {
        BlockHitResult hit = player.level().clip(new ClipContext(
                player.getEyePosition(), Vec3.atCenterOf(target),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    // ---- factories (compiler-facing) ----

    /** Work-on-a-block arrival: grounded, within {@link #REACH} of the target.
     *  Line of sight is OFF by default (matching every existing interact-style
     *  task predicate); opt in via {@link #withLineOfSight()}. */
    public static ArrivalSpec interact(BlockPos target) {
        return new ArrivalSpec(target.immutable(), REACH * REACH, false, true, null);
    }

    /** Occupy-a-cell arrival: grounded + feet-cell membership of the search goal
     *  itself (one definition of "there"). */
    public static ArrivalSpec standOn(NavGoal membership) {
        return new ArrivalSpec(null, 0.0, false, true, membership);
    }

    /** Vicinity arrival: within {@code radius} of {@code center}; no ground/LOS
     *  terms (chasing things that move). */
    public static ArrivalSpec near(BlockPos center, double radius) {
        return new ArrivalSpec(center.immutable(), radius * radius, false, false, null);
    }

    /** This spec, with the eyes→focus sight-line requirement switched on. */
    public ArrivalSpec withLineOfSight() {
        return new ArrivalSpec(focus, reachSqr, true, grounded, membership);
    }
}
