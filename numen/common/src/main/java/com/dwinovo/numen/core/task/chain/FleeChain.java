package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.combat.AttackPlan;
import com.dwinovo.numen.core.combat.Haven;
import com.dwinovo.numen.core.combat.Loadout;
import com.dwinovo.numen.core.combat.Menace;
import com.dwinovo.numen.core.nav.Trip;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.task.reflex.Reflex;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * 打不过就跑:有东西在追她,而她扛不住了(或者手上没有能打的东西)——抢过身体,跑到 {@link Menace#FLEE_DISTANCE}
 * 格外。判据只有 {@link AttackPlan#breakOff} 一处;怎么打仍归 {@code attack},这条链不挥刀。
 *
 * <h2>为什么是一条本能,不在 attack 里</h2>
 * 跑是身体的反射,不是一次攻击的一部分:她挖着矿被围、血见底,该跑的是她,不是"一场仗里的逃跑分支"。放在本能这一层,
 * 模型派的 attack、自卫链开的仗、手上任何活都由它抢占,跑完把身体交还;模型派的 attack 回来时看见她还扛不住、
 * 也没有谁在追她,就自己收工说清楚(见 {@code AttackCompanionTask})。
 *
 * <h2>跑不掉就打</h2>
 * 挑不出落点、或往落点的寻路走不通,连续 {@link #MAX_RETREAT_FAILURES} 次就是退不掉:站着挨打是确定的死,背水一战至少有机会。
 * 它让出身体 {@link #CORNERED_TICKS} 刻,自卫链或手上的仗接着打;那之后再看跑不跑得掉。
 *
 * <h2>落点一次挑定,跑到才换</h2>
 * 方向的连续性就是不绕圈的全部原因。路径每 {@link #REPLAN_TICKS} 刻重算一次,这一刻的怪折进边成本,路线拐开而
 * 目标不变。
 */
public final class FleeChain implements Task, Reflex {

    /** 本能名册里的 id。 */
    public static final String ID = "flee";

    /** 看多远的追兵:和自卫、攻击组装局面同一个半径。 */
    private static final double CHASE_RADIUS = 12.0;

    /**
     * 跑的时候扫多远。必须<b>大于</b> {@link Menace#FLEE_DISTANCE},否则她一边跑一边有新的怪进入视野,落点每几刻
     * 换一次,等于没有落点。
     */
    private static final double SCAN_RADIUS = 40.0;

    /** 离落点这么近就算到了,该重新挑下一个。 */
    private static final double HAVEN_ARRIVED = 2.0;

    /**
     * 跑的路上多久重算一次路线(刻)。二十刻是怪走四五格的量级;再密就是把路径反复拆了重建,疾跑的加速起不来。
     */
    private static final int REPLAN_TICKS = 20;

    /** 往落点的寻路连续失败几次算"退不掉"。 */
    private static final int MAX_RETREAT_FAILURES = 3;

    /** 退不掉时让出身体多久(刻)。 */
    private static final long CORNERED_TICKS = 10 * 20;

    private Trip nav;
    private BlockPos haven;
    private long plannedAt;
    private int retreatFailures;
    private long corneredUntil = Long.MIN_VALUE;
    /** 这一回跑着呢:从抢过身体到跑开为止。 */
    private boolean fleeing;

    @Override
    public boolean canRun(NumenPlayer companion) {
        long now = companion.level().getGameTime();
        if (now < corneredUntil) {
            return false;
        }
        boolean beaten = mustBreakOff(companion);
        if (fleeing) {
            // 还扛不住、身边还有怪就接着跑;血回来了或者跑开了就收手
            if (beaten && !Menace.hostilesAround(companion, Menace.FLEE_DISTANCE).isEmpty()) {
                return true;
            }
            done(companion, beaten);
            return false;
        }
        return beaten && !chasers(companion).isEmpty();
    }

    /** 扛不住,或者赤手而有东西在追她。 */
    private static boolean mustBreakOff(NumenPlayer companion) {
        Loadout loadout = Loadout.forTarget(companion, companion);
        return AttackPlan.breakOff(Menace.effectiveHealth(companion), loadout.hasMelee() || loadout.hasRanged(),
                !chasers(companion).isEmpty());
    }

    /** 正在针对她的:锁定了她,或刚打了她。 */
    private static List<Mob> chasers(NumenPlayer companion) {
        return Menace.hostilesAround(companion, CHASE_RADIUS).stream()
                .filter(m -> m.getTarget() == companion || m == companion.getLastHurtByMob())
                .toList();
    }

    @Override
    public TaskState tick(NumenPlayer companion) {
        if (!fleeing) {
            fleeing = true;
            com.dwinovo.numen.Constants.LOG.info("[numen-flee] 扛不住了,跑 —— 身边 {} 只在追",
                    chasers(companion).size());
        }
        if (haven == null || companion.blockPosition().closerThan(haven, HAVEN_ARRIVED)) {
            haven = Haven.awayFrom(companion, Menace.hostilesAround(companion, SCAN_RADIUS));
            stopNav();
        }
        if (haven == null) {
            // 四下都没有能站的落点:和往落点的路走不通一样,算一次退不掉
            return ++retreatFailures >= MAX_RETREAT_FAILURES ? cornered(companion) : TaskState.RUNNING;
        }
        long now = companion.level().getGameTime();
        if (nav != null && now - plannedAt >= REPLAN_TICKS) {
            stopNav();   // 到点重算:落点不变,只让这一刻的怪进边成本
        }
        if (nav == null) {
            plannedAt = now;
            // 路上要绕开谁:四十格内每一只,经过它们身边的格变贵;落点旁边站着一只怪也算到了,不然她永远到不了、
            // 也就永远不换落点
            nav = Trip.to(companion, Goals.within(Goals.at(haven), 0, HAVEN_ARRIVED), RouteSpec.defaults(), haven)
                    .avoiding(() -> Menace.dangers(companion, SCAN_RADIUS));
        }
        Trip.Status status = nav.tick();
        if (status == Trip.Status.FAILED) {
            stopNav();
            haven = null;   // 这个方向走不通,下一刻换一个
            if (++retreatFailures >= MAX_RETREAT_FAILURES) {
                return cornered(companion);
            }
        } else {
            if (status == Trip.Status.ARRIVED) {
                stopNav();
                haven = null;
            }
            retreatFailures = 0;
        }
        return TaskState.RUNNING;
    }

    /** 退不掉:让出身体一阵,背水一战;如实告诉她。 */
    private TaskState cornered(NumenPlayer companion) {
        corneredUntil = companion.level().getGameTime() + CORNERED_TICKS;
        retreatFailures = 0;
        haven = null;
        stopNav();
        fleeing = false;
        companion.controls().releaseAll();
        com.dwinovo.numen.Constants.LOG.info("[numen-flee] 退不掉,就地还手");
        com.dwinovo.numen.event.NumenEvents.reflex(companion, this,
                "tried to run from what was chasing me but found no way out — fighting back where I stand");
        return TaskState.RUNNING;
    }

    /** 这一回跑完了:跑开了,或者血回来了。 */
    private void done(NumenPlayer companion, boolean stillBeaten) {
        fleeing = false;
        haven = null;
        stopNav();
        companion.controls().releaseAll();
        com.dwinovo.numen.event.NumenEvents.reflex(companion, this, stillBeaten
                ? "broke off and ran — nothing hostile is within " + (int) Menace.FLEE_DISTANCE + " blocks now"
                : "ran from a fight I could not take; I can hold my own again");
    }

    private void stopNav() {
        if (nav != null) {
            nav.stop();
            nav = null;
        }
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        // 被更急的链抢走(摔落、换气):只松开身体,落点留着,回来接着跑
        stopNav();
        companion.controls().releaseAll();
    }

    @Override
    public String name() {
        return ID;
    }

    // ---- Reflex roster paperwork (constitution §6) ----

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "扛不住或手上没有能打的东西时,有东西追她就自动跑开;跑不掉就就地还手";
    }
}
