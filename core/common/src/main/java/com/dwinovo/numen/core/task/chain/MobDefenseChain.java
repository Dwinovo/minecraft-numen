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
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Autonomous threat-response survival chain. Polls for a hostile within a bounded
 * radius each tick (biased toward whatever last hurt the body); when one is present
 * it spikes above the LLM task and either fights back (healthy + armed) or backs away
 * (too hurt, unarmed, or the thing explodes). Bounded by the scan radius: it engages
 * what is near and gives up chasing anything that leaves, never travelling across the world.
 *
 * <p><b>退避只有一种。</b>"打不过要退"与"不该贴近要退"的动作是同一个,所以走同一段代码、
 * 同一个势场目标;区别只在谁做的决定。
 *
 * <p>Drives the substrate primitives directly — {@link PlayerNav} to close on or
 * run from the mob, {@link Interaction#attackEntity} for the native cooldown-scaled
 * swing, {@link NavGoal#avoid} for the retreat field. No {@code AbstractCompanionTask}:
 * there is no result to build and the fight/retreat logic is a per-tick decision, not a
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

    /** 追上去打 / 拉开距离。<b>退避只有一种</b>——打不过要退和不该贴近要退,动作是同一个。 */
    private enum Mode { NONE, CHASE, RETREAT }

    /** Consecutive nav failures on the current engagement before the leash fires. */
    private static final int MAX_ENGAGE_FAILS = 3;
    /**
     * 威胁离开扫描半径后还盯这么久,才算真的脱离接触。
     *
     * <p>没有它,一只跟她跑得几乎一样快的怪会在半径边界上一进一出,每进出一次就是一轮
     * 完整的"交战 → 脱身",身体日记也就跟着发一条。实测二十秒里发了十七条。
     */
    private static final long DISENGAGE_GRACE_TICKS = 40;
    /** {@link #threatLastSeenTick} 的"从没见过"哨兵。不参与减法,免得负溢出。 */
    private static final long NEVER = Long.MIN_VALUE;
    /** How long an unreachable target is ignored / how long the whole chain cools down (ticks). */
    private static final long UNREACHABLE_COOLDOWN = 200;
    private static final long CHAIN_COOLDOWN = 100;


    private Mode mode = Mode.NONE;
    private LivingEntity target;
    private PlayerNav nav;

    public MobDefenseChain() {
    }
    /** 最后一刻还看得见威胁的游戏时间,配合 {@link #DISENGAGE_GRACE_TICKS} 做脱离迟滞。 */
    private long threatLastSeenTick = NEVER;
    /** Engagement leash: consecutive nav FAILEDs on the current fight/retreat attempt. */
    private int consecutiveNavFails;
    /** Targets we provably can't path to, ignored until the stored gameTime (entity id → until). */
    private final java.util.Map<Integer, Long> unreachable = new java.util.HashMap<>();
    /** Whole-chain cooldown after a boxed-in retreat — hands the body back to the LLM. */
    private long cooldownUntilGameTime;

    @Override
    public boolean canRun(NumenPlayer companion) {
        long now = companion.level().getGameTime();
        if (now < cooldownUntilGameTime) return false;
        if (SurvivalDecisions.mobDefenseTriggered(!unhandledThreats(companion).isEmpty())) return true;
        // 宽限期内不撒手:怪刚出半径不代表甩掉了,这一刻放手下一刻就得重来。
        return threatLastSeenTick != NEVER && now - threatLastSeenTick < DISENGAGE_GRACE_TICKS;
    }

    @Override
    public TaskState tick(NumenPlayer companion) {
        long now = companion.level().getGameTime();
        LivingEntity threat = nearestThreat(companion);
        if (threat == null) {
            // 宽限期内照旧退,别原地站住等它追上来。
            if (threatLastSeenTick != NEVER && now - threatLastSeenTick < DISENGAGE_GRACE_TICKS) {
                retreat(companion);
                return TaskState.RUNNING;
            }
            release(companion);
            threatLastSeenTick = NEVER;
            return TaskState.RUNNING;
        }
        threatLastSeenTick = now;
        if (threat != target) {
            noteOutcome(companion);   // the previous engagement just ended (e.g. target died)
            target = threat;
            consecutiveNavFails = 0;
            stopNav();   // re-plan for the new target
        }

        // 「打不过」不再是判据层之前的一道前置闸,它就是判据的一维(有效血量)。
        // 于是"打还是退"只在一处决定,反射链与 attack 工具问的是同一个函数。
        switch (AttackPlan.decide(new AttackPlan.Situation(
                companion.distanceTo(threat),
                ATTACK_REACH,
                consecutiveNavFails < MAX_ENGAGE_FAILS,
                Loadout.forTarget(companion, threat).hasRanged(),
                Menace.keepAwayFrom(threat),
                Menace.safeDistanceFrom(threat),
                Menace.effectiveHealth(companion)))) {
            // 够不够得着由 fight() 内部照旧判(它还要看视线);判据在这里只回答打不打。
            case MELEE, CLOSE_IN -> {
                // 生存层<b>从不主动去拿武器</b>,所以空手不还手 —— 这是反射链自己的政策
                // (模型派的 attack 用拳头打鸡是正当的,那条路不受这里影响)。
                if (hasWeapon(companion)) {
                    fight(companion, threat);
                } else {
                    retreat(companion);
                }
            }
            // 本能的职责是别死,不是打赢。够不着、不该贴上去、或扛不住,就退开——
            // 真要打它,让模型派 attack,那条路上才有弹道与拉弓。
            case RANGED, AVOID, ABANDON, DISENGAGE -> retreat(companion);
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
        return "被怪物攻击会反击;打不过、或者对方会爆炸,就退开到安全距离";
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

    // ---- retreat ----

    /**
     * 拉开距离。<b>打不过要退、不该贴近要退,走的是同一段代码</b>——两者的动作本来就是
     * 同一个,拆成两套只会得到两份各有各的残缺。
     *
     * <h2>为什么不是"背对着它跑"</h2>
     * 旧的逃离目标只收一个坐标点,别的怪在它眼里不存在,于是背对着僵尸跑很可能一头撞进
     * 另一只怀里。这里给的是势场:当前每一只威胁都进场、按危险程度配权重,绕开才便宜。
     *
     * <h2>为什么有终点</h2>
     * 退到离每只威胁都够远就算脱身,链子随即把身体交还。旧的逃离目标"永不到达",只能一直
     * 跑到寻路连续失败才停——中间这段时间模型派的任务一刻都轮不上。
     */
    private void retreat(NumenPlayer companion) {
        if (mode != Mode.RETREAT) {
            stopNav();
            mode = Mode.RETREAT;
            com.dwinovo.numen.Constants.LOG.info("[numen-defense] 退开 从 {} 只威胁中拉开到 {} 格",
                    threatsNear(companion).size(), (int) SCAN_RADIUS);
        }
        if (nav == null) {
            nav = PlayerNav.toGoal(companion,
                    // 威胁快照<b>在这里面</b>取:supplier 每次重规划调一次,坐标因此跟着刷新。
                    // 放到外面就是把开路那一刻钉死,怪追上来之后她还按旧位置退。
                    () -> {
                        // 追她的要拉开整段距离;旁边发呆的只绕开 —— 否则一片沼泽的史莱姆
                        // 会让"脱身"永远不成立,她一直跑到寻路失败为止。
                        var field = Menace.field(threatsNear(companion), bystandersNear(companion));
                        return field.isEmpty() ? null
                                : NavGoal.avoid(SCAN_RADIUS, Menace.AVOID_PENALTY, field);
                    },
                    FLEE_SPEED,
                    () -> threatsNear(companion).isEmpty());
        }
        switch (nav.tick()) {
            case RUNNING -> { }
            case ARRIVED -> stopNav();
            case FAILED -> {
                stopNav();
                // 退无可退(被围住、被堵在死角)。攥着身体谁也帮不上,交还给模型——
                // 它也许知道一条我们看不见的路。
                if (++consecutiveNavFails >= MAX_ENGAGE_FAILS) {
                    cooldownUntilGameTime = companion.level().getGameTime() + CHAIN_COOLDOWN;
                    consecutiveNavFails = 0;
                    release(companion);
                }
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
        LivingEntity attacker = companion.getLastHurtByMob();
        LivingEntity best = null;
        double bestDistSqr = Double.MAX_VALUE;
        for (LivingEntity m : unhandledThreats(companion)) {
            double d = companion.distanceToSqr(m);
            // Bias toward the mob that hurt us: pretend it is closer so it wins ties.
            double weighted = (m == attacker) ? d - 1.0 : d;
            if (weighted < bestDistSqr) {
                bestDistSqr = weighted;
                best = m;
            }
        }
        return best;
    }

    /**
     * 反射链<b>该管的</b>那些威胁:全部,减去模型派的战斗任务已经认领的目标。
     *
     * <p>让路的理由是任务的判据更细——它知道爬行者该退到几格、够不着该换弓,而反射链
     * 抢过去只会让它拉到一半的弓作废,两层还会在边界上互相抢。清单外的照旧归反射链:
     * 她打鸡时扑上来的僵尸,任务根本不认识。
     *
     * <p><b>命要紧时不让路</b>:血掉到濒死线以下,任务的判据里一个血量都没有,那一档
     * 只有反射链看得见。
     */
    private java.util.List<LivingEntity> unhandledThreats(NumenPlayer companion) {
        java.util.List<LivingEntity> all = threatsNear(companion);
        if (Menace.outmatched(companion)) {
            return all;   // 扛不住了:任务认领与否都不作数,本能一律接管
        }
        java.util.List<LivingEntity> mine = new java.util.ArrayList<>();
        for (LivingEntity m : all) {
            if (!companion.isCombatFocus(m.getId())) {
                mine.add(m);
            }
        }
        return mine;
    }

    /**
     * 扫描半径内<b>正在针对她</b>的敌对生物——链子要不要醒、打哪一只,看的是这一份。
     *
     * <p>敌对与否问 {@link Menace#hostile}(认 {@code Enemy} 接口),不是 {@code Monster}:
     * 史莱姆、恶魂、幻翼、疣猪兽都不在 {@code Monster} 之下,按它扫会把这些整个漏掉。
     */
    private java.util.List<LivingEntity> threatsNear(NumenPlayer companion) {
        LivingEntity attacker = companion.getLastHurtByMob();
        long now = companion.level().getGameTime();
        java.util.List<LivingEntity> found = new java.util.ArrayList<>();
        for (Mob m : Menace.hostilesAround(companion, SCAN_RADIUS)) {
            // DEFENSE, not aggression: only a mob that is actually engaging us — it hurt
            // us, or its AI has targeted us — counts. A neutral hostile (a calm zombified
            // piglin drifting by) must not be attacked and provoked by a "defense" chain.
            if (m != attacker && m.getTarget() != companion) continue;
            // Skip targets we recently proved unreachable (the engagement leash).
            if (unreachable.getOrDefault(m.getId(), 0L) > now) continue;
            found.add(m);
        }
        return found;
    }

    /** 半径内还没盯上她的那些敌对生物:逃跑路上要绕开,但不必为它们多跑。 */
    private java.util.List<Mob> bystandersNear(NumenPlayer companion) {
        java.util.List<LivingEntity> engaging = threatsNear(companion);
        java.util.List<Mob> rest = new java.util.ArrayList<>();
        for (Mob m : Menace.hostilesAround(companion, SCAN_RADIUS)) {
            if (!engaging.contains(m)) {
                rest.add(m);
            }
        }
        return rest;
    }

    /** Does the body CARRY a melee weapon anywhere in inventory? Pure check — no hand
     *  mutation; the swap happens only once FIGHT is actually chosen (a retreating body
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
        if (mode == Mode.RETREAT && unhandledThreats(companion).isEmpty()) {
            // <b>不急</b>。她的后台任务照跑,黄了自有 task_finished 报;这条只是让主人
            // 翻聊天流时看得懂她刚才为什么挪了二十格。攒着搭下一轮的车就够。
            //
            // 一次交战只发一条:发的时机是"宽限期过完、确实甩掉了",不是"这一刻没看见它"
            // ——后者在半径边界上一两秒就成立一次。
            com.dwinovo.numen.event.NumenEvents.body(companion,
                    "backed away from a " + mob + " and is clear of it now");
        }
    }
}
