package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.task.BodyLog;
import com.dwinovo.numen.task.reflex.Reflex;
import com.dwinovo.numen.entity.InputDriver;

import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.SurvivalConfig;
import com.dwinovo.numen.task.TaskChain;
import com.dwinovo.numen.core.task.base.ToolSelect;
import com.dwinovo.numen.core.task.survival.SurvivalDecisions;
import com.dwinovo.numen.core.task.survival.SurvivalDecisions.ThreatResponse;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Autonomous threat-response survival chain. Polls for a hostile within a bounded
 * radius each tick (biased toward whatever last hurt the body); when one is present
 * it spikes above the LLM task and either fights back (healthy + armed) or flees
 * (too hurt, or unarmed — survival never auto-acquires a weapon). Bounded by the
 * scan radius: it engages what is near and gives up chasing anything that leaves,
 * never travelling across the world.
 *
 * <p>Drives the substrate primitives directly — {@link PlayerNav} to close on or
 * run from the mob, {@link Interaction#attackEntity} for the native cooldown-scaled
 * swing, {@link NavGoal#runAway} for the flee vector. No {@code AbstractCompanionTask}:
 * there is no result to build and the fight/flee logic is a per-tick decision, not a
 * nav-then-act script.
 *
 * <p>GATED OFF by default via {@link SurvivalConfig}.
 */
public final class MobDefenseChain implements TaskChain, com.dwinovo.numen.task.reflex.Reflex {

    /** How far to look for a threat, and the leash beyond which we abandon a chase. */
    private static final double SCAN_RADIUS = 12.0;
    /** Native player melee reach (~3 blocks). */
    private static final double ATTACK_REACH = 3.0;
    private static final double ATTACK_REACH_SQR = ATTACK_REACH * ATTACK_REACH;
    private static final double CHASE_SPEED = 1.2;
    private static final double FLEE_SPEED = 1.3;

    private enum Mode { NONE, CHASE, FLEE }

    /** Consecutive nav failures on the current engagement before the leash fires. */
    private static final int MAX_ENGAGE_FAILS = 3;
    /** How long an unreachable target is ignored / how long the whole chain cools down (ticks). */
    private static final long UNREACHABLE_COOLDOWN = 200;
    private static final long CHAIN_COOLDOWN = 100;

    /** BodyLog for completed episodes (kill / escape) — dual-rail routed (may be null in unit tests). */
    private final com.dwinovo.numen.task.BodyLog bodyLog;

    private Mode mode = Mode.NONE;
    private LivingEntity target;
    private PlayerNav nav;

    public MobDefenseChain() {
        this(null);
    }

    public MobDefenseChain(com.dwinovo.numen.task.BodyLog bodyLog) {
        this.bodyLog = bodyLog;
    }
    /** Last known threat position, for the flee goal supplier (survives the mob despawning mid-flee). */
    private BlockPos lastThreatPos;
    /** Engagement leash: consecutive nav FAILEDs on the current fight/flee attempt. */
    private int consecutiveNavFails;
    /** Targets we provably can't path to, ignored until the stored gameTime (entity id → until). */
    private final java.util.Map<Integer, Long> unreachable = new java.util.HashMap<>();
    /** Whole-chain cooldown after a failed (boxed-in) flee — hands the body back to the LLM. */
    private long cooldownUntilGameTime;

    @Override
    public float getPriority(NumenPlayer companion) {
        if (!SurvivalConfig.enabled()) return Float.NEGATIVE_INFINITY;
        if (!com.dwinovo.numen.task.reflex.ReflexRegistry.enabled(id())) {
            return SurvivalDecisions.DORMANT;   // reflex switched off by the owner
        }
        // Leash cooldown: we recently proved we can neither reach nor escape the
        // threat — stop spiking so the LLM task resumes (and its deadline can run)
        // instead of holding the body forever while freezeTick pushes the deadline.
        if (companion.level().getGameTime() < cooldownUntilGameTime) return Float.NEGATIVE_INFINITY;
        return SurvivalDecisions.mobDefensePriority(nearestThreat(companion) != null);
    }

    @Override
    public void tick(NumenPlayer companion) {
        LivingEntity threat = nearestThreat(companion);
        if (threat == null) {
            release(companion);
            return;
        }
        if (threat != target) {
            noteOutcome(companion);   // the previous engagement just ended (e.g. target died)
            target = threat;
            consecutiveNavFails = 0;
            stopNav();   // re-plan for the new target
        }
        lastThreatPos = threat.blockPosition();

        ThreatResponse resp = SurvivalDecisions.decideThreatResponse(
                true, companion.getHealth(), hasWeapon(companion));   // pure carry-check; fight() arms
        if (resp == ThreatResponse.FIGHT) {
            fight(companion, threat);
        } else {
            flee(companion);
        }
    }

    @Override
    public void onInterrupt(NumenPlayer companion) {
        release(companion);
    }

    @Override
    public String name() {
        return "mob_defense";
    }

    // ---- Reflex roster paperwork (constitution §6) ----

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "被怪物攻击会反击,受伤太重或没武器就先逃开";
    }

    // ---- fight ----

    private void fight(NumenPlayer companion, LivingEntity threat) {
        if (mode != Mode.CHASE) {
            stopNav();
            mode = Mode.CHASE;
        }
        ToolSelect.holdBestWeapon(companion);   // pathfinder may have swapped a block into the hand
        if (inReach(companion, threat)) {
            stopNav();
            consecutiveNavFails = 0;
            // A fresh once() per tick: it aims, then attacks iff the native attack
            // cooldown has recovered (else soft-waits). The cooldown lives on the
            // player, so recreating the interaction each tick is stateless and safe.
            Interaction.attackEntity(companion, threat).tick();
            return;
        }
        if (nav == null) {
            nav = new PlayerNav(companion, threat::blockPosition, CHASE_SPEED,
                    () -> inReach(companion, threat));
        }
        switch (nav.tick()) {
            case RUNNING, ARRIVED -> { /* closing distance */ }
            case FAILED -> {
                stopNav();
                // Leash: a skeleton on an unreachable ledge would otherwise hold this
                // chain (and freeze the LLM task's deadline) FOREVER. After a few
                // provably-failed plans, ignore that target for a while.
                if (++consecutiveNavFails >= MAX_ENGAGE_FAILS) {
                    unreachable.put(threat.getId(),
                            companion.level().getGameTime() + UNREACHABLE_COOLDOWN);
                    consecutiveNavFails = 0;
                    target = null;   // re-scan picks another threat, or none → dormant
                }
            }
        }
    }

    // ---- flee ----

    private void flee(NumenPlayer companion) {
        if (mode != Mode.FLEE) {
            stopNav();
            mode = Mode.FLEE;
        }
        if (nav == null) {
            int maintainY = companion.blockPosition().getY();
            nav = PlayerNav.toGoal(companion,
                    () -> NavGoal.runAway(lastThreatPos, maintainY),
                    FLEE_SPEED,
                    () -> false);   // never "arrived" — keep running until the threat clears
        }
        if (nav.tick() == PlayerNav.Status.FAILED) {
            stopNav();
            // Boxed in with no escape plan: after a few failed attempts, stop
            // spiking for a while — holding the body helps nobody, and the LLM
            // (whose deadline resumes) may know a better way out.
            if (++consecutiveNavFails >= MAX_ENGAGE_FAILS) {
                cooldownUntilGameTime = companion.level().getGameTime() + CHAIN_COOLDOWN;
                consecutiveNavFails = 0;
                release(companion);
            }
        }
    }

    // ---- threat detection ----

    /**
     * Nearest live hostile within {@link #SCAN_RADIUS}, preferring whatever last hurt
     * the body if it is still in range. Returns {@code null} when nothing hostile is
     * near — the chain's only actionable, bounded threat signal.
     */
    private LivingEntity nearestThreat(NumenPlayer companion) {
        AABB box = companion.getBoundingBox().inflate(SCAN_RADIUS);
        LivingEntity attacker = companion.getLastHurtByMob();
        long now = companion.level().getGameTime();
        LivingEntity best = null;
        double bestDistSqr = Double.MAX_VALUE;
        for (Monster m : companion.level().getEntitiesOfClass(Monster.class, box)) {
            if (m.isRemoved() || m.isDeadOrDying()) continue;
            // DEFENSE, not aggression: only a mob that is actually engaging us — it hurt
            // us, or its AI has targeted us — counts. A neutral Monster (a calm zombified
            // piglin drifting by) must not be attacked and provoked by a "defense" chain.
            if (m != attacker && m.getTarget() != companion) continue;
            // Skip targets we recently proved unreachable (the engagement leash).
            if (unreachable.getOrDefault(m.getId(), 0L) > now) continue;
            double d = companion.distanceToSqr(m);
            if (d > SCAN_RADIUS * SCAN_RADIUS) continue;
            // Bias toward the mob that hurt us: pretend it is closer so it wins ties.
            double weighted = (m == attacker) ? d - 1.0 : d;
            if (weighted < bestDistSqr) {
                bestDistSqr = weighted;
                best = m;
            }
        }
        return best;
    }

    /** Does the body CARRY a melee weapon anywhere in inventory? Pure check — no hand
     *  mutation; the swap happens only once FIGHT is actually chosen (a fleeing body
     *  must not have its held tool silently replaced by a probe). */
    private static boolean hasWeapon(NumenPlayer companion) {
        var inv = companion.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (stackAttackBonus(inv.getItem(i)) > 0.0) return true;
        }
        return false;
    }

    /** Flat main-hand attack-damage a stack grants (mirrors {@code ToolSelect.weaponDamage}). */
    private static double stackAttackBonus(ItemStack stack) {
        if (stack.isEmpty()) return 0.0;
        ItemAttributeModifiers mods = stack.getOrDefault(
                DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double sum = 0.0;
        for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
            if (e.slot().test(EquipmentSlot.MAINHAND)
                    && e.attribute().is(Attributes.ATTACK_DAMAGE)
                    && e.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
                sum += e.modifier().amount();
            }
        }
        return sum;
    }

    private boolean inReach(NumenPlayer companion, LivingEntity threat) {
        return companion.distanceToSqr(Vec3.atCenterOf(threat.blockPosition())) <= ATTACK_REACH_SQR
                && companion.hasLineOfSight(threat);
    }

    private void stopNav() {
        if (nav != null) {
            nav.stop();
            nav = null;
        }
    }

    private void release(NumenPlayer companion) {
        noteOutcome(companion);
        stopNav();
        InputDriver.halt(companion);
        companion.setShiftKeyDown(false);
        mode = Mode.NONE;
        target = null;
    }

    /** Diary the engagement that just ended — a kill (we chased and it died) or a clean
     *  escape (we fled and nothing hostile remains in range). Anything else (preempted
     *  mid-fight, leashed unreachable) is not an outcome worth a line. */
    private void noteOutcome(NumenPlayer companion) {
        if (bodyLog == null || target == null) return;
        String mob = target.getType().getDescription().getString();
        if (mode == Mode.CHASE && (target.isDeadOrDying() || target.isRemoved())) {
            bodyLog.report("was attacked by a " + mob + " and killed it");
        } else if (mode == Mode.FLEE && nearestThreat(companion) == null) {
            bodyLog.report("fled from a " + mob + " to safety");
        }
    }
}
