package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.act.Ballistics;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runtime-id ranged attack task for bow and crossbow combat. */
public final class RangedAttackCompanionTask extends AbstractCompanionTask<RangedAttackTaskRecord> {

    private enum WeaponKind { BOW, CROSSBOW }

    private record WeaponChoice(int slot, ItemStack stack, WeaponKind kind, boolean charged) {}


    private static final double CHASE_SPEED = 1.1;
    private static final double MAX_FIRING_RANGE = 32.0;
    private static final double DEFAULT_MIN_RANGE = 5.0;
    private static final double CRYSTAL_MIN_RANGE = 8.0;
    private static final double FOLLOW_STEP = 5.0;
    private static final int MAX_APPROACH_FAILURES = 4;

    private static final double ARROW_GRAVITY = 0.05;
    private static final double ARROW_DRAG = 0.99;
    private static final double ARROW_HITBOX_RADIUS = 0.5;
    private static final double BOW_FULL_SPEED = 3.0;
    private static final double CROSSBOW_SPEED = 3.15;

    private static final int BOW_RELEASE_TICKS = 15;
    private static final int BOW_MAX_DRAW_TICKS = 40;
    private static final int CROSSBOW_LOAD_TIMEOUT = 80;
    private static final int SETTLE_TICKS = 4;
    private static final int MAX_MISFIRES = 2;
    private static final double AIM_THRESHOLD_DEGREES = 1.5;

    private final Map<Integer, Integer> navFailures = new HashMap<>();

    private Entity target;
    private Shot shot;
    private boolean backingOff;
    private double followRadius;
    private int misfires;
    private int lastNoWindowLogTick = -1000;

    public RangedAttackCompanionTask(NumenPlayer player, RangedAttackTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<com.dwinovo.numen.core.task.base.Precondition> preconditions() {
        return List.of(
                () -> findRangedWeapon() >= 0 ? null
                        : new com.dwinovo.numen.core.task.base.Precondition.Failure(
                                "you need a bow or crossbow before ranged_attack can start",
                                FailureType.WRONG_TOOL),
                () -> hasAmmoOrChargedCrossbow() ? null
                        : new com.dwinovo.numen.core.task.base.Precondition.Failure(
                                "you have no arrows for ranged_attack", FailureType.NO_MATERIAL));
    }

    @Override
    protected TaskState onTick() {
        if (player.isDeadOrDying()) return TaskState.CANCELLED;

        validateCurrentTarget();
        Entity selected = selectTarget();
        if (selected == null) return finishCombat();
        if (selected != target) {
            stopActiveNav();
            abortShot();
            target = selected;
            followRadius = initialFollowRadius(minRangeFor(selected));
        }
        return tickTarget();
    }

    private void validateCurrentTarget() {
        if (target == null) return;
        ServerLevel level = (ServerLevel) player.level();
        Entity current = level.getEntity(target.getId());
        if (isDestroyed(target, current)) {
            r.destroyed(target.getId());
            clearTarget();
        } else if (current != target || target.isRemoved()) {
            r.lost(target.getId());
            clearTarget();
        }
    }

    private boolean isDestroyed(Entity previous, Entity current) {
        if (previous instanceof LivingEntity living && living.isDeadOrDying()) return true;
        if (current instanceof LivingEntity living && living.isDeadOrDying()) return true;
        return previous.isRemoved() && r.shots(previous.getId()) > 0;
    }

    private Entity selectTarget() {
        Entity best = null;
        ServerLevel level = (ServerLevel) player.level();
        for (int id : r.entityIds) {
            if (r.terminal(id)) continue;
            Entity entity = level.getEntity(id);
            if (entity == null || entity == player) {
                r.lost(id);
                continue;
            }
            if (entity instanceof LivingEntity living && living.isDeadOrDying()) {
                r.destroyed(id);
                continue;
            }
            if (entity.isRemoved()) {
                r.lost(id);
                continue;
            }
            if (best == null || compareTargetKeys(player.distanceToSqr(entity), entity.getId(),
                    player.distanceToSqr(best), best.getId()) < 0) {
                best = entity;
            }
        }
        return best;
    }

    private TaskState finishCombat() {
        InputDriver.halt(player);
        if (!r.destroyed().isEmpty()) return TaskState.SUCCESS;
        fail("none of the requested entity ids could be destroyed with ranged_attack", FailureType.TARGET_LOST);
        return TaskState.FAILED;
    }

    private TaskState tickTarget() {
        if (!hasAmmoOrChargedCrossbow()) {
            abortShot();
            stopActiveNav();
            if (!r.destroyed().isEmpty()) return TaskState.SUCCESS;
            fail("ranged_attack stopped because there are no arrows left", FailureType.NO_MATERIAL);
            return TaskState.FAILED;
        }
        if (tooCloseTo(target, minRangeFor(target))) {
            abortShot();
            return backOffTarget(minRangeFor(target));
        }

        WeaponChoice choice = chooseRangedWeapon();
        if (choice == null) {
            fail("ranged_attack needs a bow or crossbow with arrows", FailureType.WRONG_TOOL);
            return TaskState.FAILED;
        }

        Ballistics.Aim aim = aimFor(target, choice);
        if (aim == null) {
            logNoWindow(choice);
            abortShot();
            return chaseTarget();
        }


        stopActiveNav();
        navFailures.remove(target.getId());
        return fireAtTarget(choice, aim);
    }

    private TaskState fireAtTarget(WeaponChoice choice, Ballistics.Aim aim) {
        InputDriver.halt(player);
        InputDriver.lookAt(player, aim.lookPoint());

        ItemStack before = player.getMainHandItem();
        player.holdInHand(choice.slot);
        if (player.getMainHandItem() != before && shot == null) {
            return TaskState.RUNNING;
        }
        if (!sameWeapon(choice, player.getMainHandItem())) {
            abortShot();
            return TaskState.RUNNING;
        }
        if (player.isUsingItem() && shot == null) {
            return TaskState.RUNNING;
        }

        if (shot == null) {
            shot = new Shot(choice.kind);
        }
        if (shot.tick(aim)) {
            boolean fired = shot.fired();
            shot = null;
            if (fired) {
                misfires = 0;
            }
            if (!fired && ++misfires >= MAX_MISFIRES) {
                fail("the ranged weapon did not launch an arrow", FailureType.WRONG_TOOL);
                return TaskState.FAILED;
            }
        }
        return TaskState.RUNNING;
    }

    private boolean sameWeapon(WeaponChoice choice, ItemStack stack) {
        return switch (choice.kind) {
            case BOW -> stack.getItem() instanceof BowItem;
            case CROSSBOW -> stack.getItem() instanceof CrossbowItem;
        };
    }

    private TaskState chaseTarget() {
        if (backingOff) stopActiveNav();
        if (nav == null) {
            nav = PlayerNav.followEntity(player, () -> target, followRadius, CHASE_SPEED,
                    () -> target == null || target.isRemoved() || hasCurrentShotWindow());
        }
        switch (nav.tick()) {
            case RUNNING -> { return TaskState.RUNNING; }
            case ARRIVED -> {
                if (!hasCurrentShotWindow()) {
                    reduceFollowRadiusOrNoteFailure();
                }
                stopActiveNav();
                return TaskState.RUNNING;
            }
            case FAILED -> {
                reduceFollowRadiusOrNoteFailure();
                stopActiveNav();
                return TaskState.RUNNING;
            }
        }
        return TaskState.RUNNING;
    }

    private TaskState backOffTarget(double minRange) {
        if (!backingOff) {
            stopNav();
            backingOff = true;
            nav = PlayerNav.toGoal(player,
                    () -> target == null || target.isRemoved() ? null
                            : NavGoal.runAway(target.blockPosition(), player.blockPosition().getY()),
                    CHASE_SPEED,
                    () -> target == null || target.isRemoved() || !tooCloseTo(target, minRange + 1.0));
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

    private void reduceFollowRadiusOrNoteFailure() {
        if (target == null) return;
        double min = minRangeFor(target) + 1.0;
        double next = Math.max(min, followRadius - FOLLOW_STEP);
        if (next < followRadius - 0.01) {
            followRadius = next;
            return;
        }
        int failures = navFailures.merge(target.getId(), 1, Integer::sum);
        if (failures >= MAX_APPROACH_FAILURES) {
            r.unreachable(target.getId());
            clearTarget();
        }
    }

    private boolean hasCurrentShotWindow() {
        WeaponChoice choice = chooseRangedWeapon();
        return target != null && choice != null && !tooCloseTo(target, minRangeFor(target))
                && aimFor(target, choice) != null;
    }


    private Ballistics.Aim aimFor(Entity entity, WeaponChoice choice) {
        double velocity = projectileVelocity(choice);
        return Ballistics.findArrowShot(player.level(), player, entity, velocity,
                ARROW_GRAVITY, ARROW_DRAG, ARROW_HITBOX_RADIUS, MAX_FIRING_RANGE,
                choice.kind == WeaponKind.BOW);
    }

    private void logNoWindow(WeaponChoice choice) {
        if (target == null || player.tickCount - lastNoWindowLogTick < 20) return;
        lastNoWindowLogTick = player.tickCount;
        Constants.LOG.debug("[numen-ranged] no shot window target={} weapon={} dist={} follow_radius={}",
                target.getId(), choice.kind, player.distanceTo(target), followRadius);
    }

    private double projectileVelocity(WeaponChoice choice) {
        if (choice.kind == WeaponKind.CROSSBOW) return CROSSBOW_SPEED;
        int ticks = BOW_RELEASE_TICKS;
        if (shot != null && shot.kind == WeaponKind.BOW) {
            ticks = Math.max(BOW_RELEASE_TICKS, shot.held + 1);
        }
        return BOW_FULL_SPEED * bowPowerForTicks(ticks);
    }

    static double bowPowerForTicks(int ticks) {
        double draw = ticks / 20.0;
        double power = (draw * draw + draw * 2.0) / 3.0;
        return Math.min(1.0, Math.max(0.0, power));
    }

    static double initialFollowRadius(double minRange) {
        return Math.max(minRange + 1.0, MAX_FIRING_RANGE - 8.0);
    }

    static int compareTargetKeys(double distanceA, int idA, double distanceB, int idB) {
        int byDistance = Double.compare(distanceA, distanceB);
        return byDistance != 0 ? byDistance : Integer.compare(idA, idB);
    }

    static boolean canRelease(double angleDegrees, int heldTicks, int releaseTicks) {
        return angleDegrees <= AIM_THRESHOLD_DEGREES && heldTicks >= releaseTicks;
    }

    private boolean aimReady(Ballistics.Aim aim) {
        return Ballistics.angleDegrees(player.getViewVector(1.0f), aim.direction()) <= AIM_THRESHOLD_DEGREES;
    }

    private boolean tooCloseTo(Entity entity, double minRange) {
        return player.distanceToSqr(entity) < minRange * minRange;
    }

    private double minRangeFor(Entity entity) {
        return entity.getType() == EntityType.END_CRYSTAL ? CRYSTAL_MIN_RANGE : DEFAULT_MIN_RANGE;
    }

    private int findRangedWeapon() {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem) return i;
        }
        return -1;
    }

    private boolean hasAmmoOrChargedCrossbow() {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) return true;
            if ((stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem)
                    && !player.getProjectile(stack).isEmpty()) return true;
        }
        return false;
    }

    private WeaponChoice chooseRangedWeapon() {
        Inventory inv = player.getInventory();
        int selected = inv.getSelectedSlot();
        ItemStack current = inv.getItem(selected);
        if (current.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(current)) {
            return new WeaponChoice(selected, current, WeaponKind.CROSSBOW, true);
        }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
                return new WeaponChoice(i, stack, WeaponKind.CROSSBOW, true);
            }
        }
        if (current.getItem() instanceof BowItem && !player.getProjectile(current).isEmpty()) {
            return new WeaponChoice(selected, current, WeaponKind.BOW, false);
        }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() instanceof BowItem && !player.getProjectile(stack).isEmpty()) {
                return new WeaponChoice(i, stack, WeaponKind.BOW, false);
            }
        }
        if (current.getItem() instanceof CrossbowItem && !player.getProjectile(current).isEmpty()) {
            return new WeaponChoice(selected, current, WeaponKind.CROSSBOW, false);
        }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() instanceof CrossbowItem && !player.getProjectile(stack).isEmpty()) {
                return new WeaponChoice(i, stack, WeaponKind.CROSSBOW, false);
            }
        }
        return null;
    }


    private void clearTarget() {
        target = null;
        stopActiveNav();
        abortShot();
    }

    private void stopActiveNav() {
        stopNav();
        backingOff = false;
    }

    private void abortShot() {
        if (shot != null) {
            shot.abort();
            shot = null;
        }
    }

    private final class Shot {
        private enum State { USING, READY_TO_FIRE, SETTLING, DONE, MISFIRE }

        private final WeaponKind kind;
        private State state = State.USING;
        private int held;
        private int settle;
        private boolean fired;

        Shot(WeaponKind kind) {
            this.kind = kind;
            player.gameMode.useItem(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
        }

        boolean tick(Ballistics.Aim aim) {
            switch (state) {
                case USING -> tickUsing(aim);
                case READY_TO_FIRE -> tickReadyToFire(aim);
                case SETTLING -> tickSettling();
                default -> { return true; }
            }
            return state == State.DONE || state == State.MISFIRE;
        }

        private void tickUsing(Ballistics.Aim aim) {
            ItemStack weapon = player.getMainHandItem();
            if (kind == WeaponKind.CROSSBOW) {
                if (CrossbowItem.isCharged(weapon)) {
                    state = State.READY_TO_FIRE;
                    tickReadyToFire(aim);
                } else if (!player.isUsingItem()) {
                    state = State.SETTLING;
                } else if (++held >= CrossbowItem.getChargeDuration(weapon, player)) {
                    player.releaseUsingItem();
                    state = State.READY_TO_FIRE;
                    settle = 0;
                } else if (held >= CROSSBOW_LOAD_TIMEOUT) {
                    player.stopUsingItem();
                    state = State.SETTLING;
                }
                return;
            }

            if (!player.isUsingItem()) {
                state = State.SETTLING;
                return;
            }
            held++;
            double angle = Ballistics.angleDegrees(player.getViewVector(1.0f), aim.direction());
            if (canRelease(angle, held, BOW_RELEASE_TICKS) || held >= BOW_MAX_DRAW_TICKS) {
                player.releaseUsingItem();
                markFired(aim);
            }
        }

        private void tickReadyToFire(Ballistics.Aim aim) {
            ItemStack weapon = player.getMainHandItem();
            if (!CrossbowItem.isCharged(weapon)) {
                if (++settle >= SETTLE_TICKS) {
                    state = State.MISFIRE;
                }
                return;
            }
            if (aimReady(aim)) {
                player.gameMode.useItem(player, player.level(), weapon, InteractionHand.MAIN_HAND);
                markFired(aim);
            }
        }

        private void tickSettling() {
            if (++settle >= SETTLE_TICKS) {
                state = State.MISFIRE;
            }
        }

        private void markFired(Ballistics.Aim aim) {
            fired = true;
            if (target != null) {
                r.shot(target.getId());
                Constants.LOG.debug("[numen-ranged] shot target={} weapon={} held={} dist={} eta={}",
                        target.getId(), kind, held, player.distanceTo(target), Math.ceil(aim.travelTicks()));
            }
            state = State.DONE;
        }

        boolean fired() {
            return fired;
        }

        void abort() {
            if (player.isUsingItem()) player.stopUsingItem();
        }
    }

    private Map<Integer, Map<String, Object>> combatByEntity() {
        Map<Integer, Map<String, Object>> data = new LinkedHashMap<>();
        for (int id : r.entityIds) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", r.status(id));
            entry.put("shots", r.shots(id));
            data.put(id, entry);
        }
        return data;
    }

    @Override
    protected void cleanup() {
        abortShot();
        InputDriver.halt(player);
        player.setShiftKeyDown(false);
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requested_entity_ids", r.entityIds);
        data.put("destroyed_entity_ids", r.destroyed());
        data.put("lost_entity_ids", r.lost());
        data.put("unreachable_entity_ids", r.unreachable());
        data.put("shots", r.shots());
        data.put("combat_by_entity", combatByEntity());
        return data;
    }

    @Override
    protected String successMessage() {
        int incomplete = r.lost().size() + r.unreachable().size();
        return "destroyed " + r.destroyed().size() + "/" + r.entityIds.size()
                + " requested ranged targets"
                + (incomplete == 0 ? "" : " (" + incomplete + " targets could not be completed)");
    }

    @Override
    protected String timeoutMessage() {
        return "ranged_attack timed out after destroying " + r.destroyed().size()
                + "/" + r.entityIds.size();
    }

    @Override
    protected String cancelledMessage() {
        return "ranged_attack interrupted after destroying " + r.destroyed().size()
                + "/" + r.entityIds.size();
    }
}

