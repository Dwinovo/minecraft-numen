package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.mining.MiningAttemptLedger;
import com.dwinovo.numen.core.mining.MiningArrivalStability;
import com.dwinovo.numen.core.mining.ActiveMiningTargets;
import com.dwinovo.numen.core.mining.MiningDropTarget;
import com.dwinovo.numen.core.mining.MiningDropTarget.Mode;
import com.dwinovo.numen.core.mining.MiningGeometry;
import com.dwinovo.numen.core.mining.MiningGeometry.Point;
import com.dwinovo.numen.core.mining.MiningTargetIdentity;
import com.dwinovo.numen.core.mining.MiningTargetOrder;
import com.dwinovo.numen.core.mining.RecentMiningTargets;
import com.dwinovo.numen.core.pathing.execute.PathingCore;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.task.FailureType;
import com.dwinovo.numen.core.task.MineBlockTaskRecord;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.dwinovo.numen.core.task.MineCompanionTask")
public abstract class ReliableMineMixin extends AbstractCompanionTask<MineBlockTaskRecord> {
    private static final int NUMEN_MAX_TARGET_ATTEMPTS = 3;
    private static final int NUMEN_MAX_STANCES = 16;
    private static final int NUMEN_ARRIVAL_SETTLE_TICKS = 20;

    @Shadow @Final private List<BlockPos> knownOres;
    @Shadow @Final private Set<BlockPos> blacklist;
    @Shadow @Final private Set<BlockPos> unharvestable;
    @Shadow private List<BlockPos> drops;

    @Unique private MiningAttemptLedger numen$attemptLedger;
    @Unique private MiningArrivalStability numen$arrivalStability;
    @Unique private MiningDropTarget numen$dropPickup;
    @Unique private Mode numen$dropPickupMode;
    @Unique private String numen$dropFailureReason;
    @Unique private BlockPos numen$currentTarget;
    @Unique private int numen$allowedHorizontalMiningRing;

    protected ReliableMineMixin(NumenPlayer player, MineBlockTaskRecord record) {
        super(player, record);
    }

    @Inject(method = "onStart", at = @At("HEAD"))
    private void numen$registerActiveTargetTypes(CallbackInfo callback) {
        ActiveMiningTargets.begin(
            this.player.getUUID(),
            this.r.targets.stream()
                .map(block -> BuiltInRegistries.BLOCK.getKey(block).toString())
                .toList()
        );
    }

    @Inject(method = "cleanup", at = @At("TAIL"))
    private void numen$clearActiveTargetTypes(CallbackInfo callback) {
        ActiveMiningTargets.clear(this.player.getUUID());
    }

    /** Selects a visible target only after reaching a stable mining stance. */
    @Overwrite
    private BlockPos reachableTarget() {
        if (!this.player.onGround()) {
            return null;
        }
        numen$arrivalStability().reset();

        Vec3 eye = this.player.getEyePosition();
        Point feet = numen$point(this.player.blockPosition());
        int allowedRing = Math.max(1, this.numen$allowedHorizontalMiningRing);
        List<BlockPos> reachable = new ArrayList<>();
        for (BlockPos target : this.knownOres) {
            if (!numen$isEligibleTarget(target)) {
                continue;
            }
            Point point = numen$point(target);
            if (!MiningGeometry.withinHorizontalRing(feet, point, allowedRing)
                || !MiningGeometry.withinStableMiningReach(
                    this.player.getX(),
                    this.player.getY(),
                    this.player.getZ(),
                    point
                )
                || !MiningGeometry.withinReach(eye.x, eye.y, eye.z, point)
                || !numen$hasLineOfSight(eye, target)) {
                continue;
            }
            reachable.add(target);
        }
        return numen$orderedTarget(reachable);
    }

    /**
     * Plans to one explicit block at a time and approaches from a reachable stance
     * around it. This keeps navigation failures attributable to a concrete target.
     */
    @Overwrite
    private GoalCompiler.Compiled oreFieldCompiled() {
        numen$pruneInvalidTargets();
        if (this.knownOres.isEmpty()) {
            if (!this.drops.isEmpty()) {
                if (this.numen$dropPickupMode == null) {
                    this.numen$dropPickupMode = numen$dropPickup().beginAttempt();
                }
                if (this.numen$dropPickupMode == Mode.BROAD) {
                    return GoalCompiler.anyOf(new ArrayList<>(this.drops));
                }
                Point pickupFeet = MiningDropTarget.selectPickupFeet(
                    this.drops.stream().map(ReliableMineMixin::numen$point).toList(),
                    numen$point(this.player.blockPosition())
                );
                if (pickupFeet != null) {
                    return GoalCompiler.standOn(
                        new BlockPos(pickupFeet.x(), pickupFeet.y(), pickupFeet.z())
                    );
                }
            }
            return GoalCompiler.standOn(this.player.blockPosition());
        }

        BlockPos target = numen$selectTarget();
        List<BlockPos> feetCandidates = numen$stanceCandidates(target);
        if (feetCandidates.isEmpty()) {
            feetCandidates = List.of(this.player.blockPosition());
        }

        List<GoalCompiler.Stance> stances = new ArrayList<>(feetCandidates.size());
        for (BlockPos feet : feetCandidates) {
            stances.add(new GoalCompiler.Stance(target, feet, 0));
        }
        return GoalCompiler.mineField(stances, List.of());
    }

    /**
     * Defers a failed target so other blocks can change the route, then records a
     * final coordinate-specific failure after a bounded number of rounds.
     */
    @Overwrite
    private void blacklistNearest() {
        BlockPos target = this.numen$currentTarget;
        if (target == null || !this.knownOres.contains(target)) {
            target = this.knownOres.stream()
                .min(Comparator.comparingDouble(this.player.blockPosition()::distSqr))
                .orElse(null);
        }
        if (target == null) {
            return;
        }

        if (this.nav == null
            && numen$arrivalStability().shouldWait(target.asLong(), this.player.onGround())) {
            return;
        }
        numen$arrivalStability().reset();

        String reason;
        if (this.nav == null) {
            reason = "reached a planned stance but the target remained out of reach or line of sight";
        } else {
            reason = this.nav.failType() + ": " + this.nav.failReason();
        }

        Point point = numen$point(target);
        MiningAttemptLedger.Decision decision = numen$ledger().recordFailure(point, reason);
        if (decision == MiningAttemptLedger.Decision.FINAL_FAILURE) {
            this.blacklist.add(target.immutable());
            this.knownOres.remove(target);
        }
        this.numen$currentTarget = null;
        this.numen$allowedHorizontalMiningRing = 1;
    }

    @Inject(method = "mineProgress", at = @At("TAIL"))
    private void numen$recordVerifiedRemoval(BlockPos target, CallbackInfo callback) {
        if (!this.player.level().getBlockState(target).isAir()) {
            return;
        }
        ServerLevel level = this.player.level();
        RecentMiningTargets.record(
            this.player.getUUID(),
            level.dimension().identifier().toString(),
            target.getX(),
            target.getY(),
            target.getZ(),
            level.getGameTime()
        );
        numen$ledger().recordSuccess(numen$point(target));
        numen$dropPickup().reset();
        this.numen$dropPickupMode = null;
        this.numen$dropFailureReason = null;
        numen$cancelMiningPath();
        if (target.equals(this.numen$currentTarget)) {
            this.numen$currentTarget = null;
        }
        this.numen$allowedHorizontalMiningRing = 1;
    }

    @Inject(method = "mineProgress", at = @At("HEAD"), cancellable = true)
    private void numen$rejectChangedOrTemporaryTarget(BlockPos target, CallbackInfo callback) {
        if (numen$isEligibleTarget(target)) {
            return;
        }

        this.knownOres.remove(target);
        numen$cancelMiningPath();
        if (target.equals(this.numen$currentTarget)) {
            this.numen$currentTarget = null;
        }
        this.numen$allowedHorizontalMiningRing = 1;
        callback.cancel();
    }

    @Inject(method = "prune", at = @At("TAIL"))
    private void numen$pruneTemporaryTargets(CallbackInfo callback) {
        numen$pruneInvalidTargets();
    }

    @Inject(method = "onTick", at = @At("RETURN"), cancellable = true)
    private void numen$rejectPartialSuccess(CallbackInfoReturnable<TaskState> callback) {
        if (callback.getReturnValue() != TaskState.SUCCESS
            || MiningAttemptLedger.isComplete(this.r.getMined(), this.r.count)) {
            return;
        }
        fail(numen$incompleteMessage(), numen$dominantFailureType());
        callback.setReturnValue(TaskState.FAILED);
    }

    @Inject(
        method = "onTick",
        at = @At(
            value = "INVOKE",
            target = "Lcom/dwinovo/numen/core/task/MineCompanionTask;reachableTarget()Lnet/minecraft/core/BlockPos;",
            ordinal = 0
        ),
        cancellable = true
    )
    private void numen$stopAfterBoundedDropPickup(CallbackInfoReturnable<TaskState> callback) {
        MiningDropTarget pickup = numen$dropPickup();
        if (!this.knownOres.isEmpty()
            || this.nav != null
            || !pickup.attempted()
            || (!pickup.exhausted() && !this.drops.isEmpty())) {
            return;
        }

        int unresolved = Math.max(0, this.r.count - this.r.getMined());
        if (unresolved == 0) {
            return;
        }
        this.numen$dropFailureReason = MiningDropTarget.failureReason(
            this.drops.stream().map(ReliableMineMixin::numen$point).toList()
        );
        FailureType type = this.drops.isEmpty() ? FailureType.TARGET_LOST : FailureType.OUT_OF_REACH;
        fail(numen$incompleteMessage(), type);
        callback.setReturnValue(TaskState.FAILED);
    }

    @Inject(method = "stopNav", at = @At("TAIL"))
    private void numen$clearCurrentDropPickupMode(CallbackInfo callback) {
        this.numen$dropPickupMode = null;
    }

    @Inject(method = "noOreFailure", at = @At("HEAD"), cancellable = true)
    private void numen$describeEveryFailure(CallbackInfoReturnable<TaskState> callback) {
        fail(numen$incompleteMessage(), numen$dominantFailureType());
        callback.setReturnValue(TaskState.FAILED);
    }

    @Inject(method = "resultData", at = @At("RETURN"))
    private void numen$addCompletionContract(CallbackInfoReturnable<Map<String, Object>> callback) {
        Map<String, Object> result = callback.getReturnValue();
        int unresolved = Math.max(0, this.r.count - this.r.getMined());
        result.put("complete", unresolved == 0);
        result.put("unresolved", unresolved);
        result.put("failures", numen$failureEntries());
    }

    @Unique
    private BlockPos numen$selectTarget() {
        numen$pruneInvalidTargets();
        if (this.numen$currentTarget != null
            && this.knownOres.contains(this.numen$currentTarget)
            && !numen$ledger().isDeferred(numen$point(this.numen$currentTarget))) {
            return this.numen$currentTarget;
        }

        this.numen$currentTarget = numen$orderedTarget(
            this.knownOres.stream()
                .filter(target -> !numen$ledger().isDeferred(numen$point(target)))
                .toList()
        );
        if (this.numen$currentTarget != null) {
            return this.numen$currentTarget;
        }

        numen$ledger().startNextRound();
        this.numen$currentTarget = numen$orderedTarget(this.knownOres);
        return this.numen$currentTarget;
    }

    @Unique
    private BlockPos numen$orderedTarget(List<BlockPos> candidates) {
        Point selected = MiningTargetOrder.select(
            candidates.stream().map(ReliableMineMixin::numen$point).toList(),
            numen$point(this.player.blockPosition())
        );
        if (selected == null) {
            return null;
        }
        return candidates.stream()
            .filter(candidate -> numen$point(candidate).equals(selected))
            .findFirst()
            .orElse(null);
    }

    @Unique
    private List<BlockPos> numen$stanceCandidates(BlockPos target) {
        this.numen$allowedHorizontalMiningRing = 1;
        ServerLevel level = this.player.level();
        List<BlockPos> visibleStandable = new ArrayList<>();
        List<BlockPos> visibleScaffold = new ArrayList<>();
        List<BlockPos> standable = new ArrayList<>();
        List<BlockPos> scaffold = new ArrayList<>();

        for (Point point : MiningGeometry.candidateFeet(numen$point(target))) {
            BlockPos feet = new BlockPos(point.x(), point.y(), point.z());
            if (!BlockHelper.canWalkThrough(level, feet)
                || !BlockHelper.canWalkThrough(level, feet.above())) {
                continue;
            }

            boolean hasSight = numen$hasLineOfSight(
                new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5),
                target
            );
            if (BlockHelper.isStandable(level, feet)) {
                (hasSight ? visibleStandable : standable).add(feet);
            } else if (!BlockHelper.isHazard(level, feet.below())) {
                (hasSight ? visibleScaffold : scaffold).add(feet);
            }
        }

        Comparator<BlockPos> byPlayerDistance = Comparator.comparingDouble(
            this.player.blockPosition()::distSqr
        );
        visibleStandable.sort(byPlayerDistance);
        visibleScaffold.sort(byPlayerDistance);
        standable.sort(byPlayerDistance);
        scaffold.sort(byPlayerDistance);

        if (!visibleStandable.isEmpty()) {
            return numen$limit(numen$closestHorizontalRing(visibleStandable, target));
        }
        if (!visibleScaffold.isEmpty()) {
            return numen$limit(numen$closestHorizontalRing(visibleScaffold, target));
        }
        if (!standable.isEmpty()) {
            return numen$limit(numen$closestHorizontalRing(standable, target));
        }
        return numen$limit(numen$closestHorizontalRing(scaffold, target));
    }

    @Unique
    private boolean numen$hasLineOfSight(Vec3 eye, BlockPos target) {
        BlockHitResult hit = this.player.level().clip(new ClipContext(
            eye,
            Vec3.atCenterOf(target),
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            this.player
        ));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    @Unique
    private List<BlockPos> numen$limit(List<BlockPos> candidates) {
        int end = Math.min(NUMEN_MAX_STANCES, candidates.size());
        return List.copyOf(candidates.subList(0, end));
    }

    @Unique
    private List<BlockPos> numen$closestHorizontalRing(List<BlockPos> candidates, BlockPos target) {
        Point targetPoint = numen$point(target);
        List<Point> closest = MiningGeometry.closestHorizontalRing(
            candidates.stream().map(ReliableMineMixin::numen$point).toList(),
            targetPoint
        );
        if (!closest.isEmpty()) {
            for (int ring = 1; ring <= 2; ring++) {
                if (MiningGeometry.withinHorizontalRing(closest.getFirst(), targetPoint, ring)) {
                    this.numen$allowedHorizontalMiningRing = ring;
                    break;
                }
            }
        }
        return closest.stream()
            .map(point -> new BlockPos(point.x(), point.y(), point.z()))
            .toList();
    }

    @Unique
    private MiningAttemptLedger numen$ledger() {
        if (this.numen$attemptLedger == null) {
            this.numen$attemptLedger = new MiningAttemptLedger(NUMEN_MAX_TARGET_ATTEMPTS);
        }
        return this.numen$attemptLedger;
    }

    @Unique
    private MiningArrivalStability numen$arrivalStability() {
        if (this.numen$arrivalStability == null) {
            this.numen$arrivalStability = new MiningArrivalStability(NUMEN_ARRIVAL_SETTLE_TICKS);
        }
        return this.numen$arrivalStability;
    }

    @Unique
    private MiningDropTarget numen$dropPickup() {
        if (this.numen$dropPickup == null) {
            this.numen$dropPickup = new MiningDropTarget();
        }
        return this.numen$dropPickup;
    }

    @Unique
    private void numen$pruneInvalidTargets() {
        boolean currentTargetInvalid = this.numen$currentTarget != null
            && !numen$isEligibleTarget(this.numen$currentTarget);
        this.knownOres.removeIf(target -> !numen$isEligibleTarget(target));
        if (currentTargetInvalid || (this.numen$currentTarget != null
            && !this.knownOres.contains(this.numen$currentTarget))) {
            numen$cancelMiningPath();
            this.numen$currentTarget = null;
            this.numen$allowedHorizontalMiningRing = 1;
        }
    }

    @Unique
    private void numen$cancelMiningPath() {
        stopNav();
        for (PathingCore core : PathingCore.liveCores()) {
            if (core.player().getUUID().equals(this.player.getUUID())) {
                core.forceCancel();
            }
        }
    }

    @Unique
    private boolean numen$isEligibleTarget(BlockPos target) {
        ServerLevel level = this.player.level();
        return MiningTargetIdentity.isEligible(
            this.player.getUUID(),
            level.dimension().identifier().toString(),
            target.getX(),
            target.getY(),
            target.getZ(),
            this.r.targets.contains(level.getBlockState(target).getBlock())
        );
    }

    @Unique
    private List<Map<String, Object>> numen$failureEntries() {
        LinkedHashMap<Point, String> failures = new LinkedHashMap<>();
        failures.putAll(numen$ledger().failures());
        for (BlockPos target : this.unharvestable) {
            failures.putIfAbsent(numen$point(target), "wrong or missing tool for this block");
        }
        for (BlockPos target : this.blacklist) {
            failures.putIfAbsent(numen$point(target), "no path or legal mining stance after retries");
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        failures.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator
                .comparingInt(Point::x)
                .thenComparingInt(Point::y)
                .thenComparingInt(Point::z)))
            .forEach(entry -> {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("x", entry.getKey().x());
                detail.put("y", entry.getKey().y());
                detail.put("z", entry.getKey().z());
                detail.put("reason", entry.getValue());
                entries.add(detail);
            });

        int unresolved = Math.max(0, this.r.count - this.r.getMined());
        if (this.numen$dropFailureReason != null && unresolved > 0) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("count", unresolved);
            detail.put("reason", this.numen$dropFailureReason);
            entries.add(detail);
        }
        int undiscovered = this.numen$dropFailureReason == null
            ? Math.max(0, unresolved - entries.size())
            : 0;
        if (undiscovered > 0) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("count", undiscovered);
            detail.put(
                "reason",
                "no additional " + this.r.label + " targets were found in the loaded search range"
            );
            entries.add(detail);
        }
        return List.copyOf(entries);
    }

    @Unique
    private String numen$incompleteMessage() {
        List<Map<String, Object>> failures = numen$failureEntries();
        StringBuilder message = new StringBuilder()
            .append("incomplete: gathered ")
            .append(this.r.getMined())
            .append('/')
            .append(this.r.count)
            .append(' ')
            .append(this.r.label)
            .append("; unresolved: ");
        for (int i = 0; i < failures.size(); i++) {
            if (i > 0) {
                message.append(" | ");
            }
            Map<String, Object> failure = failures.get(i);
            if (failure.containsKey("x")) {
                message.append('(')
                    .append(failure.get("x")).append(',')
                    .append(failure.get("y")).append(',')
                    .append(failure.get("z")).append("): ");
            } else {
                message.append(failure.get("count")).append(" target(s): ");
            }
            message.append(failure.get("reason"));
        }
        return message.toString();
    }

    @Unique
    private FailureType numen$dominantFailureType() {
        if (!this.unharvestable.isEmpty()) {
            return FailureType.WRONG_TOOL;
        }
        if (!this.blacklist.isEmpty() || !numen$ledger().failures().isEmpty()) {
            return FailureType.NO_PATH;
        }
        return FailureType.MINED_OUT;
    }

    @Unique
    private static Point numen$point(BlockPos position) {
        return new Point(position.getX(), position.getY(), position.getZ());
    }
}
