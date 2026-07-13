package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.pathing.exec.Interaction;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.CountedProgress;
import com.dwinovo.numen.core.task.base.TargetSet;
import com.dwinovo.numen.core.task.base.ToolSelect;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code hunt} on the player body: find / chase / fight N mobs. The combat twin
 * of {@code HuntTaskGoal}, but melee is the player's NATIVE attack
 * ({@code player.attack} with real cooldown / weapon modifiers / sweep / crit)
 * instead of the Mob's MeleeEngine — and the chase is {@link PlayerNav}.
 */
public final class HuntCompanionTask extends AbstractCompanionTask<HuntTaskRecord> {

    private enum Phase { SCAN, ENGAGE, COLLECT }

    private static final int INITIAL_RADIUS = 24;
    private static final int RADIUS_STEP = 16;
    private static final double CHASE_SPEED = 1.2;
    /** Melee strike range — vanilla player entity-interaction reach ≈ 3 blocks. */
    private static final double ATTACK_REACH = 3.0;
    private static final double ATTACK_REACH_SQR = ATTACK_REACH * ATTACK_REACH;
    /** Post-hunt loot sweep: radius scanned for mob drops, and a tick budget so it can't stall. */
    private static final int COLLECT_RADIUS = 24;
    private static final int MAX_COLLECT_TICKS = 300;   // ~15 s

    /** Mobs A* couldn't close on — skipped so the scan doesn't retry the same one forever. */
    private final TargetSet<LivingEntity> skipped = new TargetSet<>(LivingEntity::getId);
    /** How many of those skips happened ({@code TargetSet} keeps no count) — so the final
     *  "nothing left to hunt" message can tell the LLM "N targets were there but unreachable". */
    private int unreachableSkips;
    /** Drops A* can't reach — skipped so the sweep doesn't retry the same one forever. */
    private final TargetSet<BlockPos> dropBlacklist = new TargetSet<>(p -> p);

    private CountedProgress progress;
    private Phase phase = Phase.SCAN;
    private int currentRadius;
    private int collectTicks;
    private LivingEntity target;
    /** The success parenthetical / partial-progress note (drives {@link #successMessage()}). */
    private String note = "done";

    public HuntCompanionTask(NumenPlayer player, HuntTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        currentRadius = Math.min(INITIAL_RADIUS, r.maxRadius);
        phase = Phase.SCAN;
        progress = new CountedProgress(r.count, r::getKilled);
    }

    @Override
    protected TaskState onTick() {
        if (player.isDeadOrDying()) {
            return TaskState.CANCELLED;
        }
        return switch (phase) {
            case SCAN -> tickScan();
            case ENGAGE -> tickEngage();
            case COLLECT -> tickCollect();
        };
    }

    private TaskState tickScan() {
        if (progress.done()) {
            note = "hunted all requested";
            beginCollect();
            return TaskState.RUNNING;
        }
        LivingEntity best = nearestTarget();
        if (best == null) {
            if (currentRadius < r.maxRadius) {
                currentRadius = Math.min(currentRadius + RADIUS_STEP, r.maxRadius);
                return TaskState.RUNNING;
            }
            if (r.getKilled() > 0) {
                note = "only killed " + r.getKilled() + "/" + r.count + " within " + r.maxRadius + " blocks";
                beginCollect();   // sweep the battlefield for loot before finishing
                return TaskState.RUNNING;
            }
            // Structured give-up for the LLM: what was scanned (the task's full radius) and
            // how many candidates existed but couldn't be closed on. Same condition as before —
            // only the message content is richer.
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
        // Arrive = in reach AND a clear line of sight, so we close around a wall rather than
        // standing behind it swinging at nothing.
        nav = new PlayerNav(player, this::targetCell, CHASE_SPEED, this::inReachAndLos);
        phase = Phase.ENGAGE;
        return TaskState.RUNNING;
    }

    private TaskState tickEngage() {
        if (target == null || target.isRemoved()) {
            stopNav();
            phase = Phase.SCAN;
            return TaskState.RUNNING;
        }
        if (target.isDeadOrDying()) {
            r.incrementKilled();
            target = null;
            stopNav();
            phase = Phase.SCAN;
            return TaskState.RUNNING;
        }
        switch (nav.tick()) {
            case RUNNING -> { /* closing distance */ }
            case ARRIVED -> swing();
            case FAILED -> {
                skipped.skip(target);
                unreachableSkips++;
                target = null;
                stopNav();
                phase = Phase.SCAN;
            }
        }
        return TaskState.RUNNING;
    }

    /** Done fighting — switch to a post-hunt loot sweep. Mirrors auto_mine's drop collection
     *  (scan nearby item drops, walk over them so native player pickup grabs them) so a finished
     *  hunt leaves loot in the pack instead of on the ground. */
    private void beginCollect() {
        stopNav();
        collectTicks = 0;
        phase = Phase.COLLECT;
    }

    private TaskState tickCollect() {
        if (nearbyDrops().isEmpty() || ++collectTicks > MAX_COLLECT_TICKS) {
            stopNav();
            return TaskState.SUCCESS;
        }
        if (nav == null) {
            nav = PlayerNav.toGoal(player, this::collectGoal, CHASE_SPEED, () -> nearbyDrops().isEmpty());
        }
        switch (nav.tick()) {
            case RUNNING -> { /* walking onto the next drop; native pickup collects it */ }
            case ARRIVED -> { stopNav(); return TaskState.SUCCESS; }   // nothing left in range
            case FAILED -> {                                           // nearest drop unreachable —
                blacklistNearestDrop();                                // skip it and retry the rest
                stopNav();
            }
        }
        return TaskState.RUNNING;
    }

    /** Nearby dropped items to sweep up after the fight (auto_mine's droppedItemsScan, with a wider
     *  radius because mobs die spread across the engagement). Unreachable (blacklisted) ones excluded. */
    private List<BlockPos> nearbyDrops() {
        AABB box = player.getBoundingBox().inflate(COLLECT_RADIUS);
        List<BlockPos> out = new ArrayList<>();
        for (ItemEntity ie : player.level().getEntitiesOfClass(ItemEntity.class, box)) {
            if (ie.isRemoved()) continue;
            BlockPos p = ie.blockPosition();
            if (dropBlacklist.isExcluded(p)) continue;
            out.add(p);
        }
        return out;
    }

    /** GoalComposite over every nearby drop — one A* heads for the closest reachable (auto_mine pattern). */
    private NavGoal collectGoal() {
        List<BlockPos> drops = nearbyDrops();
        if (drops.isEmpty()) return NavGoal.exact(player.blockPosition());
        List<NavGoal> goals = new ArrayList<>(drops.size());
        for (BlockPos d : drops) goals.add(NavGoal.near(d, 1.0));   // walk over it; native pickup grabs it
        return NavGoal.composite(goals);
    }

    private void blacklistNearestDrop() {
        BlockPos feet = player.blockPosition();
        nearbyDrops().stream()
                .min(Comparator.comparingDouble(feet::distSqr))
                .ifPresent(dropBlacklist::blacklist);
    }

    /** Native melee swing: aim, fire one crosshair raytrace, and only strike when it actually
     *  resolves to THIS target (a wall / another mob in the line is not hit through), the sprint
     *  is dropped (so it sweeps and doesn't knock the mob away into another chase), and the attack
     *  cooldown has recovered (full-charge damage). */
    private void swing() {
        if (target == null) return;
        ToolSelect.holdBestWeapon(player);   // pathfinder may have swapped a scaffold block into the hand while bridging
        InputDriver.lookAt(player, target.getEyePosition());
        HitResult hit = Interaction.nativeRaytrace(player, ATTACK_REACH);
        boolean onTarget = hit.getType() == HitResult.Type.ENTITY
                && ((EntityHitResult) hit).getEntity() == target;
        if (!onTarget) {
            return;   // not actually looking at the target this tick — re-aim next tick
        }
        player.setSprinting(false);       // sweep + no knockback-chase
        if (player.getAttackStrengthScale(0.0f) >= 0.95f) {
            player.attack(target);        // real damage / cooldown / sweep / knockback / crit
            player.resetAttackStrengthTicker();
            player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
    }

    private BlockPos targetCell() {
        return (target != null && !target.isRemoved()) ? target.blockPosition() : null;
    }

    private boolean inReachAndLos() {
        return target != null
                && player.distanceToSqr(Vec3.atCenterOf(target.blockPosition())) <= ATTACK_REACH_SQR
                && player.hasLineOfSight(target);
    }

    private LivingEntity nearestTarget() {
        AABB box = player.getBoundingBox().inflate(currentRadius);
        List<LivingEntity> candidates = new ArrayList<>();
        for (Entity e : player.level().getEntities(player, box)) {
            if (e == player || e.isRemoved()) continue;
            if (!(e instanceof LivingEntity le) || le.isDeadOrDying()) continue;
            if (!r.targets.contains(e.getType())) continue;
            candidates.add(le);
        }
        return skipped.pick(candidates, Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("target", r.label);
        data.put("requested", r.count);
        data.put("killed", r.getKilled());
        return data;
    }

    @Override
    protected String successMessage() {
        return "killed " + r.getKilled() + "/" + r.count + " " + r.label + " (" + note + ")";
    }

    @Override
    protected String timeoutMessage() {
        return "timed out after killing " + r.getKilled() + "/" + r.count + " " + r.label;
    }

    @Override
    protected String cancelledMessage() {
        return "interrupted after killing " + r.getKilled() + "/" + r.count + " " + r.label;
    }
}
