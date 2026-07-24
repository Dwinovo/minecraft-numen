package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.ToolSelect;
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
import java.util.Comparator;
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
public final class MeleeAttackCompanionTask extends AbstractCompanionTask<MeleeAttackTaskRecord> {

    private enum Phase { COMBAT, LOOT }

    private static final double CHASE_SPEED = 1.2;
    private static final double LOOT_RADIUS = 8.0;
    private static final int DROP_LOITER_TICKS = 5;
    private static final int MAX_APPROACH_FAILURES = 3;
    private static final float ATTACK_READY = 0.99f;

    private Phase phase = Phase.COMBAT;
    private LivingEntity target;
    private Vec3 lastTargetPosition;
    private boolean backingOff;
    private final Map<Integer, Integer> navFailures = new HashMap<>();

    private final Map<Item, Integer> inventoryBaseline = new HashMap<>();
    private final Set<Integer> preexistingDrops = new HashSet<>();
    private final Map<Integer, Integer> preexistingDropCounts = new HashMap<>();
    private final Set<Integer> trackedDrops = new HashSet<>();
    private final Set<Integer> skippedDrops = new HashSet<>();
    private BlockPos deathPosition;
    private long anticipatedUntil;
    private int lootApproachFailures;
    private int unreachableDropCount;

    public MeleeAttackCompanionTask(NumenPlayer player, MeleeAttackTaskRecord record) {
        super(player, record);
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
                r.defeated(target.getId());
                beginLoot();
                return TaskState.RUNNING;
            }
            if (current != target || target.isRemoved()) {
                r.lost(target.getId());
                clearTarget();
            }
        }

        LivingEntity selected = selectTarget();
        if (selected == null) return finishCombat();
        if (selected != target) {
            stopActiveNav();
            target = selected;
            lastTargetPosition = selected.position();
        }
        return tickTarget();
    }

    private TaskState finishCombat() {
        InputDriver.halt(player);
        if (!r.defeated().isEmpty()) return TaskState.SUCCESS;
        fail("none of the requested entity ids could be defeated", FailureType.TARGET_LOST);
        return TaskState.FAILED;
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
                r.defeated(id);
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
        rememberExistingDrops(lastTargetPosition);

        double reach = entityReachRange();
        double maintainDistance = approachRadius(reach);
        if (shouldBackOffBeforeSwing(target, maintainDistance)) {
            return backOffTarget(maintainDistance);
        }
        if (!isInReach(target, reach)) {
            return chaseTarget(maintainDistance);
        }

        stopActiveNav();
        navFailures.remove(target.getId());
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
        r.hit(target.getId());
        return TaskState.RUNNING;
    }

    private TaskState chaseTarget(double maintainDistance) {
        if (backingOff) stopActiveNav();
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

    private TaskState backOffTarget(double maintainDistance) {
        if (!backingOff) {
            stopNav();
            backingOff = true;
            nav = PlayerNav.toGoal(player,
                    () -> target == null || target.isRemoved() ? null
                            : NavGoal.runAway(target.blockPosition(), player.blockPosition().getY()),
                    CHASE_SPEED,
                    () -> target == null || target.isRemoved()
                            || !tooCloseTo(target, maintainDistance));
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

    private void noteApproachFailure(int id) {
        int failures = navFailures.merge(id, 1, Integer::sum);
        if (failures >= MAX_APPROACH_FAILURES) {
            r.unreachable(id);
            clearTarget();
        }
    }

    static int compareTargetKeys(double distanceA, int idA, double distanceB, int idB) {
        int byDistance = Double.compare(distanceA, distanceB);
        return byDistance != 0 ? byDistance : Integer.compare(idA, idB);
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

    private boolean tooCloseTo(LivingEntity entity, double maintainDistance) {
        return isWithinEntityReach(player.distanceToSqr(entity), maintainDistance);
    }

    private boolean isInReach(LivingEntity entity, double reach) {
        return isWithinEntityReach(player.distanceToSqr(entity), reach);
    }

    private double entityReachRange() {
        return effectiveEntityReach(interactionRange());
    }

    private void rememberExistingDrops(Vec3 around) {
        AABB box = new AABB(around, around).inflate(LOOT_RADIUS);
        for (ItemEntity item : player.level().getEntitiesOfClass(ItemEntity.class, box)) {
            preexistingDrops.add(item.getId());
            preexistingDropCounts.put(item.getId(), item.getItem().getCount());
        }
    }

    private void beginLoot() {
        stopActiveNav();
        InputDriver.halt(player);
        deathPosition = BlockPos.containing(lastTargetPosition != null
                ? lastTargetPosition : target.position());
        anticipatedUntil = player.level().getGameTime() + DROP_LOITER_TICKS;
        trackedDrops.clear();
        skippedDrops.clear();
        lootApproachFailures = 0;
        target = null;
        phase = Phase.LOOT;
    }

    private TaskState tickLoot() {
        scanNewDrops();
        long now = player.level().getGameTime();
        if (now <= anticipatedUntil) {
            InputDriver.halt(player);
            return TaskState.RUNNING;
        }
        pruneDrops();
        if (liveDrops().isEmpty()) {
            stopActiveNav();
            preexistingDrops.clear();
            preexistingDropCounts.clear();
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
                    nearestLiveDrop().ifPresent(item -> {
                        if (skippedDrops.add(item.getId())) unreachableDropCount++;
                    });
                    lootApproachFailures = 0;
                }
                stopActiveNav();
            }
        }
        return TaskState.RUNNING;
    }

    private void scanNewDrops() {
        if (deathPosition == null) return;
        AABB box = new AABB(deathPosition).inflate(LOOT_RADIUS);
        for (ItemEntity item : player.level().getEntitiesOfClass(ItemEntity.class, box)) {
            int id = item.getId();
            if (!preexistingDrops.contains(id)
                    || item.getItem().getCount() > preexistingDropCounts.getOrDefault(id, 0)) {
                trackedDrops.add(id);
            }
        }
    }

    private void pruneDrops() {
        trackedDrops.removeIf(id -> {
            Entity entity = ((ServerLevel) player.level()).getEntity(id);
            return !(entity instanceof ItemEntity) || entity.isRemoved();
        });
    }

    private List<ItemEntity> liveDrops() {
        List<ItemEntity> out = new ArrayList<>();
        ServerLevel level = (ServerLevel) player.level();
        for (int id : trackedDrops) {
            Entity entity = level.getEntity(id);
            if (entity instanceof ItemEntity item && !item.isRemoved() && !skippedDrops.contains(id)) {
                out.add(item);
            }
        }
        return out;
    }

    private java.util.Optional<ItemEntity> nearestLiveDrop() {
        return liveDrops().stream().min(Comparator.comparingDouble(player::distanceToSqr));
    }

    private NavGoal lootGoal() {
        List<NavGoal> goals = liveDrops().stream()
                .map(item -> NavGoal.near(item.blockPosition(), 1.0))
                .toList();
        return goals.isEmpty() ? NavGoal.exact(player.blockPosition()) : NavGoal.composite(goals);
    }

    private double interactionRange() {
        return player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
    }

    private void clearTarget() {
        target = null;
        stopActiveNav();
    }

    private void stopActiveNav() {
        stopNav();
        backingOff = false;
    }

    private void snapshotInventory(Map<Item, Integer> out) {
        out.clear();
        Inventory inventory = player.getInventory();
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
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

    private Map<Integer, Map<String, Object>> combatByEntity() {
        Map<Integer, Map<String, Object>> data = new LinkedHashMap<>();
        for (int id : r.entityIds) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", r.status(id));
            entry.put("hits", r.hits(id));
            data.put(id, entry);
        }
        return data;
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requested_entity_ids", r.entityIds);
        data.put("defeated_entity_ids", r.defeated());
        data.put("lost_entity_ids", r.lost());
        data.put("unreachable_entity_ids", r.unreachable());
        data.put("hits", r.hits());
        data.put("combat_by_entity", combatByEntity());
        data.put("loot_gained", lootGained());
        data.put("unreachable_drop_count", unreachableDropCount);
        return data;
    }

    @Override
    protected String successMessage() {
        int incomplete = r.lost().size() + r.unreachable().size();
        return "defeated " + r.defeated().size() + "/" + r.entityIds.size()
                + " requested entities, collected " + lootGained()
                + (incomplete == 0 ? "" : " (" + incomplete + " targets could not be completed)");
    }

    @Override protected String timeoutMessage() {
        return "melee_attack timed out after defeating " + r.defeated().size()
                + "/" + r.entityIds.size() + ", collected " + lootGained();
    }

    @Override protected String cancelledMessage() {
        return "melee_attack interrupted after defeating " + r.defeated().size()
                + "/" + r.entityIds.size() + ", collected " + lootGained();
    }
}
