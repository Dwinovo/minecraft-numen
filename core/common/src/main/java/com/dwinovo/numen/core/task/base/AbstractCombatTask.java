package com.dwinovo.numen.core.task.base;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.task.combat.CombatTaskRecord;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 近战与远程战斗任务的共享骨架:目标簿记(最近优先的比较键、追击失败
 * 计数与放弃)、后撤走位、以及回执的公共形状。追击与出手属于各自的
 * 武器学(近战的贴身冷却挥击、远程的弹道与拉弓状态机),留在子类。
 *
 * @param <T> 目标实体类型(近战只打 LivingEntity,远程还打末影水晶)
 * @param <R> 战斗账本类型
 */
public abstract class AbstractCombatTask<T extends Entity, R extends CombatTaskRecord>
        extends AbstractCompanionTask<R> {

    protected T target;
    private boolean backingOff;
    private final Map<Integer, Integer> navFailures = new HashMap<>();

    protected AbstractCombatTask(NumenPlayer player, R record) {
        super(player, record);
    }

    /** 追击/后撤的行走速度。 */
    protected abstract double chaseSpeed();

    /** 连续几次接近失败后把目标记为 unreachable。 */
    protected abstract int maxApproachFailures();

    /** 目标被清除时的额外收尾(远程在此中止拉弓)。 */
    protected void onTargetCleared() {}

    /** 最近优先、距离并列时按 id 稳定排序的目标比较键。 */
    public static int compareTargetKeys(double distanceA, int idA, double distanceB, int idB) {
        int byDistance = Double.compare(distanceA, distanceB);
        return byDistance != 0 ? byDistance : Integer.compare(idA, idB);
    }

    protected final void stopActiveNav() {
        stopNav();
        backingOff = false;
    }

    /** 当前 nav 是否是后撤走位(追击入口据此丢弃它,换回跟随导航)。 */
    protected final boolean isBackingOff() {
        return backingOff;
    }

    protected final void clearTarget() {
        target = null;
        stopActiveNav();
        onTargetCleared();
    }

    /** 严格平方距离判定:身体离实体比 {@code distance} 更近。 */
    protected final boolean tooCloseTo(Entity entity, double distance) {
        return player.distanceToSqr(entity) < distance * distance;
    }

    /** 记一次接近失败;达到上限即放弃该目标(记 unreachable 并清除)。 */
    protected final void noteApproachFailure(int id) {
        int failures = navFailures.merge(id, 1, Integer::sum);
        if (failures >= maxApproachFailures()) {
            r.unreachable(id);
            clearTarget();
        }
    }

    /** 接近成功,该目标的失败计数清零。 */
    protected final void forgiveApproachFailures(int id) {
        navFailures.remove(id);
    }

    /**
     * 后撤走位:贴得太近时沿 {@link NavGoal#runAway} 离开目标,直到与其
     * 拉开 {@code keepDistance}。目标消失或已拉开即视为完成。
     */
    protected final TaskState backOffTarget(double keepDistance) {
        if (!backingOff) {
            stopNav();
            backingOff = true;
            nav = PlayerNav.toGoal(player,
                    () -> target == null || target.isRemoved() ? null
                            : NavGoal.runAway(target.blockPosition(), player.blockPosition().getY()),
                    chaseSpeed(),
                    () -> target == null || target.isRemoved()
                            || !tooCloseTo(target, keepDistance));
        }
        switch (nav.tick()) {
            case RUNNING -> { return TaskState.RUNNING; }
            case ARRIVED, FAILED -> {
                stopActiveNav();
                return TaskState.RUNNING;
            }
        }
        return TaskState.RUNNING;
    }

    /** 战斗收场:有战果即成功,颗粒无收按 TARGET_LOST 落败。 */
    protected final TaskState finishCombat(String noneCompletedMessage) {
        InputDriver.halt(player);
        if (!r.completed().isEmpty()) return TaskState.SUCCESS;
        fail(noneCompletedMessage, FailureType.TARGET_LOST);
        return TaskState.FAILED;
    }

    private Map<Integer, Map<String, Object>> combatByEntity() {
        Map<Integer, Map<String, Object>> data = new LinkedHashMap<>();
        for (int id : r.entityIds) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", r.status(id));
            entry.put(r.strikeWord(), r.strikes(id));
            data.put(id, entry);
        }
        return data;
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requested_entity_ids", r.entityIds);
        data.put(r.completedWord() + "_entity_ids", r.completed());
        data.put("lost_entity_ids", r.lost());
        data.put("unreachable_entity_ids", r.unreachable());
        data.put(r.strikeWord(), r.strikes());
        data.put("combat_by_entity", combatByEntity());
        return data;
    }

    /** 战果之后、未竟名单之前的可选补充(近战在此报拾取的战利品)。 */
    protected String successExtra() {
        return "";
    }

    /** 成功文案里目标的称法(entities / ranged targets)。 */
    protected String targetNoun() {
        return "entities";
    }

    @Override
    protected String successMessage() {
        int incomplete = r.lost().size() + r.unreachable().size();
        return r.completedWord() + " " + r.completed().size() + "/" + r.entityIds.size()
                + " requested " + targetNoun() + successExtra()
                + (incomplete == 0 ? "" : " (" + incomplete + " targets could not be completed)");
    }

    @Override
    protected String timeoutMessage() {
        return r.getToolName() + " timed out after " + r.completingWord() + " "
                + r.completed().size() + "/" + r.entityIds.size() + successExtra();
    }

    @Override
    protected String cancelledMessage() {
        return r.getToolName() + " interrupted after " + r.completingWord() + " "
                + r.completed().size() + "/" + r.entityIds.size() + successExtra();
    }
}
