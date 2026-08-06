package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.task.reflex.Reflex;
import com.dwinovo.numen.entity.InputDriver;

import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.core.combat.AttackPlan;
import com.dwinovo.numen.core.combat.Loadout;
import com.dwinovo.numen.core.combat.Menace;
import com.dwinovo.numen.core.combat.WeaponDamage;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.core.task.survival.SurvivalDecisions;
import com.dwinovo.numen.core.task.survival.SurvivalDecisions.ThreatResponse;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
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
 */
public final class MobDefenseChain implements Task, com.dwinovo.numen.task.reflex.Reflex {

    /** How far to look for a threat, and the leash beyond which we abandon a chase. */
    private static final double SCAN_RADIUS = 12.0;
    /** Native player melee reach (~3 blocks). */
    private static final double ATTACK_REACH = 3.0;
    private static final double ATTACK_REACH_SQR = ATTACK_REACH * ATTACK_REACH;
    private static final double CHASE_SPEED = 1.2;
    private static final double FLEE_SPEED = 1.3;

    private enum Mode { NONE, CHASE, FLEE, AVOID }

    /** Consecutive nav failures on the current engagement before the leash fires. */
    private static final int MAX_ENGAGE_FAILS = 3;
    /** How long an unreachable target is ignored / how long the whole chain cools down (ticks). */
    private static final long UNREACHABLE_COOLDOWN = 200;
    private static final long CHAIN_COOLDOWN = 100;


    private Mode mode = Mode.NONE;
    private LivingEntity target;
    private PlayerNav nav;

    public MobDefenseChain() {
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
    public boolean canRun(NumenPlayer companion) {
        if (companion.level().getGameTime() < cooldownUntilGameTime) return false;
        return SurvivalDecisions.mobDefenseTriggered(nearestThreat(companion) != null);
    }

    @Override
    public TaskState tick(NumenPlayer companion) {
        LivingEntity threat = nearestThreat(companion);
        if (threat == null) {
            release(companion);
            return TaskState.RUNNING;
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
        if (resp != ThreatResponse.FIGHT) {
            flee(companion);
            return TaskState.RUNNING;
        }
        switch (AttackPlan.decide(new AttackPlan.Situation(
                companion.distanceTo(threat),
                ATTACK_REACH,
                consecutiveNavFails < MAX_ENGAGE_FAILS,
                Loadout.forTarget(companion, threat).hasRanged(),
                Menace.keepAwayFrom(threat),
                Menace.safeDistanceFrom(threat)))) {
            // 够不够得着由 fight() 内部照旧判(它还要看视线);判据在这里只回答打不打。
            case MELEE, CLOSE_IN -> fight(companion, threat);
            // 本能的职责是别死,不是打赢。够不着、或不该贴上去,就拉开距离——真要打它,
            // 让模型派 attack,那条路上才有弹道与拉弓。
            case RANGED, AVOID, ABANDON -> keepAway(companion, threat);
        }
        return TaskState.RUNNING;
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
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
        return "被怪物攻击会反击;受伤太重或没武器就先逃开;会炸的东西一律拉开距离";
    }

    // ---- fight ----

    private void fight(NumenPlayer companion, LivingEntity threat) {
        if (mode != Mode.CHASE) {
            stopNav();
            mode = Mode.CHASE;
        }
        // 按"对这只怪最狠"选,不是按攻击力常数选(亡灵杀手打僵尸强过一把更好的光板剑)。
        // 顺带也修回寻路途中被换进手里的方块。
        var melee = Loadout.forTarget(companion, threat).melee();
        if (melee != null) companion.holdInHand(melee.slot());
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

    // ---- keep away ----

    /**
     * 与它保持距离(爬行者、末影水晶)。走带权重的势场目标——它认得完所有威胁,而且
     * <b>有终点</b>:退到安全线就停,不必每 tick 判断"退够没有",也就不会在边界上抖。
     */
    private void keepAway(NumenPlayer companion, LivingEntity threat) {
        if (mode != Mode.AVOID) {
            stopNav();
            mode = Mode.AVOID;
            com.dwinovo.numen.Constants.LOG.info(
                    "[numen-defense] 保持距离 target={} type={} dist={} safe={}",
                    threat.getId(), threat.getType().getDescription().getString(),
                    String.format("%.1f", companion.distanceTo(threat)),
                    Menace.safeDistanceFrom(threat));
        }
        if (nav == null) {
            var threats = Menace.threatsAmong(java.util.List.of(threat));
            if (threats.isEmpty()) {
                // 不在"该躲"之列(够不着的普通怪):原地别追,让 LLM 决定要不要打。
                InputDriver.halt(companion);
                return;
            }
            double safe = Menace.safeDistanceFrom(threat);
            nav = PlayerNav.toGoal(companion,
                    () -> NavGoal.avoid(safe, Menace.AVOID_PENALTY, threats),
                    FLEE_SPEED,
                    () -> companion.distanceTo(threat) >= safe);
        }
        switch (nav.tick()) {
            case RUNNING -> { }
            case ARRIVED, FAILED -> stopNav();
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
            if (WeaponDamage.flatAttackDamage(inv.getItem(i)) > 0.0) return true;
        }
        return false;
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
        if (target == null) return;
        String mob = target.getType().getDescription().getString();
        if (mode == Mode.CHASE && (target.isDeadOrDying() || target.isRemoved())) {
            // 打赢了。她本来就在打,结果不改变她该做什么 —— 攒着搭车,别为此单开一轮。
            com.dwinovo.numen.event.NumenEvents.body(companion, "was attacked by a " + mob + " and killed it");
            return;
        }
        if (mode == Mode.AVOID && nearestThreat(companion) == null) {
            // 她刚被一个会炸的东西赶离了原地。<b>急件</b>:她多半正在挖矿/赶路,身体却已经
            // 不在那儿了,不告诉她,她会照着旧位置继续算下一步。
            com.dwinovo.numen.event.NumenEvents.emit(companion,
                    com.dwinovo.numen.event.NumenEvents.Kind.BODY_LOG,
                    java.util.Map.of("threat", mob),
                    "你身边出现了" + mob + ",本能让你先拉开了距离,所以你已经不在刚才那个位置了。"
                            + "要打它就得用弓弩,贴上去会被炸。", true);
            return;
        }
        if (mode == Mode.FLEE && nearestThreat(companion) == null) {
            // 打不过跑掉了 —— 同样是急件:她手上那件活多半因此黄了。
            com.dwinovo.numen.event.NumenEvents.emit(companion,
                    com.dwinovo.numen.event.NumenEvents.Kind.BODY_LOG,
                    java.util.Map.of("threat", mob),
                    "你打不过" + mob + ",本能带着你跑开了,现在安全但已经离开了原地。"
                            + "手上那件活可能因此没做完。", true);
        }
    }
}
