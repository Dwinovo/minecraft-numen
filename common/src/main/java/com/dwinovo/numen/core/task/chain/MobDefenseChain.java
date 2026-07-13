package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.pathing.exec.Interaction;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.SurvivalConfig;
import com.dwinovo.numen.core.task.TaskChain;
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
public final class MobDefenseChain implements TaskChain {

    /** How far to look for a threat, and the leash beyond which we abandon a chase. */
    private static final double SCAN_RADIUS = 12.0;
    /** Native player melee reach (~3 blocks). */
    private static final double ATTACK_REACH = 3.0;
    private static final double ATTACK_REACH_SQR = ATTACK_REACH * ATTACK_REACH;
    private static final double CHASE_SPEED = 1.2;
    private static final double FLEE_SPEED = 1.3;

    private enum Mode { NONE, CHASE, FLEE }

    private Mode mode = Mode.NONE;
    private LivingEntity target;
    private PlayerNav nav;
    /** Last known threat position, for the flee goal supplier (survives the mob despawning mid-flee). */
    private BlockPos lastThreatPos;

    @Override
    public float getPriority(NumenPlayer companion) {
        if (!SurvivalConfig.enabled()) return Float.NEGATIVE_INFINITY;
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
            target = threat;
            stopNav();   // re-plan for the new target
        }
        lastThreatPos = threat.blockPosition();

        ThreatResponse resp = SurvivalDecisions.decideThreatResponse(
                true, companion.getHealth(), hasWeapon(companion));
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

    // ---- fight ----

    private void fight(NumenPlayer companion, LivingEntity threat) {
        if (mode != Mode.CHASE) {
            stopNav();
            mode = Mode.CHASE;
        }
        ToolSelect.holdBestWeapon(companion);   // pathfinder may have swapped a block into the hand
        if (inReach(companion, threat)) {
            stopNav();
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
            case FAILED -> stopNav();   // unreachable this tick; re-plan next (bounded by re-scan)
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
            stopNav();   // boxed in; re-plan next tick
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
        LivingEntity best = null;
        double bestDistSqr = Double.MAX_VALUE;
        for (Monster m : companion.level().getEntitiesOfClass(Monster.class, box)) {
            if (m.isRemoved() || m.isDeadOrDying()) continue;
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

    private boolean hasWeapon(NumenPlayer companion) {
        // holdBestWeapon ranks the whole inventory by melee attack damage and swaps it
        // into the hand; the body is "armed" iff that best item actually grants a
        // main-hand ATTACK_DAMAGE bonus (an empty hand / block / food scores nothing).
        ToolSelect.holdBestWeapon(companion);
        return mainHandAttackBonus(companion) > 0.0;
    }

    /** Flat main-hand attack-damage the held item grants (mirrors {@code ToolSelect.weaponDamage}). */
    private static double mainHandAttackBonus(NumenPlayer companion) {
        ItemStack stack = companion.getMainHandItem();
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
        stopNav();
        InputDriver.halt(companion);
        companion.setShiftKeyDown(false);
        mode = Mode.NONE;
        target = null;
    }
}
