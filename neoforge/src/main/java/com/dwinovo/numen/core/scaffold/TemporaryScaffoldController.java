package com.dwinovo.numen.core.scaffold;

import com.dwinovo.numen.core.act.BlockDigger;
import com.dwinovo.numen.core.act.BlockDigger.DigResult;
import com.dwinovo.numen.core.agent.AgentTurnActivity;
import com.dwinovo.numen.core.mining.ActiveMiningTargets;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.pathing.execute.PathExecutor;
import com.dwinovo.numen.core.pathing.execute.PathingCore;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.task.base.ToolSelect;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Reclaims temporary path blocks only while the companion has no active task. */
public final class TemporaryScaffoldController {
    private static final int RECHECK_INTERVAL_TICKS = 10;
    private static final int RETRY_COOLDOWN_TICKS = 100;
    private static final int MAX_CONSECUTIVE_NO_SHOT = 3;
    private static final int RETREAT_HORIZONTAL_RADIUS = 2;
    private static final int RETREAT_VERTICAL_RANGE = 4;
    private static final double RETREAT_SPEED = 1.0D;
    private static final double STABLE_CLEANUP_REACH_SQUARED = 3.5D * 3.5D;
    private static final Map<UUID, CleanupState> STATES = new HashMap<>();

    private record Landing(boolean known, boolean hazardous, int fallDistance) {
    }

    private static final class CleanupState {
        private final NumenPlayer player;
        private final BlockDigger digger;
        private TemporaryScaffoldLedger.Entry current;
        private PlayerNav retreatNav;
        private BlockPos retreatTarget;
        private TemporaryScaffoldLedger.Entry cleanupTarget;
        private final Set<BlockPos> failedRetreatTargets = new HashSet<>();
        private long nextRetryAt;
        private int noShotCount;

        private CleanupState(NumenPlayer player) {
            this.player = player;
            this.digger = new BlockDigger(player);
        }
    }

    private TemporaryScaffoldController() {
    }

    public static boolean canRunCleanup(
        NumenPlayer player,
        boolean chainRunning,
        boolean queuePending
    ) {
        boolean asyncTaskActive = CompanionTickDispatcher.asyncTaskFor(player.getUUID()) != null;
        boolean agentTurnActive = AgentTurnActivity.isActive(
            player.getUUID(),
            player.level().getServer().getTickCount()
        );
        CleanupState state = STATES.get(player.getUUID());
        if (state != null && state.retreatNav != null) {
            return ScaffoldCleanupGate.canContinueRetreat(
                chainRunning,
                queuePending,
                asyncTaskActive,
                agentTurnActive
            );
        }

        boolean currentPathActive = false;
        boolean nextPathActive = false;
        boolean pathSearchActive = false;
        for (PathingCore core : PathingCore.liveCores()) {
            if (!core.player().getUUID().equals(player.getUUID())) {
                continue;
            }
            currentPathActive |= core.isPathing() || core.getCurrent() != null;
            nextPathActive |= core.getNext() != null;
            pathSearchActive |= core.hasInProgressSearch();
        }
        return ScaffoldCleanupGate.canRun(
            chainRunning,
            queuePending,
            asyncTaskActive,
            agentTurnActive,
            currentPathActive,
            nextPathActive,
            pathSearchActive
        );
    }

    public static void tickIdle(NumenPlayer player) {
        if (!canRunCleanup(player, false, false)) {
            pause(player);
            return;
        }

        UUID id = player.getUUID();
        List<TemporaryScaffoldLedger.Entry> entries = TemporaryScaffoldLedger.entries(id);
        if (entries.isEmpty()) {
            CleanupState removed = STATES.remove(id);
            if (removed != null) {
                cancelState(removed);
            }
            return;
        }
        if (entries.stream().noneMatch(entry -> entry.role().automaticallyReclaimable())) {
            CleanupState removed = STATES.remove(id);
            if (removed != null) {
                cancelState(removed);
            }
            return;
        }

        CleanupState state = STATES.get(id);
        if (state == null || state.player != player) {
            if (state != null) {
                cancelState(state);
            }
            state = new CleanupState(player);
            STATES.put(id, state);
        }

        long now = player.level().getGameTime();
        if (state.retreatNav != null) {
            tickRetreat(state, entries, now);
            return;
        }
        if (state.current == null
            && (now < state.nextRetryAt || now % RECHECK_INTERVAL_TICKS != 0L)) {
            return;
        }

        if (state.current == null) {
            refreshReasons(player);
            state.current = TemporaryScaffoldLedger.topmostReclaimableEntries(id).stream()
                .filter(entry -> decision(player, entry).action() == ScaffoldRemovalSafety.Action.REMOVE)
                .min(Comparator.comparingDouble(entry -> distanceToSqr(player, entry)))
                .orElse(null);
            if (state.current == null) {
                startCleanupNavigation(state, entries, now);
                return;
            }
        }

        TemporaryScaffoldLedger.Entry entry = state.current;
        ScaffoldRemovalSafety.Decision decision = decision(player, entry);
        if (decision.action() == ScaffoldRemovalSafety.Action.FORGET) {
            state.digger.cancel();
            TemporaryScaffoldLedger.remove(id, entry);
            resetCurrent(state);
            return;
        }
        if (decision.action() != ScaffoldRemovalSafety.Action.REMOVE) {
            state.digger.cancel();
            TemporaryScaffoldLedger.markReason(id, entry, decision.reason());
            resetCurrent(state);
            return;
        }

        BlockPos pos = pos(entry);
        BlockState blockState = player.level().getBlockState(pos);
        ToolSelect.holdBestTool(player, blockState);
        DigResult result = state.digger.digTargetStep(pos);
        if (result == DigResult.PROGRESSING) {
            TemporaryScaffoldLedger.markReason(id, entry, "cleanup_in_progress");
            return;
        }
        if (result == DigResult.BROKE_TARGET) {
            if (!matches(player.level(), pos, entry.placedBlockId())) {
                TemporaryScaffoldLedger.remove(id, entry);
            } else {
                TemporaryScaffoldLedger.markReason(id, entry, "break_not_confirmed");
            }
            state.failedRetreatTargets.clear();
            resetCurrent(state);
            return;
        }

        state.digger.cancel();
        state.noShotCount++;
        String reason = result == DigResult.BROKE_OCCLUDER
            ? "occluded_exact_target_not_mined"
            : "no_exact_shot";
        TemporaryScaffoldLedger.markReason(id, entry, reason);
        if (state.noShotCount >= MAX_CONSECUTIVE_NO_SHOT) {
            state.nextRetryAt = now + RETRY_COOLDOWN_TICKS;
            state.noShotCount = 0;
        }
        state.current = null;
    }

    public static void pause(NumenPlayer player) {
        CleanupState state = STATES.get(player.getUUID());
        if (state == null) {
            return;
        }
        state.digger.cancel();
        stopRetreat(state);
        state.current = null;
        state.failedRetreatTargets.clear();
    }

    public static void shutdown(NumenPlayer player) {
        UUID companionId = player.getUUID();
        CleanupState state = STATES.remove(companionId);
        if (state != null) {
            cancelState(state);
        }
        ActiveMiningTargets.clear(companionId);
        AgentTurnActivity.clear(companionId);
        TemporaryScaffoldTracker.clear(companionId);
    }

    public static void refreshReasons(NumenPlayer player) {
        UUID id = player.getUUID();
        for (TemporaryScaffoldLedger.Entry entry : TemporaryScaffoldLedger.entries(id)) {
            if (!entry.role().automaticallyReclaimable()) {
                TemporaryScaffoldLedger.markReason(
                    id,
                    entry,
                    entry.role().preservationReason()
                );
                continue;
            }
            ScaffoldRemovalSafety.Decision decision = decision(player, entry);
            if (decision.action() == ScaffoldRemovalSafety.Action.FORGET) {
                TemporaryScaffoldLedger.remove(id, entry);
            } else if (decision.action() == ScaffoldRemovalSafety.Action.REMOVE) {
                TemporaryScaffoldLedger.markReason(id, entry, "awaiting_idle_cleanup");
            } else {
                TemporaryScaffoldLedger.markReason(id, entry, decision.reason());
            }
        }
    }

    public static String explainReason(String reason) {
        if (reason.startsWith("safe_retreat_path_failed: ")) {
            return "could not reach a safe off-column cleanup stance ("
                + reason.substring("safe_retreat_path_failed: ".length()) + ')';
        }
        return switch (reason) {
            case "supports_ai_over_hazard" -> "still supports the companion above a hazard";
            case "currently_supports_ai" -> "still directly supports the companion";
            case "required_by_active_path" -> "still belongs to the current or next path";
            case "only_known_retreat" -> "removing it would cut the only known retreat";
            case "chunk_not_loaded" -> "the chunk is not loaded, so removal safety is unknown";
            case "landing_not_known" -> "the landing below cannot be determined safely";
            case "hazard_below" -> "lava, fire, powder snow, void, or another hazard is below";
            case "unsafe_fall_below" -> "removal would open an unsafe fall";
            case "currently_out_of_reach" -> "the exact block is not currently within stable reach";
            case "no_exact_shot" -> "no exact unobstructed mining ray is available";
            case "occluded_exact_target_not_mined" -> "another block occludes it and was not mined";
            case "break_not_confirmed" -> "the block-state change could not be confirmed";
            case "cleanup_in_progress" -> "safe cleanup is currently in progress";
            case "moving_to_safe_cleanup_stance" ->
                "moving to a safe off-column stance before reclaiming the support";
            case "preserved_navigation_bridge" ->
                "kept because it is a navigation bridge, not a disposable vertical support";
            case "preserved_navigation_step" ->
                "kept because it is a navigation step needed as part of the route";
            case "preserved_navigation_route" ->
                "kept because it is part of a reusable navigation route";
            case "no_safe_retreat_stance" ->
                "no loaded, non-hazardous off-column landing is currently available";
            case "no_safe_cleanup_stance" ->
                "no loaded, non-hazardous stance can currently reach the tracked support";
            case "awaiting_idle_cleanup", "pending_safety_recheck" ->
                "waiting for an idle tick to perform safe cleanup";
            default -> reason;
        };
    }

    private static ScaffoldRemovalSafety.Decision decision(
        NumenPlayer player,
        TemporaryScaffoldLedger.Entry entry
    ) {
        return ScaffoldRemovalSafety.evaluate(removalContext(player, entry));
    }

    private static ScaffoldRemovalSafety.Context removalContext(
        NumenPlayer player,
        TemporaryScaffoldLedger.Entry entry
    ) {
        ServerLevel level = player.level();
        BlockPos pos = pos(entry);
        boolean sameDimension = level.dimension().identifier().toString().equals(entry.dimensionId());
        boolean loaded = sameDimension && level.isLoaded(pos);
        if (!loaded) {
            return new ScaffoldRemovalSafety.Context(
                false, true, false, false, false, false, 0, false, false
            );
        }

        boolean blockMatches = matches(level, pos, entry.placedBlockId());
        boolean supportsPlayer = PathExecutor.playerFeet(player).below().equals(pos);
        boolean requiredByPath = requiredByActivePath(player, pos);
        Landing landing = landingAfterRemoval(level, pos);
        boolean onlyRetreat = isLikelyOnlyRetreat(player, entry);
        boolean reachable = exactReachable(player, pos);
        return new ScaffoldRemovalSafety.Context(
            true,
            blockMatches,
            supportsPlayer,
            requiredByPath,
            landing.known(),
            landing.hazardous(),
            landing.fallDistance(),
            onlyRetreat,
            reachable
        );
    }

    private static void startCleanupNavigation(
        CleanupState state,
        List<TemporaryScaffoldLedger.Entry> entries,
        long now
    ) {
        NumenPlayer player = state.player;
        if (now < state.nextRetryAt) {
            return;
        }

        boolean leavingTemporaryColumn = isStandingOnTemporaryScaffold(player);
        TemporaryScaffoldLedger.Entry cleanupTarget = leavingTemporaryColumn
            ? null
            : TemporaryScaffoldLedger.topmostReclaimableEntries(player.getUUID()).stream()
                .filter(entry -> ScaffoldRemovalSafety.canNavigateForRemoval(
                    removalContext(player, entry)
                ))
                .min(Comparator.comparingDouble(entry -> distanceToSqr(player, entry)))
                .orElse(null);
        if (!leavingTemporaryColumn && cleanupTarget == null) {
            return;
        }

        BlockPos destination = leavingTemporaryColumn
            ? safeRetreatTarget(player, entries, state.failedRetreatTargets)
            : safeCleanupTarget(
                player,
                cleanupTarget,
                entries,
                state.failedRetreatTargets
            );
        if (destination == null) {
            if (cleanupTarget == null) {
                markRetreatReason(player, entries, "no_safe_retreat_stance");
            } else {
                TemporaryScaffoldLedger.markReason(
                    player.getUUID(),
                    cleanupTarget,
                    "no_safe_cleanup_stance"
                );
            }
            state.nextRetryAt = now + RETRY_COOLDOWN_TICKS;
            return;
        }

        state.cleanupTarget = cleanupTarget;
        state.retreatTarget = destination;
        state.retreatNav = PlayerNav.to(
            player,
            () -> GoalCompiler.standOn(destination),
            RETREAT_SPEED,
            () -> reachedRetreatTarget(player, destination)
        );
        markNavigationReason(state, entries, "moving_to_safe_cleanup_stance");
    }

    private static void tickRetreat(
        CleanupState state,
        List<TemporaryScaffoldLedger.Entry> entries,
        long now
    ) {
        BlockPos target = state.retreatTarget;
        if (target == null || !isSafeRetreatFeet(state.player, target, entries)) {
            failRetreat(state, entries, now, "landing changed");
            return;
        }
        if (state.cleanupTarget != null && !isTracked(state.player, state.cleanupTarget)) {
            stopRetreat(state);
            state.nextRetryAt = 0L;
            refreshReasons(state.player);
            return;
        }

        PlayerNav.Status status = state.retreatNav.tick();
        if (status == PlayerNav.Status.RUNNING) {
            markNavigationReason(state, entries, "moving_to_safe_cleanup_stance");
            return;
        }
        if (status == PlayerNav.Status.ARRIVED && reachedRetreatTarget(state.player, target)) {
            stopRetreat(state);
            state.nextRetryAt = 0L;
            refreshReasons(state.player);
            return;
        }

        String reason = status == PlayerNav.Status.FAILED
            ? state.retreatNav.failType() + ": " + state.retreatNav.failReason()
            : "arrival was not stable";
        failRetreat(state, entries, now, reason);
    }

    private static void failRetreat(
        CleanupState state,
        List<TemporaryScaffoldLedger.Entry> entries,
        long now,
        String reason
    ) {
        if (state.retreatTarget != null) {
            state.failedRetreatTargets.add(state.retreatTarget);
        }
        markNavigationReason(state, entries, "safe_retreat_path_failed: " + reason);
        stopRetreat(state);
        state.nextRetryAt = now + RETRY_COOLDOWN_TICKS;
    }

    private static BlockPos safeCleanupTarget(
        NumenPlayer player,
        TemporaryScaffoldLedger.Entry entry,
        List<TemporaryScaffoldLedger.Entry> entries,
        Set<BlockPos> denied
    ) {
        BlockPos origin = PathExecutor.playerFeet(player);
        BlockPos target = pos(entry);
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -RETREAT_HORIZONTAL_RADIUS; dx <= RETREAT_HORIZONTAL_RADIUS; dx++) {
            for (int dz = -RETREAT_HORIZONTAL_RADIUS; dz <= RETREAT_HORIZONTAL_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int dy = 2; dy >= -RETREAT_VERTICAL_RANGE; dy--) {
                    BlockPos candidate = target.offset(dx, dy, dz);
                    if (!denied.contains(candidate)
                        && isSafeRetreatFeet(player, candidate, entries)
                        && reachableFromFeet(player, candidate, target)) {
                        candidates.add(candidate.immutable());
                    }
                }
            }
        }
        return candidates.stream()
            .min(Comparator.comparingDouble(candidate -> candidate.distSqr(origin)))
            .orElse(null);
    }

    private static BlockPos safeRetreatTarget(
        NumenPlayer player,
        List<TemporaryScaffoldLedger.Entry> entries,
        Set<BlockPos> denied
    ) {
        BlockPos origin = PathExecutor.playerFeet(player);
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -RETREAT_HORIZONTAL_RADIUS; dx <= RETREAT_HORIZONTAL_RADIUS; dx++) {
            for (int dz = -RETREAT_HORIZONTAL_RADIUS; dz <= RETREAT_HORIZONTAL_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int dy = 1; dy >= -RETREAT_VERTICAL_RANGE; dy--) {
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    if (!denied.contains(candidate)
                        && isSafeRetreatFeet(player, candidate, entries)) {
                        candidates.add(candidate.immutable());
                    }
                }
            }
        }
        return candidates.stream()
            .min(Comparator.comparingDouble(candidate -> candidate.distSqr(origin)))
            .orElse(null);
    }

    private static boolean isSafeRetreatFeet(
        NumenPlayer player,
        BlockPos feet,
        List<TemporaryScaffoldLedger.Entry> entries
    ) {
        ServerLevel level = player.level();
        BlockPos head = feet.above();
        BlockPos support = feet.below();
        if (!level.isLoaded(feet) || !level.isLoaded(head) || !level.isLoaded(support)) {
            return false;
        }
        String dimensionId = level.dimension().identifier().toString();
        boolean temporaryColumn = entries.stream().anyMatch(entry ->
            entry.role().automaticallyReclaimable()
                && entry.dimensionId().equals(dimensionId)
                && entry.x() == feet.getX()
                && entry.z() == feet.getZ()
        );
        if (temporaryColumn
            || !BlockHelper.canWalkThrough(level, feet)
            || !BlockHelper.canWalkThrough(level, head)
            || !BlockHelper.isStandable(level, feet)
            || BlockHelper.isHazard(level, support)) {
            return false;
        }
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(head);
        BlockState supportState = level.getBlockState(support);
        return feetState.getFluidState().isEmpty()
            && headState.getFluidState().isEmpty()
            && !supportState.getFluidState().is(FluidTags.LAVA)
            && !isHazard(supportState);
    }

    private static boolean reachedRetreatTarget(NumenPlayer player, BlockPos target) {
        return player.onGround() && PathExecutor.playerFeet(player).equals(target);
    }

    private static boolean isStandingOnTemporaryScaffold(NumenPlayer player) {
        ServerLevel level = player.level();
        BlockPos support = PathExecutor.playerFeet(player).below();
        return TemporaryScaffoldLedger.containsReclaimable(
            player.getUUID(),
            level.dimension().identifier().toString(),
            support.getX(),
            support.getY(),
            support.getZ()
        );
    }

    private static void markRetreatReason(
        NumenPlayer player,
        List<TemporaryScaffoldLedger.Entry> entries,
        String reason
    ) {
        UUID id = player.getUUID();
        for (TemporaryScaffoldLedger.Entry entry : entries) {
            String currentReason = decision(player, entry).reason();
            if (currentReason.equals("currently_supports_ai")
                || currentReason.equals("only_known_retreat")) {
                TemporaryScaffoldLedger.markReason(id, entry, reason);
            }
        }
    }

    private static void markNavigationReason(
        CleanupState state,
        List<TemporaryScaffoldLedger.Entry> entries,
        String reason
    ) {
        if (state.cleanupTarget == null) {
            markRetreatReason(state.player, entries, reason);
            return;
        }
        TemporaryScaffoldLedger.markReason(
            state.player.getUUID(),
            state.cleanupTarget,
            reason
        );
    }

    private static boolean requiredByActivePath(NumenPlayer player, BlockPos scaffold) {
        BlockPos feet = scaffold.above();
        for (PathingCore core : PathingCore.liveCores()) {
            if (!core.player().getUUID().equals(player.getUUID())) {
                continue;
            }
            if (executorRequires(core.getCurrent(), scaffold, feet)
                || executorRequires(core.getNext(), scaffold, feet)) {
                return true;
            }
        }
        return false;
    }

    private static boolean executorRequires(
        PathExecutor executor,
        BlockPos scaffold,
        BlockPos feet
    ) {
        if (executor == null) {
            return false;
        }
        if (executor.toPlace().contains(scaffold)
            || executor.toWalkInto().contains(feet)
            || executor.toWalkInto().contains(scaffold)) {
            return true;
        }
        List<BlockPos> positions = executor.getPath().positions();
        int start = Math.max(0, Math.min(executor.getPosition(), positions.size()));
        return positions.subList(start, positions.size()).stream()
            .anyMatch(position -> position.equals(feet) || position.equals(scaffold));
    }

    private static Landing landingAfterRemoval(ServerLevel level, BlockPos scaffold) {
        BlockPos.MutableBlockPos cursor = scaffold.mutable();
        for (int distance = 1; distance <= 8; distance++) {
            cursor.setY(scaffold.getY() - distance);
            if (cursor.getY() < level.getMinY()) {
                return new Landing(true, true, distance);
            }
            if (!level.isLoaded(cursor)) {
                return new Landing(false, false, distance);
            }

            BlockState state = level.getBlockState(cursor);
            if (state.getFluidState().is(FluidTags.LAVA) || isHazard(state)) {
                return new Landing(true, true, distance);
            }
            if (!state.getFluidState().isEmpty()) {
                return new Landing(true, false, 0);
            }
            if (state.isFaceSturdy(level, cursor, Direction.UP)) {
                return new Landing(true, false, distance);
            }
        }
        return new Landing(true, false, 9);
    }

    private static boolean isHazard(BlockState state) {
        return state.is(Blocks.FIRE)
            || state.is(Blocks.SOUL_FIRE)
            || state.is(Blocks.MAGMA_BLOCK)
            || state.is(Blocks.POWDER_SNOW)
            || state.is(Blocks.CACTUS)
            || state.is(Blocks.SWEET_BERRY_BUSH)
            || state.is(Blocks.WITHER_ROSE)
            || state.is(Blocks.CAMPFIRE)
            || state.is(Blocks.SOUL_CAMPFIRE)
            || state.is(Blocks.POINTED_DRIPSTONE);
    }

    private static boolean isLikelyOnlyRetreat(
        NumenPlayer player,
        TemporaryScaffoldLedger.Entry entry
    ) {
        if (player.getY() <= entry.y() + 1.0D) {
            return false;
        }
        double dx = player.getX() - (entry.x() + 0.5D);
        double dz = player.getZ() - (entry.z() + 0.5D);
        if (dx * dx + dz * dz > 64.0D) {
            return false;
        }
        return TemporaryScaffoldLedger.entries(player.getUUID()).stream()
            .filter(other -> other.role().automaticallyReclaimable())
            .anyMatch(other -> other.dimensionId().equals(entry.dimensionId())
                && other.x() == entry.x()
                && other.z() == entry.z()
                && other.y() > entry.y());
    }

    private static boolean exactReachable(NumenPlayer player, BlockPos pos) {
        return reachableFromEye(player, player.getEyePosition(), pos);
    }

    private static boolean reachableFromFeet(
        NumenPlayer player,
        BlockPos feet,
        BlockPos target
    ) {
        Vec3 eye = new Vec3(
            feet.getX() + 0.5D,
            feet.getY() + 1.62D,
            feet.getZ() + 0.5D
        );
        return reachableFromEye(player, eye, target);
    }

    private static boolean reachableFromEye(NumenPlayer player, Vec3 eye, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        if (eye.distanceToSqr(center) > STABLE_CLEANUP_REACH_SQUARED) {
            return false;
        }
        BlockHitResult hit = player.level().clip(
            new ClipContext(eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)
        );
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    private static boolean isTracked(
        NumenPlayer player,
        TemporaryScaffoldLedger.Entry entry
    ) {
        return TemporaryScaffoldLedger.contains(
            player.getUUID(),
            entry.dimensionId(),
            entry.x(),
            entry.y(),
            entry.z()
        ) && matches(player.level(), pos(entry), entry.placedBlockId());
    }

    private static boolean matches(ServerLevel level, BlockPos pos, String expectedId) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString()
            .equals(expectedId);
    }

    private static double distanceToSqr(
        NumenPlayer player,
        TemporaryScaffoldLedger.Entry entry
    ) {
        return player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos(entry)));
    }

    private static BlockPos pos(TemporaryScaffoldLedger.Entry entry) {
        return new BlockPos(entry.x(), entry.y(), entry.z());
    }

    private static void resetCurrent(CleanupState state) {
        state.digger.cancel();
        state.current = null;
        state.noShotCount = 0;
    }

    private static void stopRetreat(CleanupState state) {
        if (state.retreatNav != null) {
            state.retreatNav.stop();
        }
        state.retreatNav = null;
        state.retreatTarget = null;
        state.cleanupTarget = null;
    }

    private static void cancelState(CleanupState state) {
        state.digger.cancel();
        stopRetreat(state);
        state.current = null;
    }
}
