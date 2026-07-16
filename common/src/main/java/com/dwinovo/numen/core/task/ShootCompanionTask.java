package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.entity.InputDriver;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.Ballistics;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.CountedProgress;
import com.dwinovo.numen.core.task.base.Precondition;
import com.dwinovo.numen.core.task.base.TargetSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code shoot} on the player body: destroy N targets at range with whatever ranged weapon is in the
 * main hand, via the player's NATIVE use path (real projectile physics + ammo).
 *
 * <p><b>Weapon-agnostic firing.</b> Rather than special-casing the bow's constants, each shot is a
 * tiny state machine driven by observable native state. The only mechanic split is draw-vs-load,
 * taken from the data-driven {@link ItemStack#getUseAnimation()} (modded bows/crossbows declare it):
 * a CROSSBOW-style weapon is held until it LOADS ({@link CrossbowItem#isCharged}) then tapped to
 * fire; everything else (bow / trident / modded charge-bow) is drawn to full power then released. The
 * shot is confirmed by an actual projectile leaving with us as its owner — so infinity bows, modded
 * ammo, and pre-loaded crossbows all work, and a weapon that fires NOTHING is reported rather than
 * silently looped. The aim arc is calibrated from the real launch speed of the previous shot, so it
 * tracks any projectile/ammo without a hard-coded velocity.
 */
public final class ShootCompanionTask extends AbstractCompanionTask<ShootTaskRecord> {

    private enum Phase { SCAN, ENGAGE }

    private static final int INITIAL_RADIUS = 32;
    private static final int RADIUS_STEP = 16;
    private static final double APPROACH_SPEED = 1.1;
    private static final double FIRING_RANGE_SQR = 18.0 * 18.0;

    /** Per-tick gravity on a launched arrow (vanilla AbstractArrow). */
    private static final double ARROW_G = 0.05;
    /** First-shot launch-speed guess (≈ a full-draw bow arrow); recalibrated from real shots after. */
    private static final double DEFAULT_V = 3.0;
    /** Draw-style: hold this long before releasing (vanilla full draw is 20; a little margin). */
    private static final int BOW_DRAW_TICKS = 22;
    /** Load-style safety cap: stop waiting for a crossbow to charge after this (Quick Charge is faster). */
    private static final int LOAD_TIMEOUT = 80;
    /** Ticks to wait for the fired projectile to register before calling a shot a misfire. */
    private static final int SETTLE_TICKS = 4;
    /** Give up on the weapon after this many shots that produced no projectile. */
    private static final int MAX_MISFIRES = 2;

    private final TargetSet<Entity> skipped = new TargetSet<>(Entity::getId);
    /** How many of those skips happened ({@code TargetSet} keeps no count) — so the final
     *  "nothing left to shoot" message can tell the LLM "N targets were there but unreachable". */
    private int unreachableSkips;

    private CountedProgress progress;
    private Phase phase = Phase.SCAN;
    private int currentRadius;
    private Entity target;
    private Shot shot;            // the in-progress firing sequence (null between shots)
    private double launchV = DEFAULT_V;   // calibrated from the last projectile's real speed
    private int misfires;
    /** The success parenthetical / partial-progress note (drives {@link #successMessage()}). */
    private String note = "done";

    public ShootCompanionTask(NumenPlayer player, ShootTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        return List.of(
                () -> player.getMainHandItem().getItem() instanceof ProjectileWeaponItem ? null
                        : new Precondition.Failure(
                                "equip a ranged weapon (bow / crossbow) in your main hand first (equip_item)",
                                FailureType.WRONG_TOOL),
                () -> !player.getProjectile(player.getMainHandItem()).isEmpty() ? null
                        : new Precondition.Failure("you have no ammo — craft or obtain arrows first",
                                FailureType.NO_MATERIAL));
    }

    @Override
    protected void onStart() {
        currentRadius = Math.min(INITIAL_RADIUS, r.maxRadius);
        phase = Phase.SCAN;
        progress = new CountedProgress(r.count, r::getDestroyed);
    }

    @Override
    protected TaskState onTick() {
        if (player.isDeadOrDying()) {
            return TaskState.CANCELLED;
        }
        return switch (phase) {
            case SCAN -> tickScan();
            case ENGAGE -> tickEngage();
        };
    }

    private TaskState tickScan() {
        if (progress.done()) {
            note = "destroyed all requested";
            return TaskState.SUCCESS;
        }
        Entity best = nearestTarget();
        if (best == null) {
            if (currentRadius < r.maxRadius) {
                currentRadius = Math.min(currentRadius + RADIUS_STEP, r.maxRadius);
                return TaskState.RUNNING;
            }
            if (r.getDestroyed() > 0) {
                note = "only destroyed " + r.getDestroyed() + "/" + r.count + " within " + r.maxRadius + " blocks";
                return TaskState.SUCCESS;
            }
            // Structured give-up for the LLM: what was scanned (the task's full radius) and
            // how many candidates existed but no firing position could be reached. Same
            // condition as before — only the message content is richer.
            String scanned = "no " + r.label + " found within " + r.maxRadius + " blocks";
            if (unreachableSkips > 0) {
                scanned += " (" + unreachableSkips + " candidate"
                        + (unreachableSkips == 1 ? " was" : "s were")
                        + " skipped as unreachable)";
            }
            fail(scanned, FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        target = best;
        abortShot();
        nav = new PlayerNav(player, this::targetCell, APPROACH_SPEED, this::inFiringPosition);
        phase = Phase.ENGAGE;
        return TaskState.RUNNING;
    }

    private TaskState tickEngage() {
        if (target == null) {
            phase = Phase.SCAN;
            return TaskState.RUNNING;
        }
        if (target.isRemoved() || (target instanceof LivingEntity le && le.isDeadOrDying())) {
            r.incrementDestroyed();
            abortShot();
            target = null;
            stopNav();
            phase = Phase.SCAN;
            return TaskState.RUNNING;
        }
        if (player.getProjectile(player.getMainHandItem()).isEmpty()) {
            abortShot();
            stopNav();
            if (r.getDestroyed() > 0) {
                note = "ran out of ammo after " + r.getDestroyed() + "/" + r.count;
                return TaskState.SUCCESS;
            }
            fail("ran out of ammo after " + r.getDestroyed() + "/" + r.count, FailureType.NO_MATERIAL);
            return TaskState.FAILED;
        }
        switch (nav.tick()) {
            // Closing (or the target left the firing window mid-draw): abandon any half-draw so the
            // weapon isn't carried around held, and re-draw fresh once back in position.
            case RUNNING -> {
                abortShot();
                return TaskState.RUNNING;
            }
            case ARRIVED -> {
                return fireAtTarget();
            }
            case FAILED -> {
                abortShot();
                skipped.skip(target);
                unreachableSkips++;
                target = null;
                stopNav();
                phase = Phase.SCAN;
                return TaskState.RUNNING;
            }
        }
        return TaskState.RUNNING;
    }

    /** Aim the calibrated arc at the target (re-aimed every tick, tracking movement) and advance the
     *  native firing sequence. A resolved shot that launched nothing counts as a misfire; too many
     *  means the weapon's firing mechanic isn't one we can drive. */
    private TaskState fireAtTarget() {
        InputDriver.halt(player);   // stand still through the draw/charge instead of drifting
        InputDriver.lookAt(player, Ballistics.aimPoint(player.getEyePosition(), centerOf(target), launchV, ARROW_G));
        holdRangedWeapon();   // the pathfinder may have swapped a scaffold block into the hand while closing in
        if (shot == null) {
            shot = new Shot();
        }
        if (shot.tick()) {
            boolean fired = shot.fired();
            shot = null;
            if (!fired && ++misfires >= MAX_MISFIRES) {
                abortShot();
                stopNav();
                String why = "the weapon in hand didn't fire a projectile — it may need a special action "
                        + "this tool can't drive (it handles bows and crossbows)";
                if (r.getDestroyed() > 0) {
                    note = why;
                    return TaskState.SUCCESS;
                }
                // Same condition as before; the terminal WRONG_TOOL message additionally names
                // the item that misfired so the LLM knows exactly what was in hand.
                String weapon = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(player.getMainHandItem().getItem()).getPath();
                fail("the weapon in hand (" + weapon + ") fired no projectile in " + MAX_MISFIRES
                        + " attempts — it may need a special action this tool can't drive "
                        + "(it handles bows and crossbows)", FailureType.WRONG_TOOL);
                return TaskState.FAILED;
            }
        }
        return TaskState.RUNNING;
    }

    /** One native firing sequence for a single shot: start the use, then either release (draw-style)
     *  or load-and-tap (crossbow-style), confirmed by an owned projectile actually leaving. */
    private final class Shot {

        private enum St { CHARGING, SETTLING, DONE, MISFIRE }

        private final boolean loadStyle;        // crossbow: hold to load, then tap; else draw + release
        private final Set<Integer> before;      // owned projectiles that existed before this shot
        private St st = St.CHARGING;
        private int held;
        private int settle;

        Shot() {
            ItemStack weapon = player.getMainHandItem();
            this.loadStyle = weapon.getUseAnimation() == UseAnim.CROSSBOW;
            this.before = ownedProjectileIds();
            // Begin the action: a pre-loaded crossbow or an instant weapon fires now; a bow/crossbow
            // starts its draw/charge. Aim was set by the caller this same tick.
            player.gameMode.useItem(player, player.level(), weapon, InteractionHand.MAIN_HAND);
        }

        /** @return true once the shot resolves (fired or misfired); {@link #fired()} says which. */
        boolean tick() {
            Entity launched = firstNewOwnedProjectile(before);
            if (launched != null) {          // a projectile left with us as owner — this is our shot
                calibrateFrom(launched);
                st = St.DONE;
                return true;
            }
            switch (st) {
                case CHARGING -> {
                    ItemStack weapon = player.getMainHandItem();
                    if (loadStyle) {
                        if (CrossbowItem.isCharged(weapon)) {        // loaded → tap to fire
                            player.gameMode.useItem(player, player.level(), weapon, InteractionHand.MAIN_HAND);
                            st = St.SETTLING;
                        } else if (!player.isUsingItem() || ++held >= LOAD_TIMEOUT) {
                            if (player.isUsingItem()) {
                                player.stopUsingItem();
                            }
                            st = St.SETTLING;                        // stopped without loading / timed out
                        }
                    } else {
                        if (!player.isUsingItem()) {
                            st = St.SETTLING;                        // released early / instant weapon
                        } else if (++held >= BOW_DRAW_TICKS) {
                            player.releaseUsingItem();               // full draw → loose
                            st = St.SETTLING;
                        }
                    }
                }
                case SETTLING -> {
                    if (++settle >= SETTLE_TICKS) {                  // no projectile registered → misfire
                        st = St.MISFIRE;
                        return true;
                    }
                }
                default -> {
                    return true;
                }
            }
            return false;
        }

        boolean fired() {
            return st == St.DONE;
        }

        void abort() {
            if (player.isUsingItem()) {
                player.stopUsingItem();
            }
        }
    }

    /** Recalibrate the aim's launch speed from a real fired projectile, so the arc fits this exact
     *  weapon + ammo on the next shot (ignoring the just-spawned near-zero frames). */
    private void calibrateFrom(Entity projectile) {
        double v = projectile.getDeltaMovement().length();
        if (v > 0.5) {
            launchV = v;
        }
    }

    private Set<Integer> ownedProjectileIds() {
        Set<Integer> ids = new HashSet<>();
        for (Entity e : player.level().getEntities(player, player.getBoundingBox().inflate(8.0))) {
            if (e instanceof Projectile pr && pr.getOwner() == player) {
                ids.add(e.getId());
            }
        }
        return ids;
    }

    private Entity firstNewOwnedProjectile(Set<Integer> before) {
        for (Entity e : player.level().getEntities(player, player.getBoundingBox().inflate(8.0))) {
            if (e instanceof Projectile pr && pr.getOwner() == player && !before.contains(e.getId())) {
                return e;
            }
        }
        return null;
    }

    /** Make sure a ranged weapon is in the main hand before drawing — the pathfinder selects a scaffold
     *  block to bridge and doesn't restore the hand, which would otherwise leave the body "firing" a
     *  cobblestone (no projectile → misfire). Only acts when the hand ISN'T already a ranged weapon, so
     *  it keeps whichever bow/crossbow the owner equipped rather than second-guessing the choice. */
    private void holdRangedWeapon() {
        Inventory inv = player.getInventory();
        if (inv.getItem(inv.selected).getItem() instanceof ProjectileWeaponItem) return;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() instanceof ProjectileWeaponItem) {
                player.holdInHand(i);
                return;
            }
        }
        // no ranged weapon left in the pack — leave the hand; the ammo / misfire checks report the failure
    }

    private void abortShot() {
        if (shot != null) {
            shot.abort();
            shot = null;
        }
    }

    /** Centre mass of the target (a solid body hit, not the very top). */
    private Vec3 centerOf(Entity e) {
        return e.position().add(0.0, e.getBbHeight() * 0.5, 0.0);
    }

    private BlockPos targetCell() {
        return (target != null && !target.isRemoved()) ? target.blockPosition() : null;
    }

    private boolean inFiringPosition() {
        return target != null
                && player.distanceToSqr(target) <= FIRING_RANGE_SQR
                && player.hasLineOfSight(target);
    }

    private Entity nearestTarget() {
        AABB box = player.getBoundingBox().inflate(currentRadius);
        List<Entity> candidates = new ArrayList<>();
        for (Entity e : player.level().getEntities(player, box)) {
            if (e == player || e.isRemoved()) continue;
            if (!r.targets.contains(e.getType())) continue;
            if (e instanceof LivingEntity le && le.isDeadOrDying()) continue;
            candidates.add(e);
        }
        return skipped.pick(candidates, Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
    }

    /** Release the firing sequence, then the nav + overlay (base default). */
    @Override
    protected void cleanup() {
        abortShot();
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("target", r.label);
        data.put("requested", r.count);
        data.put("destroyed", r.getDestroyed());
        return data;
    }

    @Override
    protected String successMessage() {
        return "destroyed " + r.getDestroyed() + "/" + r.count + " " + r.label + " (" + note + ")";
    }

    @Override
    protected String timeoutMessage() {
        return "timed out after destroying " + r.getDestroyed() + "/" + r.count + " " + r.label;
    }

    @Override
    protected String cancelledMessage() {
        return "interrupted after destroying " + r.getDestroyed() + "/" + r.count + " " + r.label;
    }
}
