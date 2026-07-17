package com.dwinovo.numen.core.pathing.exec.drive;

import com.dwinovo.numen.entity.InputDriver;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.core.act.Placement;
import com.dwinovo.numen.core.pathing.movement.Movement;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Block-tower pillar: sneak so we never step off the
 * column, stay centred, jump from the ground, and while airborne keep attempting
 * to place a block in the cell we just left so we land one higher. A water-column
 * variant swims up on buoyancy instead (no sneak, no place).
 */
final class PillarDriver extends MoveDriver {

    /** Persistent pillar-place blockage diagnostics — names the failing gate once a second. */
    private int underfootBlockedTicks;

    PillarDriver(NumenPlayer player, Movement mv, double speed) {
        super(player, mv, speed);
    }

    @Override
    public void drive() {
        // Swim straight up a water column:
        // aim at the destination cell's centre and press forward ONLY when off-centre by
        // >0.2 (just to recentre) — buoyancy does the actual rising. No sneak, no place.
        if (BlockHelper.isWater(player.level(), mv.src)) {
            player.setShiftKeyDown(false);
            InputDriver.lookAt(player, Vec3.atCenterOf(mv.dest));
            double cx = mv.dest.getX() + 0.5;
            double cz = mv.dest.getZ() + 0.5;
            player.zza = (Math.abs(player.getX() - cx) > 0.2 || Math.abs(player.getZ() - cz) > 0.2)
                    ? 1.0f : 0.0f;
            player.xxa = 0.0f;
            player.setSprinting(false);
            // The rise itself comes from the universal liquid-float jump in the executor —
            // this branch deliberately presses no jump of its own.
            return;
        }
        player.setShiftKeyDown(true);   // sneak: never step off the column
        // Recentre on the column rather than walking off it.
        if (horizontalDistTo(mv.src) > 0.17) {
            InputDriver.stepToward(player, Vec3.atBottomCenterOf(mv.src), false);
        } else {
            InputDriver.halt(player);
            // Jump only when nearly still AND still BELOW the
            // destination — stop jumping once we've reached the top (`y < dest.y`).
            if (player.onGround() && horizontalSpeedSqr() < 0.0025
                    && player.getY() < mv.dest.getY()) {
                InputDriver.jump(player);
            }
        }
        // While airborne, attempt the underfoot place EVERY tick and let vanilla's own
        // placement rules judge legality: the ticks where our collision box still occupies
        // the cell are rejected by the placement's obstruction check, and the first tick
        // the hop lifts the box clear (feet above cell top) succeeds. No apex timing, no
        // Y-window guess — the judge is the same code that must accept the block anyway.
        if (!player.onGround()) {
            placeUnderfoot();
        }
    }

    @Override
    public boolean arrived() {
        // Water swim-up succeeds on feet==dest with NO
        // block check (you're swimming, nothing is placed).
        if (BlockHelper.isWater(player.level(), mv.src)) {
            return feet().equals(mv.dest);
        }
        // Dry tower: success = feet at dest AND the placed block
        // exists (canWalkOn(src)). WITHOUT the block check, the jump APEX — feet
        // momentarily at dest.y mid-air, before placeUnderfoot has placed anything —
        // false-arrives, advances the index, then the body falls back with nothing
        // placed and churns forever. The block check holds arrival until we've placed.
        return feet().equals(mv.dest) && BlockHelper.canWalkOn(player.level(), mv.src);
    }

    /**
     * A land pillar's whole premise is a floor under its own column (jump from it,
     * place against it). If that floor is missing — typically an earlier scaffold
     * that never landed left a hole — jumping can only grind the movement timeout.
     */
    @Override
    public String premiseBroken() {
        if (BlockHelper.isWater(player.level(), mv.src)) {
            return null;
        }
        // Fell below the column base: the jump-and-place premise is gone THIS tick —
        // waiting out the movement timeout just grinds sneak-jumps in a hole.
        if (feet().getY() < mv.src.getY()) {
            return "fell below the pillar base " + mv.src.toShortString()
                    + " (feet at y=" + feet().getY() + ")";
        }
        if (!BlockHelper.canWalkOn(player.level(), mv.src.below())
                && !BlockHelper.canWalkOn(player.level(), mv.src)) {
            return "pillar has no floor under " + mv.src.toShortString()
                    + " (an earlier scaffold never landed)";
        }
        return null;
    }

    /** A water-column swim-up is *meant* to run submerged. */
    @Override
    public boolean allowsSubmersion() {
        return BlockHelper.isWater(player.level(), mv.src);
    }

    @Override
    public void stop() {
        player.setShiftKeyDown(false);
    }

    /** Attempt a scaffold place at the column cell against the solid block directly
     *  below it, performed natively (look down + useItemOn on the up-face). Called every
     *  airborne pillar tick; vanilla accepts on whichever tick the hop has cleared the cell. */
    private void placeUnderfoot() {
        var cell = mv.src;
        int slot = scaffoldSlot(player);
        if (slot < 0) {
            underfootBlocked("no scaffold block in inventory");
            return;
        }
        if (!Placement.canPlaceAgainst(player.level(), cell.below(), Direction.UP)) {
            underfootBlocked("support below is gone");
            return;
        }
        InputDriver.lookAt(player, Vec3.atBottomCenterOf(cell));   // look straight down at the support's top
        BlockHitResult hit = Placement.resolve(player, cell, true);  // honest raycast only — no fabricated hit
        if (hit == null) {
            underfootBlocked("no clear line to own feet cell");
            return;
        }
        player.holdInHand(slot);   // real hotbar-select / swap-to-hand, not an aliasing overwrite
        Interaction use = Interaction.useBlock(player, hit, InteractionHand.MAIN_HAND);
        use.tick();
        // The press is NOT trusted as the outcome — the world is. Refusals early in the hop
        // (box still occupies the cell) are expected and retried next tick; only a persistent
        // refusal streak is a real blockage, and then the diagnostic names vanilla's verdict
        // and where the body actually was, so a dead pillar is never silent again.
        if (BlockHelper.canWalkOn(player.level(), cell)) {
            Constants.LOG.info("[numen-path] pillar place landed at {} (y={}, after {} refused airborne ticks)",
                    cell.toShortString(), String.format("%.2f", player.getY()), underfootBlockedTicks);
            underfootBlockedTicks = 0;
        } else {
            underfootBlocked(String.format("vanilla refused: %s (y=%.2f, clears cell at >%d.0)",
                    use.lastUseOutcome(), player.getY(), cell.getY() + 1));
        }
    }

    private void underfootBlocked(String gate) {
        if (++underfootBlockedTicks % 20 == 0) {
            Constants.LOG.info("[numen-path] pillar place blocked {} ticks ({}) at {}",
                    underfootBlockedTicks, gate, mv.src.toShortString());
        }
    }
}
