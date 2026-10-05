package com.dwinovo.numen.core.task.fish;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.core.FailureType;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.mixin.FishingHookAccessor;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.nav.Terrain;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.body.Hotbar;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.task.Preparation;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code numen.work.fish}:站在原地用钓竿抛一竿——对准、抛竿、等咬钩、收线,钓上来就收工,回执说钓上来的是什么。它不走动:站的地方
 * 得是干的、抛得进水面,受理之前({@link #preparation})就判,不成就当场拒绝,说清要先站到岸边。这一竿没落进水、钩到了实体、等不来
 * 咬钩,就如实失败;再抛一竿、钓几条是程序里一圈一圈调它。收线时原版把战果甩向她,落在半路的留在地上,{@code numen.work.collect}
 * 去捡;它不去追。
 */
public final class FishCompanionTask extends AbstractCompanionTask<FishTaskRecord> {

    private enum Phase { PREPARE, AIM, WAIT }

    private static final int CAST_SEARCH_RADIUS = 10;
    private static final int CAST_SEARCH_Y = 4;
    private static final double MIN_CAST_DISTANCE = 4.0;
    private static final double IDEAL_CAST_DISTANCE = 6.0;
    private static final double WATER_SURFACE_OFFSET = 0.85;
    private static final int AIM_TICKS = 3;
    private static final int CAST_SETTLE_TIMEOUT = 5 * 20;
    /** 一竿至多等多久的咬钩;任务的期限照它给({@link FishTaskRecord})。 */
    static final int CAST_LIFETIME = 60 * 20;


    private static final double FISHING_DRAG = 0.92;
    private static final double FISHING_GRAVITY = 0.03;
    private static final int MAX_FLIGHT_TICKS = 80;

    private Phase phase = Phase.PREPARE;
    /** 她站着钓的那一格:受理时脚下那一格,离开它就不钓了。 */
    private BlockPos stance;
    private BlockPos target;
    private int phaseTicks;
    /** 收线时甩上来的东西:{@code minecraft:cod x1}。 */
    private final List<String> caught = new ArrayList<>();

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

        player.controls().stop();

        int rodSlot = findRodSlot();
        if (rodSlot < 0) {
            discardHook();
            fail("fishing stopped because there is no fishing rod left", FailureType.WRONG_TOOL);
            return TaskState.FAILED;
        }
        Hotbar.hold(player, rodSlot);
        if (!player.getMainHandItem().is(Items.FISHING_ROD)) return TaskState.RUNNING;

        return switch (phase) {
            case PREPARE -> prepare();
            case AIM -> aimAndCast();
            case WAIT -> waitForBite();
        };
    }

    /**
     * 受理之前:身上有鱼竿(前置条件),她站的地方是干的、从这儿抛得进水面。不成就当场回那句话,不受理;成就记下站位与落点。
     */
    @Override
    protected Preparation preparation() {
        BlockPos here = feet();
        if (!isDryStance(here)) {
            return Preparation.refused(TaskResult.fail(NOT_DRY));
        }
        BlockPos water = findCastTarget(here, player.getEyePosition());
        if (water == null) {
            return Preparation.refused(TaskResult.fail(com.dwinovo.numen.agent.script.ErrorKind.NOT_FOUND, NO_WATER,
                    null));
        }
        stance = here;
        target = water;
        return Preparation.READY;
    }

    /** 站的地方不干时说的那句话。 */
    private static final String NOT_DRY = "I do not stand on dry ground here, and fishing does not move me: stand on "
            + "the shore with open water " + (int) MIN_CAST_DISTANCE + "-" + CAST_SEARCH_RADIUS + " blocks away "
            + "(numen.scan.blocks finds water; numen.move.to takes you there), then numen.work.fish again";

    /** 从这儿抛不进水面时说的那句话。 */
    private static final String NO_WATER = "no open water to cast into " + (int) MIN_CAST_DISTANCE + "-"
            + CAST_SEARCH_RADIUS + " blocks from where I stand, and fishing does not move me: stand on the shore "
            + "facing open water (numen.scan.blocks finds water; numen.move.to takes you there), then numen.work.fish again";

    private TaskState prepare() {
        if (player.fishing != null) {
            discardHook();
            return TaskState.RUNNING;
        }

        BlockPos current = feet();
        if (stance == null) {
            // 重启后接回来的活不经受理前的准备:站位就是此刻脚下
            stance = current;
        }
        if (!current.equals(stance) || !isDryStance(current)) {
            fail("I was moved off the spot I fished from (" + stance.toShortString() + "), and fishing does not "
                    + "walk me back", FailureType.OUT_OF_REACH);
            return TaskState.FAILED;
        }
        if (target == null || !isCastableSurface(target)
                || !trajectoryClear(player.getEyePosition(), target)) {
            target = findCastTarget(stance, player.getEyePosition());
        }
        if (target == null) {
            fail(NO_WATER, FailureType.OUT_OF_REACH);
            return TaskState.FAILED;
        }

        phase = Phase.AIM;
        phaseTicks = 0;
        aimAtTarget();
        return TaskState.RUNNING;
    }

    private TaskState aimAndCast() {
        if (!isCastableSurface(target) || !trajectoryClear(player.getEyePosition(), target)) {
            return failedCast("the selected water surface became obstructed");
        }
        aimAtTarget();
        if (++phaseTicks < AIM_TICKS) return TaskState.RUNNING;

        double pitch = castPitchDegrees(player.getEyePosition(), target);
        player.gameMode.useItem(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
        if (player.fishing == null) {
            return failedCast("the fishing rod did not cast");
        }
        Constants.LOG.debug("[numen-fish] cast target={} pitch={}",
                target.toShortString(), String.format(java.util.Locale.ROOT, "%.1f", pitch));
        phase = Phase.WAIT;
        phaseTicks = 0;
        return TaskState.RUNNING;
    }

    private TaskState waitForBite() {
        FishingHook hook = player.fishing;
        if (hook == null || hook.isRemoved()) {
            return failedCast("the fishing hook disappeared before a catch");
        }
        phaseTicks++;

        Entity hooked = hook.getHookedIn();
        if (hooked != null) {
            reelIn();
            return failedCast("the hook caught an entity instead of landing in the water");
        }

        int nibble = ((FishingHookAccessor) (Object) hook).numen$getNibble();
        if (isBiteWindow(nibble)) {
            Vec3 at = hook.position();
            reelIn();
            // 原版收线把战果就地生在浮漂那儿、甩向她:刚生出来的那几个就是这一竿钓上来的
            for (net.minecraft.world.entity.item.ItemEntity item : hook.level().getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(at, at)
                            .inflate(1.5), e -> e.tickCount == 0)) {
                caught.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem().getItem())
                        + " x" + item.getItem().getCount());
            }
            Constants.LOG.debug("[numen-fish] caught {}", caught);
            succeed();
            return TaskState.SUCCESS;
        }

        boolean inWater = hook.level().getFluidState(hook.blockPosition()).is(FluidTags.WATER);
        if (!inWater && phaseTicks >= CAST_SETTLE_TIMEOUT) {
            Constants.LOG.debug("[numen-fish] miss hook={} on_ground={} age={}",
                    hook.blockPosition().toShortString(), hook.onGround(), phaseTicks);
            return failedCast("the fishing hook did not settle in water");
        }
        if (phaseTicks >= CAST_LIFETIME) {
            return failedCast("no bite came in " + CAST_LIFETIME / 20 + " seconds");
        }
        return TaskState.RUNNING;
    }

    /** 这一竿没成:收回浮漂,如实失败;再抛一竿是下一次调用。 */
    private TaskState failedCast(String reason) {
        discardHook();
        fail(reason + "; numen.work.fish casts again", FailureType.OUT_OF_REACH);
        return TaskState.FAILED;
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
                    if (isCastableSurface(candidate)) {
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

    /**
     * 能站着钓鱼的干地方:出厂规格的路线会让她在这儿站着({@link Terrain#standingSpot}),脚与头所在的两格没有液体。
     */
    private boolean isDryStance(BlockPos pos) {
        Terrain terrain = Terrain.of(player);
        return terrain.state(pos).getFluidState().isEmpty() && terrain.state(pos.above()).getFluidState().isEmpty()
                && terrain.standingSpot(pos, RouteSpec.defaults());
    }

    private BlockPos feet() {
        return Feet.cell(player);
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
    public void stop(NumenPlayer companion, StopReason why) {
        super.stop(companion, why);
        discardHook();
        phase = Phase.PREPARE;
        phaseTicks = 0;
    }

    @Override
    protected void cleanup() {
        player.controls().stop();
        discardHook();
        super.cleanup();
    }

    /** 钓上来的,{@code minecraft:cod x1}:{@code numen.work.fish} 交回的值。 */
    @Override
    protected List<String> value() {
        return List.copyOf(caught);
    }

    @Override
    protected String successMessage() {
        return "reeled in " + (caught.isEmpty() ? "a catch" : String.join(", ", caught)) + "; the reel throws it to "
                + "me, and what lands short lies on the ground: `numen.work.collect()` picks it up";
    }

    @Override
    protected String timeoutMessage() {
        return "the cast timed out without a catch";
    }

    @Override
    protected String cancelledMessage() {
        return "fishing interrupted before a catch";
    }
}
