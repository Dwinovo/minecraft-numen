package com.dwinovo.numen.core.task.combat;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.act.Ballistics;
import com.dwinovo.numen.core.combat.AttackPlan;
import com.dwinovo.numen.core.combat.Loadout;
import com.dwinovo.numen.core.combat.Menace;
import com.dwinovo.numen.core.combat.Swing;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code attack}:打掉指定的实体,<b>近战还是远程由身体判,不由模型判</b>。
 *
 * <h2>为什么合成一个工具</h2>
 * 模型在派发那一刻知道的是"打谁";不知道的是等她走到时还有多远、有没有视线、还剩几支箭、
 * 那东西够不够得着——这些每 tick 都在变,只有身体读得到。让模型选弓还是剑,等于要它拿着
 * 过期信息做决定,还顺带引入一整类错误(派远程攻击而背包里没箭)。
 *
 * <h2>三套武器学,一个判据</h2>
 * 挥击(冷却与无敌帧)、射击(弹道与拉弓)、躲避(势场),各自是真正不同的东西;
 * 选哪一套则只有一处判据 {@link AttackPlan},本能链用的也是同一处。
 *
 * <h2>爬行者</h2>
 * 它够得着,但贴上去就是进爆炸半径,所以它走"该不该靠近"那一维:有弓就退到引信会倒退的
 * 距离外射,没弓就明说打不了——而不是冲上去。详见 {@link Menace}。
 */
public final class AttackCompanionTask extends AbstractCompanionTask<AttackTaskRecord> {

    private enum Phase { COMBAT, LOOT }

    private static final double CHASE_SPEED = 1.2;
    private static final int MAX_APPROACH_FAILURES = 3;

    // 弹道常数:箭的物理与两种发射器的初速。
    private static final double MAX_FIRING_RANGE = 32.0;
    private static final double ARROW_GRAVITY = 0.05;
    private static final double ARROW_DRAG = 0.99;
    private static final double ARROW_HITBOX_RADIUS = 0.5;
    private static final double BOW_FULL_SPEED = 3.0;
    private static final double CROSSBOW_SPEED = 3.15;
    /** 连续几发没能真的射出去就判这把武器不顶用。 */
    private static final int MAX_MISFIRES = 2;
    /** 射击时与目标保持的最小距离——太近了弹道压得太平,而且白白挨打。 */
    private static final double RANGED_MIN_DISTANCE = 5.0;
    /** 退开时把这个半径内别的敌对生物也算进势场,免得躲一只撞上另一只。 */
    private static final double AVOID_BYSTANDER_RADIUS = 12.0;

    private Phase phase = Phase.COMBAT;
    private Entity target;
    private Vec3 lastTargetPosition;
    private final Map<Integer, Integer> navFailures = new HashMap<>();
    private final Map<Item, Integer> inventoryBaseline = new HashMap<>();
    private final LootSweep loot;

    private RangedShot shot;
    private int misfires;
    private double followRadius = MAX_FIRING_RANGE - 8.0;
    private int lastPlanLogTick = -1000;
    private AttackPlan.Stance lastLoggedStance;

    public AttackCompanionTask(NumenPlayer player, AttackTaskRecord record) {
        super(player, record);
        this.loot = new LootSweep(player);
    }

    @Override
    protected void onStart() {
        snapshotInventory(inventoryBaseline);
        // 认领这批目标:本能链看见它们在别人手上就不来抢身体了。
        player.setCombatFocus(r.entityIds);
    }

    @Override
    protected TaskState onTick() {
        if (player.isDeadOrDying()) return TaskState.CANCELLED;
        if (phase == Phase.LOOT) return tickLoot();

        validateCurrentTarget();
        Entity selected = selectTarget();
        if (selected == null) {
            InputDriver.halt(player);
            if (!r.defeated().isEmpty()) return TaskState.SUCCESS;
            fail("none of the requested entity ids could be attacked", FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        if (selected != target) {
            stopNav();
            abortShot();
            target = selected;
            followRadius = MAX_FIRING_RANGE - 8.0;
        }
        lastTargetPosition = target.position();
        loot.rememberPreexisting(BlockPos.containing(lastTargetPosition));
        return engage();
    }

    // ==================== 目标簿记 ====================

    private void validateCurrentTarget() {
        if (target == null) return;
        ServerLevel level = (ServerLevel) player.level();
        Entity current = level.getEntity(target.getId());
        if (isDown(target, current)) {
            r.defeated(target.getId());
            beginLoot();
        } else if (current != target || target.isRemoved()) {
            r.lost(target.getId());
            clearTarget();
        }
    }

    /** 打倒了没有。末影水晶这类非生物没有"濒死",它是直接消失的,所以补一条"我打过它且它没了"。 */
    private boolean isDown(Entity previous, Entity current) {
        if (previous instanceof LivingEntity living && living.isDeadOrDying()) return true;
        if (current instanceof LivingEntity living && living.isDeadOrDying()) return true;
        return previous.isRemoved() && r.strikes(previous.getId()) > 0;
    }

    private Entity selectTarget() {
        List<Entity> candidates = new ArrayList<>();
        ServerLevel level = (ServerLevel) player.level();
        for (int id : r.entityIds) {
            if (r.terminal(id)) continue;
            Entity entity = level.getEntity(id);
            if (entity == null || entity == player) {
                r.lost(id);
                continue;
            }
            if (entity instanceof LivingEntity living && living.isDeadOrDying()) {
                r.defeated(id);
                continue;
            }
            if (entity.isRemoved()) {
                r.lost(id);
                continue;
            }
            candidates.add(entity);
        }
        candidates.sort((a, b) -> {
            int byDistance = Double.compare(player.distanceToSqr(a), player.distanceToSqr(b));
            return byDistance != 0 ? byDistance : Integer.compare(a.getId(), b.getId());
        });
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private void clearTarget() {
        target = null;
        stopNav();
        abortShot();
    }

    private void noteApproachFailure(int id) {
        if (navFailures.merge(id, 1, Integer::sum) >= MAX_APPROACH_FAILURES) {
            r.unreachable(id);
            clearTarget();
        }
    }

    // ==================== 判据分派 ====================

    private AttackPlan.Situation situationNow(Loadout loadout) {
        boolean keepAway = Menace.keepAwayFrom(target);
        return new AttackPlan.Situation(
                player.distanceTo(target),
                Swing.reachOf(player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE)),
                !navFailures.containsKey(target.getId())
                        || navFailures.get(target.getId()) < MAX_APPROACH_FAILURES,
                loadout.hasRanged(),
                keepAway,
                Menace.safeDistanceFrom(target));
    }

    private TaskState engage() {
        Loadout loadout = Loadout.forTarget(player, target);
        AttackPlan.Stance stance = AttackPlan.decide(situationNow(loadout));
        logStance(stance, loadout);
        return switch (stance) {
            case MELEE -> swingAt(loadout);
            case CLOSE_IN -> closeIn();
            case RANGED -> shootAt(loadout);
            case AVOID -> backAway();
            case ABANDON -> abandonTarget();
        };
    }

    private void logStance(AttackPlan.Stance stance, Loadout loadout) {
        if (stance == lastLoggedStance && player.tickCount - lastPlanLogTick < 40) return;
        lastLoggedStance = stance;
        lastPlanLogTick = player.tickCount;
        Constants.LOG.info("[numen-attack] {} target={} dist={} melee={} ranged={} keep_away={}",
                stance, target.getId(), String.format("%.1f", player.distanceTo(target)),
                loadout.hasMelee() ? loadout.melee().stack().getItem() : "拳头",
                loadout.hasRanged() ? loadout.ranged().stack().getItem() : "无",
                Menace.keepAwayFrom(target));
    }

    // ==================== 近战 ====================

    private TaskState swingAt(Loadout loadout) {
        stopNav();
        navFailures.remove(target.getId());
        InputDriver.halt(player);
        if (player.isUsingItem()) return TaskState.RUNNING;

        ItemStack before = player.getMainHandItem();
        if (loadout.hasMelee()) {
            player.holdInHand(loadout.melee().slot());
        }
        boolean weaponChanged = player.getMainHandItem() != before;

        InputDriver.lookAt(player, target.getEyePosition());
        if (!Swing.mayStrike(weaponChanged, targetRecovering(), player.getAttackStrengthScale(0.0f))) {
            return TaskState.RUNNING;
        }
        // 疾跑会让原版取消暴击判定(Player.attack 里 flag1 带 !isSprinting)。
        player.setSprinting(false);
        player.attack(target);
        player.swing(InteractionHand.MAIN_HAND);
        r.strike(target.getId());
        return TaskState.RUNNING;
    }

    private boolean targetRecovering() {
        return target instanceof LivingEntity living && living.hurtTime > 0;
    }

    private TaskState closeIn() {
        if (nav == null) {
            double reach = Swing.reachOf(player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE));
            nav = PlayerNav.followEntity(player, () -> target, Math.max(0.5, reach - 1.0), CHASE_SPEED,
                    () -> target == null || target.isRemoved());
        }
        switch (nav.tick()) {
            case RUNNING -> { }
            case ARRIVED -> stopNav();
            case FAILED -> {
                stopNav();
                if (target != null) noteApproachFailure(target.getId());
            }
        }
        return TaskState.RUNNING;
    }

    // ==================== 远程 ====================

    private TaskState shootAt(Loadout loadout) {
        Loadout.Pick weapon = loadout.ranged();
        if (weapon == null) {
            return abandonTarget();
        }
        if (player.distanceTo(target) < RANGED_MIN_DISTANCE && !Menace.keepAwayFrom(target)) {
            abortShot();
            return backAway();
        }
        boolean crossbow = RangedShot.isCrossbow(weapon);
        Ballistics.Aim aim = Ballistics.findArrowShot(player.level(), player, target,
                shotVelocity(crossbow), ARROW_GRAVITY, ARROW_DRAG, ARROW_HITBOX_RADIUS,
                MAX_FIRING_RANGE, !crossbow);
        if (aim == null) {
            abortShot();
            return seekShotWindow();   // 没有弹道窗口:挪个位置再看
        }

        stopNav();
        navFailures.remove(target.getId());
        InputDriver.halt(player);
        InputDriver.lookAt(player, aim.lookPoint());

        ItemStack before = player.getMainHandItem();
        player.holdInHand(weapon.slot());
        if (player.getMainHandItem() != before && shot == null) {
            return TaskState.RUNNING;   // 这一刻只换手,下一刻才起手
        }
        if (!RangedShot.stillHolding(crossbow, player.getMainHandItem())) {
            abortShot();
            return TaskState.RUNNING;
        }
        if (player.isUsingItem() && shot == null) {
            return TaskState.RUNNING;
        }
        if (shot == null) {
            shot = new RangedShot(player, crossbow);
        }
        if (shot.tick(aim, target)) {
            boolean fired = shot.fired();
            shot = null;
            if (fired) {
                r.strike(target.getId());
                misfires = 0;
            } else if (++misfires >= MAX_MISFIRES) {
                fail("the bow or crossbow did not launch an arrow", FailureType.WRONG_TOOL);
                return TaskState.FAILED;
            }
        }
        return TaskState.RUNNING;
    }

    private double shotVelocity(boolean crossbow) {
        return shot != null ? shot.projectileVelocity(BOW_FULL_SPEED, CROSSBOW_SPEED)
                : crossbow ? CROSSBOW_SPEED : BOW_FULL_SPEED * RangedShot.bowPowerForTicks(15);
    }

    /** 射不到:逼近一档再看。逼到不能再逼还是没窗口,才算这只够不着。 */
    private TaskState seekShotWindow() {
        if (nav == null) {
            nav = PlayerNav.followEntity(player, () -> target, followRadius, CHASE_SPEED,
                    () -> target == null || target.isRemoved());
        }
        switch (nav.tick()) {
            case RUNNING -> { }
            case ARRIVED, FAILED -> {
                double next = Math.max(RANGED_MIN_DISTANCE + 1.0, followRadius - 5.0);
                if (next < followRadius - 0.01) {
                    followRadius = next;
                } else if (target != null) {
                    noteApproachFailure(target.getId());
                }
                stopNav();
            }
        }
        return TaskState.RUNNING;
    }

    // ==================== 躲避 ====================

    /**
     * 拉开距离。走的是带权重的势场目标——它认得完所有威胁而且<b>有终点</b>,
     * 所以不必在这里每 tick 判断"退够了没有",也就不会在边界上来回抖。
     */
    private TaskState backAway() {
        if (nav == null) {
            double safe = Menace.safeDistanceFrom(target);
            nav = PlayerNav.toGoal(player,
                    // 威胁快照<b>在 supplier 里面</b>取:它每次重规划调一次,坐标因此跟着刷新。
                    // 放在外面就是把开路那一刻钉死,目标挪开之后她还按旧位置退。
                    () -> {
                        // 退开这一只的路上,别撞进旁边别的敌对生物 —— 它们只绕开,不为它们多跑。
                        var bystanders = Menace.hostilesAround(player, AVOID_BYSTANDER_RADIUS);
                        bystanders.remove(target);
                        var field = Menace.field(List.of(target), bystanders);
                        return field.isEmpty() ? null
                                : NavGoal.avoid(safe, Menace.AVOID_PENALTY, field);
                    },
                    CHASE_SPEED,
                    () -> target == null || target.isRemoved()
                            || player.distanceTo(target) >= safe);
        }
        switch (nav.tick()) {
            case RUNNING -> { }
            case ARRIVED, FAILED -> stopNav();
        }
        return TaskState.RUNNING;
    }

    private TaskState abandonTarget() {
        r.unreachable(target.getId());
        Constants.LOG.info("[numen-attack] 放弃 target={} —— {}", target.getId(),
                Menace.keepAwayFrom(target)
                        ? "不该贴近它,而我没有能用的弓弩"
                        : "走不到它,也没有能用的弓弩");
        clearTarget();
        return TaskState.RUNNING;
    }

    // ==================== 拾荒 ====================

    private void beginLoot() {
        stopNav();
        abortShot();
        InputDriver.halt(player);
        loot.begin(BlockPos.containing(lastTargetPosition != null
                ? lastTargetPosition : target.position()));
        target = null;
        phase = Phase.LOOT;
    }

    private TaskState tickLoot() {
        loot.discover();
        if (loot.settling()) {
            InputDriver.halt(player);
            return TaskState.RUNNING;
        }
        loot.prune();
        if (loot.live().isEmpty()) {
            stopNav();
            loot.finish();
            phase = Phase.COMBAT;
            return TaskState.RUNNING;
        }
        if (nav == null) {
            nav = PlayerNav.toGoal(player, loot::goal, 1.0, () -> loot.live().isEmpty());
        }
        switch (nav.tick()) {
            case RUNNING -> { }
            case ARRIVED, FAILED -> {
                loot.noteApproachFailure();
                stopNav();
            }
        }
        return TaskState.RUNNING;
    }

    // ==================== 收尾与回执 ====================

    private void abortShot() {
        if (shot != null) {
            shot.abort();
            shot = null;
        }
    }

    private void snapshotInventory(Map<Item, Integer> out) {
        out.clear();
        Inventory inventory = player.getInventory();
        for (ItemStack stack : inventory.items) {
            if (!stack.isEmpty()) out.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
    }

    private Map<String, Integer> lootGained() {
        Map<Item, Integer> now = new HashMap<>();
        snapshotInventory(now);
        Map<String, Integer> gained = new LinkedHashMap<>();
        now.forEach((item, count) -> {
            int delta = count - inventoryBaseline.getOrDefault(item, 0);
            if (delta > 0) gained.put(BuiltInRegistries.ITEM.getKey(item).toString(), delta);
        });
        return gained;
    }

    @Override
    protected void cleanup() {
        player.setCombatFocus(java.util.List.of());   // 交回:这些目标不再有人管
        abortShot();
        InputDriver.halt(player);
        player.setShiftKeyDown(false);
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> byEntity = new LinkedHashMap<>();
        for (int id : r.entityIds) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", r.status(id));
            entry.put("strikes", r.strikes(id));
            byEntity.put(String.valueOf(id), entry);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requested_entity_ids", r.entityIds);
        data.put("defeated_entity_ids", r.defeated());
        data.put("lost_entity_ids", r.lost());
        data.put("unreachable_entity_ids", r.unreachable());
        data.put("strikes", r.strikes());
        data.put("combat_by_entity", byEntity);
        data.put("loot_gained", lootGained());
        data.put("unreachable_drop_count", loot.unreachableCount());
        return data;
    }

    private String tally() {
        return r.defeated().size() + "/" + r.entityIds.size()
                + " requested entities, collected " + lootGained();
    }

    @Override
    protected String successMessage() {
        int incomplete = r.lost().size() + r.unreachable().size();
        return "defeated " + tally()
                + (incomplete == 0 ? "" : " (" + incomplete + " targets could not be completed)");
    }

    @Override
    protected String timeoutMessage() {
        return "attack timed out after defeating " + tally();
    }

    @Override
    protected String cancelledMessage() {
        return "attack interrupted after defeating " + tally();
    }
}
