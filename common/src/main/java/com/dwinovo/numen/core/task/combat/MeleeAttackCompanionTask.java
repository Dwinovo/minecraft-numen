package com.dwinovo.numen.core.task.combat;

import com.dwinovo.numen.core.motor.ToolSelect;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.task.base.AbstractCombatTask;
import com.dwinovo.numen.core.task.base.DropTracker;
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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explicit-id melee task: chase an authorized living entity, attack with the
 * native cooldown-gated hit once in reach, then walk over drops before the next
 * target is selected.
 */
public final class MeleeAttackCompanionTask
        extends AbstractCombatTask<LivingEntity, MeleeAttackTaskRecord> {

    private enum Phase { COMBAT, LOOT }

    private static final double CHASE_SPEED = 1.2;
    private static final double LOOT_RADIUS = 8.0;
    private static final int DROP_LOITER_TICKS = 5;
    private static final int MAX_APPROACH_FAILURES = 3;
    private static final float ATTACK_READY = 0.99f;

    private Phase phase = Phase.COMBAT;
    private Vec3 lastTargetPosition;

    private final Map<Item, Integer> inventoryBaseline = new HashMap<>();
    private final DropTracker drops = new DropTracker();
    private final Set<Integer> skippedDrops = new HashSet<>();
    private BlockPos deathPosition;
    private long anticipatedUntil;
    private int lootApproachFailures;
    private int unreachableDropCount;

    public MeleeAttackCompanionTask(NumenPlayer player, MeleeAttackTaskRecord record) {
        super(player, record);
    }

    @Override
    protected double chaseSpeed() {
        return CHASE_SPEED;
    }

    @Override
    protected int maxApproachFailures() {
        return MAX_APPROACH_FAILURES;
    }

    @Override
    protected void onStart() {
        snapshotInventory(inventoryBaseline);
    }

    @Override
    protected TaskState onTick() {
        if (player.isDeadOrDying()) return TaskState.CANCELLED;
        if (phase == Phase.LOOT) return tickLoot();

        if (target != null) {
            Entity current = ((ServerLevel) player.level()).getEntity(target.getId());
            if (target.isDeadOrDying()) {
                r.completed(target.getId());
                beginLoot();
                return TaskState.RUNNING;
            }
            if (current != target || target.isRemoved()) {
                r.lost(target.getId());
                clearTarget();
            }
        }

        LivingEntity selected = selectTarget();
        if (selected == null) {
            return finishCombat("none of the requested entity ids could be defeated");
        }
        if (selected != target) {
            stopActiveNav();
            target = selected;
            lastTargetPosition = selected.position();
        }
        return tickTarget();
    }

    private LivingEntity selectTarget() {
        List<LivingEntity> candidates = new ArrayList<>();
        ServerLevel level = (ServerLevel) player.level();
        for (int id : r.entityIds) {
            if (r.terminal(id)) continue;
            Entity entity = level.getEntity(id);
            if (!(entity instanceof LivingEntity living) || entity == player) {
                r.lost(id);
                continue;
            }
            if (living.isDeadOrDying()) {
                r.completed(id);
                continue;
            }
            if (living.isRemoved()) {
                r.lost(id);
                continue;
            }
            candidates.add(living);
        }
        candidates.sort((a, b) -> compareTargetKeys(
                player.distanceToSqr(a), a.getId(), player.distanceToSqr(b), b.getId()));
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private TaskState tickTarget() {
        lastTargetPosition = target.position();
        drops.rememberExisting(player.level(),
                new AABB(lastTargetPosition, lastTargetPosition).inflate(LOOT_RADIUS));

        double reach = entityReachRange();
        double maintainDistance = approachRadius(reach);
        if (shouldBackOffBeforeSwing(target, maintainDistance)) {
            return backOffTarget(maintainDistance);
        }
        if (!isInReach(target, reach)) {
            return chaseTarget(maintainDistance);
        }

        stopActiveNav();
        forgiveApproachFailures(target.getId());
        InputDriver.halt(player);
        if (player.isUsingItem()) return TaskState.RUNNING;
        ItemStack heldBefore = player.getMainHandItem();
        ToolSelect.holdBestWeapon(player);
        boolean weaponChanged = player.getMainHandItem() != heldBefore;

        InputDriver.lookAt(player, target.getEyePosition());
        boolean attackReady = player.getAttackStrengthScale(0.0f) >= ATTACK_READY;
        if (!canStartNativeAttack(weaponChanged, target.hurtTime > 0, attackReady)) {
            return TaskState.RUNNING;
        }
        if (!isInReach(target, entityReachRange())) return TaskState.RUNNING;

        player.setSprinting(false);
        player.attack(target);
        player.swing(InteractionHand.MAIN_HAND);
        r.strike(target.getId());
        return TaskState.RUNNING;
    }

    private TaskState chaseTarget(double maintainDistance) {
        if (isBackingOff()) stopActiveNav();
        if (nav == null) {
            nav = PlayerNav.followEntity(player, () -> target, maintainDistance, CHASE_SPEED,
                    () -> target == null || target.isRemoved());
        }
        switch (nav.tick()) {
            case RUNNING -> { return TaskState.RUNNING; }
            case ARRIVED -> {
                stopActiveNav();
                return TaskState.RUNNING;
            }
            case FAILED -> {
                stopActiveNav();
                if (target != null) noteApproachFailure(target.getId());
                return TaskState.RUNNING;
            }
        }
        return TaskState.RUNNING;
    }

    static double effectiveEntityReach(double nativeRange) {
        return Math.max(4.0, nativeRange);
    }

    static double approachRadius(double interactionRange) {
        return Math.max(0.5, interactionRange - 1.0);
    }

    static boolean shouldBackOffBeforeSwing(boolean tooClose, boolean usingItem, boolean attackReady) {
        return tooClose && (usingItem || !attackReady);
    }

    static boolean isWithinEntityReach(double distanceSqr, double reach) {
        return distanceSqr < reach * reach;
    }

    static boolean canStartNativeAttack(boolean weaponChanged, boolean targetRecovering,
                                        boolean attackReady) {
        return !weaponChanged && !targetRecovering && attackReady;
    }

    private boolean shouldBackOffBeforeSwing(LivingEntity entity, double maintainDistance) {
        return shouldBackOffBeforeSwing(
                tooCloseTo(entity, maintainDistance),
                player.isUsingItem(),
                player.getAttackStrengthScale(0.0f) >= ATTACK_READY);
    }

    private boolean isInReach(LivingEntity entity, double reach) {
        return isWithinEntityReach(player.distanceToSqr(entity), reach);
    }

    private double entityReachRange() {
        return effectiveEntityReach(player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE));
    }

    private void beginLoot() {
        stopActiveNav();
        InputDriver.halt(player);
        deathPosition = BlockPos.containing(lastTargetPosition != null
                ? lastTargetPosition : target.position());
        anticipatedUntil = player.level().getGameTime() + DROP_LOITER_TICKS;
        drops.resetTracking();
        skippedDrops.clear();
        lootApproachFailures = 0;
        target = null;
        phase = Phase.LOOT;
    }

    private TaskState tickLoot() {
        ServerLevel level = (ServerLevel) player.level();
        if (deathPosition != null) {
            drops.discover(level, new AABB(deathPosition).inflate(LOOT_RADIUS));
        }
        long now = player.level().getGameTime();
        if (now <= anticipatedUntil) {
            InputDriver.halt(player);
            return TaskState.RUNNING;
        }
        drops.prune(level);
        if (liveDrops().isEmpty()) {
            stopActiveNav();
            drops.clear();
            phase = Phase.COMBAT;
            deathPosition = null;
            return TaskState.RUNNING;
        }
        if (nav == null) {
            nav = PlayerNav.toGoal(player, this::lootGoal, 1.0, () -> liveDrops().isEmpty());
        }
        switch (nav.tick()) {
            case RUNNING -> { }
            case ARRIVED, FAILED -> {
                if (++lootApproachFailures >= 2) {
                    drops.nearest(level, player, skippedDrops).ifPresent(item -> {
                        if (skippedDrops.add(item.getId())) unreachableDropCount++;
                    });
                    lootApproachFailures = 0;
                }
                stopActiveNav();
            }
        }
        return TaskState.RUNNING;
    }

    private List<ItemEntity> liveDrops() {
        return drops.live((ServerLevel) player.level(), skippedDrops);
    }

    private NavGoal lootGoal() {
        List<NavGoal> goals = liveDrops().stream()
                .map(item -> NavGoal.near(item.blockPosition(), 1.0))
                .toList();
        return goals.isEmpty() ? NavGoal.exact(player.blockPosition()) : NavGoal.composite(goals);
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
        InputDriver.halt(player);
        player.setShiftKeyDown(false);
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = super.resultData();
        data.put("loot_gained", lootGained());
        data.put("unreachable_drop_count", unreachableDropCount);
        return data;
    }

    @Override
    protected String successExtra() {
        return ", collected " + lootGained();
    }
}
