package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.mixin.FishingHookAccessor;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tick-driven vanilla fishing from a nearby water surface. */
public final class FishCompanionTask extends AbstractCompanionTask<FishTaskRecord> {

    private enum Phase { POSITION, PREPARE, AIM, WAIT, COLLECT, COOLDOWN }

    private record FishingSetup(BlockPos stance, BlockPos water) {}

    private static final int STANCE_SEARCH_RADIUS = 12;
    private static final int STANCE_SEARCH_Y = 4;
    private static final int MAX_STANCE_CHECKS = 256;
    private static final int MAX_POSITION_FAILURES = 3;
    private static final double NAV_SPEED = 1.0;

    private static final int CAST_SEARCH_RADIUS = 10;
    private static final int CAST_SEARCH_Y = 4;
    private static final double MIN_CAST_DISTANCE = 4.0;
    private static final double IDEAL_CAST_DISTANCE = 6.0;
    private static final double WATER_SURFACE_OFFSET = 0.85;
    private static final int AIM_TICKS = 3;
    private static final int CAST_SETTLE_TIMEOUT = 5 * 20;
    private static final int CAST_LIFETIME = 60 * 20;
    private static final int COOLDOWN_TICKS = 10;
    private static final int MAX_FAILED_CASTS = 5;

    /** Vanilla reels the loot toward the player, but terrain can stop it short. */
    private static final double LOOT_SEARCH_RADIUS = 18.0;
    private static final double PICKUP_REACH_SQR = 1.5;
    private static final int LOOT_DISCOVERY_TICKS = 10;
    /** Let vanilla's reel impulse bring the catch back before chasing it. */
    private static final int LOOT_RETURN_GRACE_TICKS = 20;
    private static final int LOOT_CLOSE_WAIT_TICKS = 20;
    private static final int LOOT_COLLECTION_TIMEOUT = 20 * 20;

    private static final double FISHING_DRAG = 0.92;
    private static final double FISHING_GRAVITY = 0.03;
    private static final int MAX_FLIGHT_TICKS = 80;

    private final Set<BlockPos> rejectedStances = new HashSet<>();
    private final Set<BlockPos> rejectedTargets = new HashSet<>();
    /** Item ids that existed before the reel, so nearby clutter is not claimed as this catch. */
    private final Set<Integer> preReelItems = new HashSet<>();
    /** Exact ItemEntity instances spawned by the successful vanilla reel. */
    private final Map<Integer, ItemEntity> caughtLoot = new HashMap<>();

    private Phase phase = Phase.POSITION;
    private BlockPos stance;
    private BlockPos target;
    private int phaseTicks;
    private int failedCasts;
    private int positionFailures;
    private ItemEntity lootTarget;
    private int lootCloseTicks;
    private int unreachableLoot;

    public FishCompanionTask(NumenPlayer player, FishTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        return List.of(() -> findRodSlot() >= 0 ? null
                : new Precondition.Failure("fish needs a fishing rod in inventory",
                        FailureType.WRONG_TOOL));
    }

    @Override
    protected TaskState onTick() {
        if (player.isDeadOrDying()) return TaskState.CANCELLED;

        InputDriver.halt(player);
        if (phase == Phase.POSITION) return positionForFishing();
        if (phase == Phase.COLLECT) return collectCaughtLoot();
        if (r.caught() >= r.requested) return TaskState.SUCCESS;

        int rodSlot = findRodSlot();
        if (rodSlot < 0) {
            discardHook();
            fail("fishing stopped because there is no fishing rod left", FailureType.WRONG_TOOL);
            return TaskState.FAILED;
        }
        player.holdInHand(rodSlot);
        if (!player.getMainHandItem().is(Items.FISHING_ROD)) return TaskState.RUNNING;

        return switch (phase) {
            case POSITION -> throw new IllegalStateException("position phase handled above");
            case PREPARE -> prepare();
            case AIM -> aimAndCast();
            case WAIT -> waitForBite();
            case COLLECT -> throw new IllegalStateException("collect phase handled above");
            case COOLDOWN -> coolDown();
        };
    }

    private TaskState positionForFishing() {
        if (stance == null || target == null) {
            FishingSetup setup = findFishingSetup();
            if (setup == null) {
                fail("no safe dry fishing stance with reachable water nearby; move close to a shoreline and try fish again",
                        FailureType.OUT_OF_REACH);
                return TaskState.FAILED;
            }
            stance = setup.stance();
            target = setup.water();
        }

        if (atStance()) {
            stopNav();
            phase = Phase.PREPARE;
            return TaskState.RUNNING;
        }
        if (nav == null) {
            nav = PlayerNav.toGoal(player, () -> NavGoal.exact(stance), NAV_SPEED, this::atStance);
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> {
                stopNav();
                phase = Phase.PREPARE;
                yield TaskState.RUNNING;
            }
            case FAILED -> {
                rejectedStances.add(stance);
                stopNav();
                stance = null;
                target = null;
                if (++positionFailures >= MAX_POSITION_FAILURES) {
                    fail("nearby dry fishing stances were unreachable; move onto a clear shoreline and try fish again",
                            FailureType.NO_PATH);
                    yield TaskState.FAILED;
                }
                yield TaskState.RUNNING;
            }
        };
    }

    private FishingSetup findFishingSetup() {
        BlockPos current = feet();
        if (isDryStance(current) && !rejectedStances.contains(current)) {
            BlockPos water = findCastTarget(current, player.getEyePosition());
            if (water != null) return new FishingSetup(current, water);
            // Already safely on land but no water is in casting range. Long-distance
            // water discovery belongs to the model's locate/move step, not this job.
            return null;
        }

        List<BlockPos> candidates = new ArrayList<>();
        BlockPos origin = player.blockPosition();
        for (int dy = -STANCE_SEARCH_Y; dy <= STANCE_SEARCH_Y; dy++) {
            for (int dx = -STANCE_SEARCH_RADIUS; dx <= STANCE_SEARCH_RADIUS; dx++) {
                for (int dz = -STANCE_SEARCH_RADIUS; dz <= STANCE_SEARCH_RADIUS; dz++) {
                    if (dx * dx + dz * dz > STANCE_SEARCH_RADIUS * STANCE_SEARCH_RADIUS) continue;
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    if (!rejectedStances.contains(candidate) && isDryStance(candidate)) {
                        candidates.add(candidate.immutable());
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(current::distSqr));
        int checks = Math.min(MAX_STANCE_CHECKS, candidates.size());
        for (int i = 0; i < checks; i++) {
            BlockPos candidate = candidates.get(i);
            Vec3 eye = new Vec3(candidate.getX() + 0.5,
                    candidate.getY() + player.getEyeHeight(), candidate.getZ() + 0.5);
            BlockPos water = findCastTarget(candidate, eye);
            if (water != null) return new FishingSetup(candidate, water);
        }
        return null;
    }

    private TaskState prepare() {
        if (player.fishing != null) {
            discardHook();
            return TaskState.RUNNING;
        }

        BlockPos current = feet();
        if (!isDryStance(current)) {
            resetPositioning();
            return TaskState.RUNNING;
        }
        if (!current.equals(stance)) {
            stance = current;
            target = null;
        }
        if (target == null || !isCastableSurface(target)
                || !trajectoryClear(player.getEyePosition(), target)) {
            target = findCastTarget(stance, player.getEyePosition());
        }
        if (target == null && !rejectedTargets.isEmpty()) {
            // A tiny pond may expose only one valid landing cell. After trying all
            // distinct candidates, permit another ballistic attempt instead of
            // converting one unlucky cast into a permanent "no water" verdict.
            rejectedTargets.clear();
            target = findCastTarget(stance, player.getEyePosition());
        }
        if (target == null) {
            fail("no unobstructed fishing cast is available from this dry stance; move along the shoreline and try again",
                    FailureType.OUT_OF_REACH);
            return TaskState.FAILED;
        }

        phase = Phase.AIM;
        phaseTicks = 0;
        aimAtTarget();
        return TaskState.RUNNING;
    }

    private TaskState aimAndCast() {
        if (!isCastableSurface(target) || !trajectoryClear(player.getEyePosition(), target)) {
            return failedCast("the selected water surface became obstructed", true);
        }
        aimAtTarget();
        if (++phaseTicks < AIM_TICKS) return TaskState.RUNNING;

        double pitch = castPitchDegrees(player.getEyePosition(), target);
        player.gameMode.useItem(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
        r.castOnce();
        if (player.fishing == null) {
            return failedCast("the fishing rod did not cast", false);
        }
        Constants.LOG.debug("[numen-fish] cast={} target={} pitch={}", r.casts(),
                target.toShortString(), String.format(java.util.Locale.ROOT, "%.1f", pitch));
        phase = Phase.WAIT;
        phaseTicks = 0;
        return TaskState.RUNNING;
    }

    private TaskState waitForBite() {
        FishingHook hook = player.fishing;
        if (hook == null || hook.isRemoved()) {
            return failedCast("the fishing hook disappeared before a catch", false);
        }
        phaseTicks++;

        Entity hooked = hook.getHookedIn();
        if (hooked != null) {
            reelIn();
            return failedCast("the hook caught an entity instead of landing cleanly", true);
        }

        int nibble = ((FishingHookAccessor) (Object) hook).numen$getNibble();
        if (isBiteWindow(nibble)) {
            beginLootCollection();
            r.caughtOne();
            Constants.LOG.debug("[numen-fish] caught={}/{} casts={}",
                    r.caught(), r.requested, r.casts());
            return TaskState.RUNNING;
        }

        boolean inWater = hook.level().getFluidState(hook.blockPosition()).is(FluidTags.WATER);
        if (!inWater && phaseTicks >= CAST_SETTLE_TIMEOUT) {
            Constants.LOG.debug("[numen-fish] miss hook={} on_ground={} age={}",
                    hook.blockPosition().toShortString(), hook.onGround(), phaseTicks);
            return failedCast("the fishing hook did not settle in water", true);
        }
        if (phaseTicks >= CAST_LIFETIME) {
            return failedCast("no bite arrived before the cast timed out", false);
        }
        return TaskState.RUNNING;
    }

    /**
     * Finish the semantic catch, not merely the rod interaction: walk to every
     * ItemEntity created by this reel until vanilla pickup absorbs it. Fishing
     * loot is launched toward the owner, but a bank, slab or ledge can intercept
     * it several blocks away (the exact failure seen in ordinary play-testing).
     */
    private TaskState collectCaughtLoot() {
        phaseTicks++;
        if (phaseTicks <= LOOT_DISCOVERY_TICKS) discoverCaughtLoot();

        if (phaseTicks >= LOOT_COLLECTION_TIMEOUT) {
            int remaining = (int) caughtLoot.values().stream().filter(e -> !e.isRemoved()).count();
            fail("reeled in fishing loot but timed out while retrieving " + remaining
                    + " dropped loot item(s)", FailureType.NO_PATH);
            return TaskState.FAILED;
        }

        // A vanilla reel launches its loot toward the owner. Planning against that
        // still-moving entity makes the body step off the bank to "meet" a catch
        // that would have arrived by itself. Hold the known-safe stance briefly;
        // genuine stranded loot is still path-found after this grace period.
        boolean returningLoot = caughtLoot.values().stream().anyMatch(e -> !e.isRemoved());
        if (returningLoot && phaseTicks <= LOOT_RETURN_GRACE_TICKS) {
            return TaskState.RUNNING;
        }

        if (lootTarget != null) {
            if (lootTarget.isRemoved()) {
                caughtLoot.remove(lootTarget.getId());
                lootTarget = null;
                lootCloseTicks = 0;
                stopNav();
            } else if (player.distanceToSqr(lootTarget) <= PICKUP_REACH_SQR) {
                // Give vanilla collision pickup a full second. This also produces
                // a useful failure for a full inventory instead of looping forever.
                stopNav();
                if (++lootCloseTicks >= LOOT_CLOSE_WAIT_TICKS) abandonLootTarget();
                return TaskState.RUNNING;
            } else {
                lootCloseTicks = 0;
                if (nav == null) {
                    nav = new PlayerNav(player, lootTarget::blockPosition, NAV_SPEED,
                            () -> lootTarget == null || lootTarget.isRemoved()
                                    || player.distanceToSqr(lootTarget) <= PICKUP_REACH_SQR);
                }
                switch (nav.tick()) {
                    case RUNNING -> { return TaskState.RUNNING; }
                    case ARRIVED -> { return TaskState.RUNNING; }
                    case FAILED -> {
                        abandonLootTarget();
                        return TaskState.RUNNING;
                    }
                }
            }
        }

        caughtLoot.entrySet().removeIf(e -> e.getValue().isRemoved());
        lootTarget = caughtLoot.values().stream()
                .filter(e -> !e.isRemoved())
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
        if (lootTarget != null) {
            stopNav();
            return TaskState.RUNNING;
        }

        // A freshly spawned catch can be absorbed on the same tick or become
        // query-visible one tick later. Keep the short discovery window before
        // deciding that there is nothing left to retrieve.
        if (phaseTicks < LOOT_DISCOVERY_TICKS) return TaskState.RUNNING;
        if (unreachableLoot > 0) {
            fail("reeled in fishing loot but could not reach " + unreachableLoot
                    + " dropped loot item(s)", FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        clearLootTracking();
        beginCooldown();
        return TaskState.RUNNING;
    }

    private void beginLootCollection() {
        preReelItems.clear();
        caughtLoot.clear();
        lootTarget = null;
        lootCloseTicks = 0;
        unreachableLoot = 0;
        for (ItemEntity item : nearbyItems()) preReelItems.add(item.getId());

        reelIn();
        phase = Phase.COLLECT;
        phaseTicks = 0;
        stopNav();
        discoverCaughtLoot();
    }

    /** Find only entities introduced by the reel, never unrelated ground clutter. */
    private void discoverCaughtLoot() {
        for (ItemEntity item : nearbyItems()) {
            if (!preReelItems.contains(item.getId())) caughtLoot.putIfAbsent(item.getId(), item);
        }
    }

    private List<ItemEntity> nearbyItems() {
        return player.level().getEntitiesOfClass(ItemEntity.class,
                player.getBoundingBox().inflate(LOOT_SEARCH_RADIUS), item -> !item.isRemoved());
    }

    private void abandonLootTarget() {
        if (lootTarget != null) caughtLoot.remove(lootTarget.getId());
        unreachableLoot++;
        lootTarget = null;
        lootCloseTicks = 0;
        stopNav();
    }

    private void clearLootTracking() {
        preReelItems.clear();
        caughtLoot.clear();
        lootTarget = null;
        lootCloseTicks = 0;
        unreachableLoot = 0;
        stopNav();
    }

    private TaskState coolDown() {
        if (++phaseTicks < COOLDOWN_TICKS) return TaskState.RUNNING;
        if (r.caught() >= r.requested) return TaskState.SUCCESS;
        phase = Phase.PREPARE;
        phaseTicks = 0;
        return TaskState.RUNNING;
    }

    private TaskState failedCast(String reason, boolean rejectTarget) {
        BlockPos failedTarget = target;
        discardHook();
        if (rejectTarget && failedTarget != null) rejectedTargets.add(failedTarget);
        if (++failedCasts >= MAX_FAILED_CASTS) {
            fail(reason + " after " + failedCasts
                    + " attempts; move to a clearer shoreline and try fish again", FailureType.OUT_OF_REACH);
            return TaskState.FAILED;
        }
        phase = Phase.PREPARE;
        phaseTicks = 0;
        if (rejectTarget) target = null;
        return TaskState.RUNNING;
    }

    private void beginCooldown() {
        phase = Phase.COOLDOWN;
        phaseTicks = 0;
        failedCasts = 0;
        rejectedTargets.clear();
    }

    private void resetPositioning() {
        discardHook();
        stopNav();
        clearLootTracking();
        phase = Phase.POSITION;
        phaseTicks = 0;
        stance = null;
        target = null;
        rejectedTargets.clear();
    }

    private void reelIn() {
        if (player.fishing != null && player.getMainHandItem().is(Items.FISHING_ROD)) {
            player.gameMode.useItem(player, player.level(), player.getMainHandItem(),
                    InteractionHand.MAIN_HAND);
        }
    }

    private int findRodSlot() {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(Items.FISHING_ROD)) return i;
        }
        return -1;
    }

    private BlockPos findCastTarget(BlockPos fromStance, Vec3 eye) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = -CAST_SEARCH_Y; dy <= CAST_SEARCH_Y; dy++) {
            for (int dx = -CAST_SEARCH_RADIUS; dx <= CAST_SEARCH_RADIUS; dx++) {
                for (int dz = -CAST_SEARCH_RADIUS; dz <= CAST_SEARCH_RADIUS; dz++) {
                    double horizontal = Math.sqrt(dx * dx + dz * dz);
                    if (horizontal < MIN_CAST_DISTANCE || horizontal > CAST_SEARCH_RADIUS) continue;
                    BlockPos candidate = fromStance.offset(dx, dy, dz);
                    if (!rejectedTargets.contains(candidate) && isCastableSurface(candidate)) {
                        candidates.add(candidate.immutable());
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate -> castScore(fromStance, candidate)));
        for (BlockPos candidate : candidates) {
            if (trajectoryClear(eye, candidate)) return candidate;
        }
        return null;
    }

    private double castScore(BlockPos fromStance, BlockPos candidate) {
        double dx = candidate.getX() - fromStance.getX();
        double dz = candidate.getZ() - fromStance.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return Math.abs(horizontal - IDEAL_CAST_DISTANCE)
                + Math.abs(candidate.getY() - fromStance.getY()) * 0.35
                - waterNeighbourCount(candidate) * 0.04;
    }

    private int waterNeighbourCount(BlockPos pos) {
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (isCastableSurface(pos.offset(dx, 0, dz))) count++;
            }
        }
        return count;
    }

    private boolean isCastableSurface(BlockPos pos) {
        var fluid = player.level().getFluidState(pos);
        if (!fluid.is(FluidTags.WATER) || !fluid.isSource()) return false;
        if (player.level().getFluidState(pos.above()).is(FluidTags.WATER)) return false;
        return player.level().getBlockState(pos.above())
                .getCollisionShape(player.level(), pos.above()).isEmpty();
    }

    private boolean isDryStance(BlockPos pos) {
        return player.level().getFluidState(pos).isEmpty()
                && player.level().getFluidState(pos.above()).isEmpty()
                && BlockHelper.canWalkThrough(player.level(), pos)
                && BlockHelper.canWalkThrough(player.level(), pos.above())
                && BlockHelper.canWalkOn(player.level(), pos.below());
    }

    private boolean atStance() {
        return stance != null && feet().equals(stance) && isDryStance(stance);
    }

    private BlockPos feet() {
        return BlockHelper.playerFeet(player.level(), player.getX(), player.getY(), player.getZ());
    }

    private void aimAtTarget() {
        InputDriver.lookAt(player, castAimPoint(player.getEyePosition(), target));
    }

    private static Vec3 castAimPoint(Vec3 eye, BlockPos target) {
        double tx = target.getX() + 0.5;
        double tz = target.getZ() + 0.5;
        double dx = tx - eye.x;
        double dz = tz - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0e-6) return new Vec3(tx, waterSurfaceY(target), tz);
        double pitch = Math.toRadians(castPitchDegrees(eye, target));
        double scale = 16.0 / horizontal;
        return new Vec3(eye.x + dx * scale,
                eye.y - Math.tan(pitch) * 16.0,
                eye.z + dz * scale);
    }

    private boolean trajectoryClear(Vec3 eye, BlockPos target) {
        double tx = target.getX() + 0.5;
        double tz = target.getZ() + 0.5;
        double dx = tx - eye.x;
        double dz = tz - eye.z;
        double directDistance = Math.sqrt(dx * dx + dz * dz);
        if (directDistance < 1.0e-6) return false;
        double ux = dx / directDistance;
        double uz = dz / directDistance;
        // Vanilla spawns the bobber 0.3 blocks in front of the player's eyes.
        Vec3 pos = eye.add(ux * 0.3, 0.0, uz * 0.3);
        double distance = Math.sqrt((tx - pos.x) * (tx - pos.x) + (tz - pos.z) * (tz - pos.z));
        double pitch = Math.toRadians(solvePitchDegrees(distance, waterSurfaceY(target) - eye.y));
        double horizontalVelocity = 0.6 * Math.cos(pitch) + 0.5;
        double verticalVelocity = -Math.tan(pitch) * horizontalVelocity;
        double travelled = 0.0;

        for (int tick = 0; tick < MAX_FLIGHT_TICKS; tick++) {
            verticalVelocity -= FISHING_GRAVITY;
            double fraction = Math.min(1.0, (distance - travelled) / horizontalVelocity);
            Vec3 next = pos.add(ux * horizontalVelocity * fraction,
                    verticalVelocity * fraction, uz * horizontalVelocity * fraction);
            HitResult hit = player.level().clip(new ClipContext(pos, next,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.MISS) return false;
            travelled += horizontalVelocity * fraction;
            if (travelled >= distance - 1.0e-6) return true;
            pos = next;
            horizontalVelocity *= FISHING_DRAG;
            verticalVelocity *= FISHING_DRAG;
        }
        return false;
    }

    private static double castPitchDegrees(Vec3 eye, BlockPos target) {
        double dx = target.getX() + 0.5 - eye.x;
        double dz = target.getZ() + 0.5 - eye.z;
        double distance = Math.max(0.1, Math.sqrt(dx * dx + dz * dz) - 0.3);
        return solvePitchDegrees(distance, waterSurfaceY(target) - eye.y);
    }

    static double solvePitchDegrees(double horizontalDistance, double targetHeight) {
        double low = -45.0;
        double high = 55.0;
        for (int i = 0; i < 32; i++) {
            double mid = (low + high) * 0.5;
            double height = trajectoryHeightAtDistance(horizontalDistance, mid);
            if (height > targetHeight) {
                low = mid;  // trajectory is high: aim farther down
            } else {
                high = mid;
            }
        }
        return (low + high) * 0.5;
    }

    static double trajectoryHeightAtDistance(double horizontalDistance, double pitchDegrees) {
        double pitch = Math.toRadians(pitchDegrees);
        double horizontalVelocity = 0.6 * Math.cos(pitch) + 0.5;
        double verticalVelocity = -Math.tan(pitch) * horizontalVelocity;
        double travelled = 0.0;
        double height = 0.0;
        for (int tick = 0; tick < MAX_FLIGHT_TICKS; tick++) {
            verticalVelocity -= FISHING_GRAVITY;
            double nextDistance = travelled + horizontalVelocity;
            double nextHeight = height + verticalVelocity;
            if (nextDistance >= horizontalDistance) {
                double fraction = (horizontalDistance - travelled) / horizontalVelocity;
                return height + verticalVelocity * fraction;
            }
            travelled = nextDistance;
            height = nextHeight;
            horizontalVelocity *= FISHING_DRAG;
            verticalVelocity *= FISHING_DRAG;
        }
        return Double.NEGATIVE_INFINITY;
    }

    private static double waterSurfaceY(BlockPos target) {
        return target.getY() + WATER_SURFACE_OFFSET;
    }

    static boolean isBiteWindow(int nibbleTicks) {
        return nibbleTicks > 0;
    }

    private void discardHook() {
        FishingHook hook = player.fishing;
        if (hook != null) {
            hook.discard();
            if (player.fishing == hook) player.fishing = null;
        }
    }

    @Override
    public void suspend() {
        boolean wasPositioning = phase == Phase.POSITION;
        boolean wasCollecting = phase == Phase.COLLECT;
        super.suspend();
        discardHook();
        if (wasCollecting) {
            // Survival preemption may stop the navigator, but the already-caught
            // drops remain the same bounded sub-goal when the LLM task resumes.
            stopNav();
        } else if (!wasPositioning) {
            resetPositioning();
        }
    }

    @Override
    protected void cleanup() {
        InputDriver.halt(player);
        discardHook();
        clearLootTracking();
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("requested", r.requested);
        data.put("caught", r.caught());
        data.put("casts", r.casts());
        return data;
    }

    @Override
    protected String successMessage() {
        return "completed " + r.caught() + " successful fishing catch(es)";
    }

    @Override
    protected String timeoutMessage() {
        return "fishing timed out after " + r.caught() + "/" + r.requested + " successful catches";
    }

    @Override
    protected String cancelledMessage() {
        return "fishing interrupted after " + r.caught() + "/" + r.requested + " successful catches";
    }
}
