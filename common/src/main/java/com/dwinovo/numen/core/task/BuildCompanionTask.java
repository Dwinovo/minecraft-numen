package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.act.BlockDigger;
import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.core.act.PlaceResolution;
import com.dwinovo.numen.core.act.Placement;
import com.dwinovo.numen.core.pathing.bridge.ContextFactory;
import com.dwinovo.numen.core.pathing.cache.LoadedOnlyView;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.execute.PathExecutor;
import com.dwinovo.numen.core.pathing.execute.AimProcessor;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.moves.movements.BuildPlacementRegistry;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.task.TaskState;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Multi-block construction task that owns planning, clearing, placement, and material choice. */
public final class BuildCompanionTask extends AbstractCompanionTask<BuildTaskRecord>
        implements BuildPlacementRegistry.Provider, PlayerNav.ContextProvider {

    private static final AimProcessor AIM = new AimProcessor();
    private static final double WALK_SPEED = 1.0;
    private static final int MAX_NO_SHOT_TICKS = 20;
    private static final int MAX_EMPTY_ARRIVALS = 6;
    private static final int POST_BREAK_PLACE_DELAY_TICKS = 5;
    private static final int LOCAL_ACTION_RADIUS = 5;
    private static final double DISTANCE_TRIM_SQR = 200.0;
    /** Give up (rather than spin to the deadline) if no cell completes for this long. */
    private static final int STALL_LIMIT_TICKS = 30 * 20;
    private static final Direction[] PLACE_GOAL_FACES = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN
    };
    private static final float[] PLACEMENT_YAWS = {0.0f, 90.0f, 180.0f, -90.0f};
    private static final float[] PLACEMENT_PITCHES = {-75.0f, 0.0f, 75.0f};
    private static final double[] PLACEMENT_FACE_SAMPLES = {0.25, 0.5, 0.75};

    private final Map<Long, BuildTaskRecord.Target> targetByPos = new LinkedHashMap<>();
    private final BlockDigger digger;
    private final Set<BlockPos> noSupport = new HashSet<>();

    private Set<BlockPos> incorrectPositions;
    private LongOpenHashSet observedCompleted;
    private int minY;
    private int maxY;
    private int layerTop;
    private boolean providerRegistered;
    private BuildTaskRecord.Target activeBreak;
    private BlockPos noShotPos;
    private int noShotTicks;
    private int emptyArrivalTicks;
    private int placeDelayTicks;
    private int stallTicks;
    private int highWaterCompleted;
    private String note = "done";

    public BuildCompanionTask(NumenPlayer player, BuildTaskRecord record) {
        super(player, record);
        this.digger = new BlockDigger(player);
        for (BuildTaskRecord.Target target : record.targets) {
            targetByPos.put(target.pos().asLong(), target);
        }
    }

    @Override
    protected List<Precondition> preconditions() {
        return List.of(this::checkExistingBlocks);
    }

    private Precondition.Failure checkExistingBlocks() {
        if (r.replaceExisting) {
            return null;
        }
        for (BuildTaskRecord.Target target : r.targets) {
            BlockState state = player.level().getBlockState(target.pos());
            if (!target.matches(state) && (isAirTarget(target) || !isReplaceable(target.pos(), state))) {
                return new Precondition.Failure("target " + target.shortPos()
                        + " is occupied; enable replacement or clear it first", FailureType.TARGET_LOST);
            }
        }
        return null;
    }

    @Override
    protected void onStart() {
        minY = r.targets.stream().mapToInt(t -> t.pos().getY()).min().orElse(playerFeet().getY());
        maxY = r.targets.stream().mapToInt(t -> t.pos().getY()).max().orElse(minY);
        layerTop = usesLayers() ? Math.min(maxY, minY + Math.max(1, r.layerHeight) - 1) : maxY;
        incorrectPositions = null;
        observedCompleted = new LongOpenHashSet();
        stallTicks = 0;
        registerProvider();
        updateCompleted();
        highWaterCompleted = r.completed();
        advanceFinishedLayers();
    }

    @Override
    protected TaskState onTick() {
        // SNEAK 每 tick 重新决策:默认站立,仅放置、及破坏时若上一 tick 仍在蹲才按下——
        // 避免搭完/动作间残留蹲姿(输入为持久状态,不像客户端 force-state 会自动清零)。
        boolean wasSneaking = player.isShiftKeyDown();
        player.setShiftKeyDown(false);
        registerProvider();
        updateCompleted();
        tickPlaceDelay();
        if (r.completed() >= r.targets.size()) {
            note = "all requested cells match";
            return TaskState.SUCCESS;
        }
        // 停滞检测:以"完成格数"为进展信号(放/清可空转,门那种放了又清也不算进展),
        // 连续 STALL_LIMIT_TICKS 无新完成就优雅失败退出,别空转到 deadline。
        if (r.completed() > highWaterCompleted) {
            highWaterCompleted = r.completed();
            stallTicks = 0;
        } else if (++stallTicks >= STALL_LIMIT_TICKS) {
            fail("stalled: no build progress for " + (STALL_LIMIT_TICKS / 20) + "s ("
                    + note + "); completed " + r.completed() + "/" + r.targets.size(),
                    FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        advanceFinishedLayers();
        if (!recalc()) {
            updateCompleted();
            if (r.completed() >= r.targets.size()) {
                note = "all requested cells match";
                return TaskState.SUCCESS;
            }
            return TaskState.RUNNING;
        }
        trimIncorrectPositions();

        if (activeBreak != null) {
            if (!canInterruptPath()) {
                activeBreak = null;
                digger.cancel();
                return TaskState.RUNNING;
            }
            return tickBreak(wasSneaking);
        }

        BuildTaskRecord.Target breakTarget = localBreakCandidate();
        if (breakTarget != null) {
            emptyArrivalTicks = 0;
            stopNav();
            activeBreak = breakTarget;
            return tickBreak(wasSneaking);
        }

        // 破块后的落定延迟只抑制"就近放置",导航照常推进(不整体静止)。
        LocalPlacement placeTarget = placeDelayTicks > 0 ? null : localPlaceCandidate();
        if (placeTarget != null) {
            emptyArrivalTicks = 0;
            stopNav();
            return tickPlace(placeTarget);
        }

        if (nav == null) {
            GoalCompiler.Compiled compiled = buildCompiled();
            if (compiled == null) {
                return failNoWork();
            }
            nav = PlayerNav.toRevalidating(player, this::buildCompiled, WALK_SPEED,
                    this::hasLocalWorkWindow, this);
            nav.setHighlights(this::activePositions);
        }

        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> {
                stopNav();
                if (!hasLocalWorkWindow()) {
                    if (++emptyArrivalTicks >= MAX_EMPTY_ARRIVALS) {
                        note = "waiting for a usable build angle";
                    } else {
                        note = "rechecking nearby build angles";
                    }
                    yield TaskState.RUNNING;
                } else {
                    emptyArrivalTicks = 0;
                    yield TaskState.RUNNING;
                }
            }
            case FAILED -> {
                String reason = nav.failReason();
                emptyArrivalTicks = 0;
                stopNav();
                note = "waiting for a reachable build stance" + (reason == null || reason.isBlank()
                        ? "" : ": " + reason);
                yield TaskState.RUNNING;
            }
        };
    }

    private TaskState tickBreak(boolean wasSneaking) {
        BuildTaskRecord.Target target = activeBreak;
        BlockState state = player.level().getBlockState(target.pos());
        if (target.matches(state) || state.isAir() || state.getBlock() instanceof LiquidBlock) {
            markObserved(target, target.matches(state));
            activeBreak = null;
            digger.cancel();
            return TaskState.RUNNING;
        }
        if (!r.replaceExisting) {
            fail("target " + target.shortPos() + " is occupied and replacement is disabled",
                    FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        Vec3 aim = MovementHelper.reachableAimPoint(player, target.pos());
        if (aim == null) {
            activeBreak = null;
            digger.cancel();
            return TaskState.RUNNING;
        }
        if (wasSneaking) {
            // 破坏时保持上一 tick 的蹲姿:眼高在放置(蹲)↔破坏之间不跳变,
            // 目标块不会因半格眼高差瞬时脱离射线可达而反复打空。
            player.setShiftKeyDown(true);
        }
        if (!readyToBreak(target.pos(), aim)) {
            applySteppedAim(aim);
            return TaskState.RUNNING;
        }
        if (!safeToBreak(target.pos())) {
            fail("clearing " + target.shortPos() + " would break a protected block",
                    FailureType.HAZARD);
            return TaskState.FAILED;
        }
        switch (digger.digTargetStep(target.pos())) {
            case BROKE_TARGET -> {
                r.brokeOne();
                markObserved(target, false);
                activeBreak = null;
                placeDelayTicks = POST_BREAK_PLACE_DELAY_TICKS;
                clearNoShot();
            }
            case BROKE_OCCLUDER -> {
                r.brokeOne();
                placeDelayTicks = POST_BREAK_PLACE_DELAY_TICKS;
                clearNoShot();
            }
            case NO_SHOT -> {
                if (target.pos().equals(noShotPos)) {
                    if (++noShotTicks >= MAX_NO_SHOT_TICKS) {
                        activeBreak = null;
                        digger.cancel();
                        clearNoShot();
                        fail("could not keep a clear line to clear " + target.shortPos()
                                + "; completed " + r.completed() + "/" + r.targets.size(),
                                FailureType.OCCLUDED);
                        return TaskState.FAILED;
                    }
                } else {
                    noShotPos = target.pos();
                    noShotTicks = 1;
                }
            }
            case PROGRESSING -> clearNoShot();
        }
        return TaskState.RUNNING;
    }

    private TaskState tickPlace(LocalPlacement placement) {
        BuildTaskRecord.Target target = placement.target();
        if (target.matches(player.level().getBlockState(target.pos()))) {
            markObserved(target, true);
            return TaskState.RUNNING;
        }
        if (!hasItem(target.item(), true) || !isReplaceable(target.pos())) {
            return TaskState.RUNNING;
        }
        int slot = matchingSlotForHit(target, placement.resolution().hit(),
                placement.resolution().yaw(), placement.resolution().pitch(), true);
        if (slot < 0) {
            return TaskState.RUNNING;
        }
        if (!readyToPlace(placement)) {
            player.setShiftKeyDown(true);
            applySteppedAim(placement.resolution());
            note = "turning toward a build face";
            return TaskState.RUNNING;
        }
        InputDriver.halt(player);
        player.setShiftKeyDown(true);
        player.holdInHand(slot);
        Interaction use = Interaction.useBlock(player, placement.resolution().hit(), InteractionHand.MAIN_HAND);
        use.tick();
        if (target.matches(player.level().getBlockState(target.pos()))) {
            r.placedOne();
            noSupport.clear();
            markObserved(target, true);
        } else {
            note = "waiting for the placed block to appear";
        }
        return TaskState.RUNNING;
    }
    private boolean hasLocalWorkWindow() {
        return localBreakCandidate() != null || localPlaceCandidate() != null;
    }

    private BuildTaskRecord.Target localBreakCandidate() {
        if (!canInterruptPath()) {
            return null;
        }
        BlockPos center = playerFeet();
        BlockPos protectedSupport = NavSettings.get().breakFromAbove ? breakFromAboveProtectedSupport() : null;
        int minDy = NavSettings.get().breakFromAbove ? -1 : 0;
        int radius = LOCAL_ACTION_RADIUS;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = minDy; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (pos.equals(protectedSupport)) {
                        continue;
                    }
                    BuildTaskRecord.Target target = targetAt(pos);
                    if (target != null && isIncorrect(target) && needsBreak(target)
                            && r.replaceExisting
                            && MovementHelper.reachableAimPoint(player, target.pos()) != null) {
                        return target;
                    }
                }
            }
        }
        return null;
    }

    private LocalPlacement localPlaceCandidate() {
        if (!canInterruptPath()) {
            return null;
        }
        BlockPos center = playerFeet();
        int radius = LOCAL_ACTION_RADIUS;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= 1; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BuildTaskRecord.Target target = targetAt(pos);
                    if (target == null || !isIncorrect(target) || !canStartLocalPlace(target)) {
                        continue;
                    }
                    if (dy == 1 && player.level().getBlockState(pos.above()).isAir()) {
                        continue;
                    }
                    LocalPlacement placement = resolveLocalPlacement(target);
                    if (placement != null) {
                        return placement;
                    }
                }
            }
        }
        return null;
    }

    private LocalPlacement resolveLocalPlacement(BuildTaskRecord.Target target) {
        if (blockedByOtherEntity(target.pos(), target.desiredState())) {
            return null;
        }
        if (!placementPlausible(target.pos(), target.desiredState())) {
            return null;
        }
        PlaceResolution resolution = Placement.resolveDetailed(player, target.pos(), true, aimY(target),
                hit -> matchingSlotForHit(target, hit, null, null, true) >= 0);
        if (!resolution.ok()) {
            return null;
        }
        int slot = matchingSlotForHit(target, resolution.hit(), resolution.yaw(), resolution.pitch(), true);
        return slot < 0 || !nextTickCanReach(resolution) ? null : new LocalPlacement(target, resolution, slot);
    }
    private boolean canInterruptPath() {
        return player.onGround() && (nav == null || nav.isSafeToCancel());
    }

    private GoalCompiler.Compiled buildCompiled() {
        BuildPlan plan = assemblePlan(false);
        if (!plan.hasGoal()) {
            plan = assemblePlan(true);
        }
        NavGoal goal = plan.goal(this);
        if (goal == null) {
            return null;
        }
        return new GoalCompiler.Compiled(goal, protectedCells(), null, false);
    }

    private BuildPlan assemblePlan(boolean wholeInventory) {
        BuildPlan plan = new BuildPlan();
        Set<Item> available = availableItems(wholeInventory);
        if (incorrectPositions == null) {
            return plan;
        }
        List<BlockPos> outOfBounds = new ArrayList<>();
        List<BuildTaskRecord.Target> completed = new ArrayList<>();
        for (BlockPos pos : incorrectPositions) {
            BuildTaskRecord.Target target = targetAt(pos);
            if (target == null || (usesLayers() && target.pos().getY() > layerTop)) {
                outOfBounds.add(pos);
                continue;
            }
            BlockState state = player.level().getBlockState(pos);
            if (target.matches(state)) {
                // 遍历中不动 incorrectPositions,命中格先记下,循环后统一登记(避免并发改集合)。
                completed.add(target);
                continue;
            }
            if (state.getBlock() instanceof LiquidBlock) {
                if (!MovementHelper.possiblyFlowing(state)) {
                    plan.sourceLiquids.add(pos);
                } else {
                    plan.flowingLiquids.add(pos);
                }
            } else if (isAirTarget(target)) {
                if (r.replaceExisting) {
                    plan.breakable.add(target);
                }
            } else if (!state.isAir() && r.replaceExisting) {
                plan.breakable.add(target);
            } else if (isReplaceable(pos, state)) {
                if (noSupport.contains(pos)) {
                    // 支撑面失败单列,交给 failNoWork 的 noSupport 分支诊断。
                } else if (available.contains(target.item())
                        && canCreateDesiredState(target)) {
                    // 有料且能产期望态即计 placeable(不查 canSurvive,缺支撑面的格留给放置搜索);
                    plan.placeable.add(target);
                } else {
                    // 没有该物品或无法从背包产出期望态才算缺料(二分,不静默丢目标)。
                    plan.missing.merge(target.item(), 1, Integer::sum);
                }
            } else if (r.replaceExisting) {
                plan.breakable.add(target);
            }
        }
        incorrectPositions.removeAll(outOfBounds);
        for (BuildTaskRecord.Target done : completed) {
            markObserved(done, true);
        }
        return plan;
    }

    private LongSet protectedCells() {
        return LongSets.emptySet();
    }

    private NavGoal placementGoal(BuildTaskRecord.Target target) {
        BlockPos pos = target.pos();
        if (!player.level().getBlockState(pos).isAir()) {
            return goalPlace(pos);
        }
        boolean allowSameLevel = !player.level().getBlockState(pos.above()).isAir();
        for (Direction facing : PLACE_GOAL_FACES) {
            BlockPos against = pos.relative(facing);
            if (MovementHelper.canPlaceAgainst(player.level(), against)
                    && placementPlausible(pos, target.desiredState())) {
                return goalAdjacent(pos, against, allowSameLevel);
            }
        }
        return goalPlace(pos);
    }

    private NavGoal breakGoal(BlockPos pos) {
        NavSettings settings = NavSettings.get();
        if (settings.breakFromAbove && settings.goalBreakFromAbove
                && player.level().getBlockState(pos.above()).isAir()
                && player.level().getBlockState(pos.above(2)).isAir()) {
            return new JankyGoal(goalBreak(pos), goalBreakFromAbove(pos));
        }
        return goalBreak(pos);
    }

    private NavGoal goalBreak(BlockPos target) {
        BlockPos t = target.immutable();
        return new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                if (feet.getY() > t.getY()) {
                    return false;
                }
                return getToBlockMembership(t, feet);
            }

            @Override public double heuristic(BlockPos from) {
                return Math.max(0.0, NavGoal.pointBound(t, from)
                        - NavGoal.COST_HEURISTIC - NavGoal.JUMP_ONE_BLOCK);
            }

            @Override public BlockPos center() {
                return t;
            }
        };
    }

    private NavGoal goalBreakFromAbove(BlockPos target) {
        BlockPos t = target.above().immutable();
        return new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                if (feet.getY() > t.getY() || feet.equals(t)) {
                    return false;
                }
                return getToBlockMembership(t, feet);
            }

            @Override public double heuristic(BlockPos from) {
                return Math.max(0.0, NavGoal.pointBound(t, from)
                        - NavGoal.COST_HEURISTIC - NavGoal.JUMP_ONE_BLOCK);
            }

            @Override public BlockPos center() {
                return t;
            }
        };
    }

    private NavGoal goalAdjacent(BlockPos target, BlockPos no, boolean allowSameLevel) {
        BlockPos t = target.immutable();
        BlockPos excluded = no.immutable();
        return new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                if (feet.equals(t) || feet.equals(excluded)) {
                    return false;
                }
                if (!allowSameLevel && feet.getY() == t.getY() - 1) {
                    return false;
                }
                if (feet.getY() < t.getY() - 1) {
                    return false;
                }
                return getToBlockMembership(t, feet);
            }

            @Override public double heuristic(BlockPos from) {
                return t.getY() * 100.0 + Math.max(0.0, NavGoal.pointBound(t, from)
                        - NavGoal.COST_HEURISTIC - NavGoal.JUMP_ONE_BLOCK);
            }

            @Override public BlockPos center() {
                return t;
            }
        };
    }

    private NavGoal goalPlace(BlockPos placeAt) {
        BlockPos t = placeAt.above().immutable();
        return new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                return feet.equals(t);
            }

            @Override public double heuristic(BlockPos from) {
                return t.getY() * 100.0 + NavGoal.pointBound(t, from);
            }

            @Override public BlockPos center() {
                return t;
            }
        };
    }

    private static boolean getToBlockMembership(BlockPos target, BlockPos feet) {
        int dx = Math.abs(feet.getX() - target.getX());
        int dz = Math.abs(feet.getZ() - target.getZ());
        int dy = feet.getY() - target.getY();
        int bodyDy = dy < 0 ? dy + 1 : dy;
        return dx + dz + Math.abs(bodyDy) <= 1;
    }

    private TaskState failNoWork() {
        BuildPlan plan = assemblePlan(true);
        if (!plan.missing.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<Item, Integer> entry : plan.missing.entrySet()) {
                parts.add(label(entry.getKey()) + " x" + entry.getValue());
            }
            String message = "missing building materials: " + String.join(", ", parts);
            if (skipFailedLayer(message)) {
                return TaskState.RUNNING;
            }
            note = message;
            return TaskState.RUNNING;
        }
        if (!plan.flowingLiquids.isEmpty()) {
            String message = "flowing liquid blocks build cell at " + plan.flowingLiquids.get(0).toShortString();
            if (skipFailedLayer(message)) {
                return TaskState.RUNNING;
            }
            note = message;
            return TaskState.RUNNING;
        }
        if (!noSupport.isEmpty()) {
            noSupport.clear();
            String message = "no usable support face for a build cell";
            if (skipFailedLayer(message)) {
                return TaskState.RUNNING;
            }
            note = message;
            return TaskState.RUNNING;
        }
        String message = "no reachable build cell is currently available";
        if (skipFailedLayer(message)) {
            return TaskState.RUNNING;
        }
        note = message;
        return TaskState.RUNNING;
    }

    private boolean skipFailedLayer(String reason) {
        if (!usesLayers() || !NavSettings.get().skipFailedLayers || layerTop >= maxY) {
            return false;
        }
        int skippedTop = layerTop;
        layerTop = Math.min(maxY, layerTop + Math.max(1, r.layerHeight));
        incorrectPositions = null;
        stopNav();
        noSupport.clear();
        placeDelayTicks = 0;
        clearNoShot();
        note = "skipped layer ending at " + skippedTop + ": " + reason;
        return true;
    }

    private boolean recalc() {
        if (incorrectPositions == null) {
            fullRecalc();
            return !incorrectPositions.isEmpty();
        }
        recalcNearby();
        if (incorrectPositions.isEmpty()) {
            fullRecalc();
        }
        return !incorrectPositions.isEmpty();
    }

    private void recalcNearby() {
        BlockPos center = playerFeet();
        int radius = scanRadius();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BuildTaskRecord.Target target = targetAt(pos);
                    if (target == null || (usesLayers() && target.pos().getY() > layerTop)) {
                        continue;
                    }
                    markObserved(target, target.matches(player.level().getBlockState(target.pos())));
                }
            }
        }
    }

    private void fullRecalc() {
        incorrectPositions = new LinkedHashSet<>();
        BlockGetter view = LoadedOnlyView.of(player.level());
        LoadedOnlyView loadedView = view instanceof LoadedOnlyView v ? v : null;
        for (BuildTaskRecord.Target target : activeLayerTargets()) {
            BlockPos pos = target.pos();
            boolean loaded = loadedView == null || loadedView.isLoaded(pos.getX(), pos.getZ());
            if (loaded) {
                BlockState state = view.getBlockState(pos);
                if (target.matches(state)) {
                    observedCompleted.add(pos.asLong());
                } else {
                    incorrectPositions.add(pos);
                    observedCompleted.remove(pos.asLong());
                }
                if (incorrectPositions.size() > incorrectLimit()) {
                    return;
                }
            } else if (!observedCompleted.contains(pos.asLong())) {
                incorrectPositions.add(pos);
                if (incorrectPositions.size() > incorrectLimit()) {
                    return;
                }
            }
        }
    }

    private void markObserved(BuildTaskRecord.Target target, boolean completed) {
        if (completed) {
            noSupport.clear();
        }
        if (observedCompleted == null) {
            observedCompleted = new LongOpenHashSet();
        }
        if (completed) {
            observedCompleted.add(target.pos().asLong());
            if (incorrectPositions != null) {
                incorrectPositions.remove(target.pos());
            }
        } else {
            observedCompleted.remove(target.pos().asLong());
            if (incorrectPositions != null && (!usesLayers() || target.pos().getY() <= layerTop)) {
                incorrectPositions.add(target.pos());
            }
        }
    }

    private void trimIncorrectPositions() {
        if (incorrectPositions == null || incorrectPositions.isEmpty() || !NavSettings.get().distanceTrim) {
            return;
        }
        Set<BlockPos> copy = new LinkedHashSet<>(incorrectPositions);
        BlockPos feet = playerFeet();
        copy.removeIf(pos -> pos.distSqr(feet) > DISTANCE_TRIM_SQR);
        if (!copy.isEmpty()) {
            incorrectPositions = copy;
        }
    }

    private int scanRadius() {
        return Math.max(0, NavSettings.get().builderTickScanRadius);
    }

    private BlockPos breakFromAboveProtectedSupport() {
        BlockPos start = nav == null
                ? com.dwinovo.numen.core.pathing.moves.Movement.pathStart(player)
                : nav.pathStart();
        return start.below();
    }

    private int incorrectLimit() {
        return Math.max(1, NavSettings.get().incorrectSize);
    }

    private void advanceFinishedLayers() {
        if (!usesLayers()) {
            return;
        }
        while (layerTop < maxY && layerComplete()) {
            layerTop = Math.min(maxY, layerTop + Math.max(1, r.layerHeight));
            incorrectPositions = null;
            stopNav();
            noSupport.clear();
        }
    }

    private boolean layerComplete() {
        BlockGetter view = LoadedOnlyView.of(player.level());
        LoadedOnlyView loadedView = view instanceof LoadedOnlyView v ? v : null;
        for (BuildTaskRecord.Target target : r.targets) {
            if (target.pos().getY() > layerTop) {
                continue;
            }
            BlockPos pos = target.pos();
            boolean loaded = loadedView == null || loadedView.isLoaded(pos.getX(), pos.getZ());
            if (loaded) {
                boolean completed = target.matches(view.getBlockState(pos));
                markObserved(target, completed);
                if (!completed) {
                    return false;
                }
            } else if (observedCompleted == null || !observedCompleted.contains(pos.asLong())) {
                return false;
            }
        }
        return true;
    }
    private List<BuildTaskRecord.Target> activeLayerTargets() {
        List<BuildTaskRecord.Target> out = new ArrayList<>();
        for (BuildTaskRecord.Target target : r.targets) {
            if (!usesLayers() || target.pos().getY() <= layerTop) {
                out.add(target);
            }
        }
        out.sort(Comparator
                .comparingInt((BuildTaskRecord.Target t) -> t.pos().getY())
                .thenComparingDouble(t -> t.pos().distSqr(playerFeet())));
        return out;
    }

    private List<BlockPos> activePositions() {
        List<BlockPos> out = new ArrayList<>();
        if (incorrectPositions != null) {
            out.addAll(incorrectPositions);
            return out;
        }
        for (BuildTaskRecord.Target target : activeLayerTargets()) {
            if (!target.matches(player.level().getBlockState(target.pos()))) {
                out.add(target.pos());
            }
        }
        return out;
    }

    private BuildTaskRecord.Target targetAt(BlockPos pos) {
        BuildTaskRecord.Target target = targetByPos.get(pos.asLong());
        return target != null && (!usesLayers() || target.pos().getY() <= layerTop) ? target : null;
    }

    private boolean isIncorrect(BuildTaskRecord.Target target) {
        return incorrectPositions == null || incorrectPositions.contains(target.pos());
    }

    private boolean usesLayers() {
        return r.layerHeight > 0;
    }

    private boolean canStartLocalPlace(BuildTaskRecord.Target target) {
        return !noSupport.contains(target.pos())
                && !isAirTarget(target)
                && !target.matches(player.level().getBlockState(target.pos()))
                && (player.level().getBlockState(target.pos()).isAir() || !r.replaceExisting)
                && isReplaceable(target.pos())
                && hasItem(target.item(), true)
                && placementPossible(target);
    }

    private boolean placementPossible(BuildTaskRecord.Target target) {
        BlockState state = target.desiredState();
        return state.canSurvive(player.level(), target.pos())
                && canCreateDesiredState(target);
    }

    private boolean canCreateDesiredState(BuildTaskRecord.Target target) {
        if (isAirTarget(target)) {
            return true;
        }
        if (!(target.item() instanceof BlockItem)) {
            return false;
        }
        Inventory inventory = player.getInventory();
        int limit = inventoryScanLimit(true);
        for (int i = 0; i < limit; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(target.item()) && stackCanCreateDesiredState(target, stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean stackCanCreateDesiredState(BuildTaskRecord.Target target, ItemStack stack) {
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        try {
            for (float yaw : PLACEMENT_YAWS) {
                for (float pitch : PLACEMENT_PITCHES) {
                    player.setYRot(yaw);
                    player.setXRot(pitch);
                    for (Direction support : Direction.values()) {
                        for (double sample : PLACEMENT_FACE_SAMPLES) {
                            BlockHitResult hit = syntheticPlacementHit(target.pos(), support, sample);
                            BlockState placed = predictedState(stack, hit, null, null);
                            if (placed != null && target.acceptsPlacedState(placed)) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        } finally {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
    }
    private BlockHitResult syntheticPlacementHit(BlockPos placeAt, Direction support, double sample) {
        BlockPos against = placeAt.relative(support);
        double x = (placeAt.getX() + against.getX() + 1.0) * 0.5;
        double y = (placeAt.getY() + against.getY() + 1.0) * 0.5;
        double z = (placeAt.getZ() + against.getZ() + 1.0) * 0.5;
        if (support.getAxis().isHorizontal()) {
            y = placeAt.getY() + sample;
        }
        return new BlockHitResult(new Vec3(x, y, z), support.getOpposite(), against, false);
    }

    private int matchingSlotForHit(BuildTaskRecord.Target target, BlockHitResult hit,
                                   Float yaw, Float pitch, boolean wholeInventory) {
        Inventory inventory = player.getInventory();
        int limit = inventoryScanLimit(wholeInventory);
        for (int i = 0; i < limit; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !stack.is(target.item())) {
                continue;
            }
            BlockState state = predictedState(stack, hit, yaw, pitch);
            if (state != null && target.acceptsPlacedState(state)
                    && state.canSurvive(player.level(), target.pos())
                    && placementPlausible(target.pos(), state)) {
                return i;
            }
        }
        return -1;
    }

    private BlockState predictedState(ItemStack stack, BlockHitResult hit, Float yaw, Float pitch) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        try {
            if (yaw != null && pitch != null) {
                player.setYRot(yaw);
                player.setXRot(pitch);
            }
            BlockPlaceContext context = new BlockPlaceContext(new UseOnContext(
                    player.level(), player, InteractionHand.MAIN_HAND, stack, hit) {});
            BlockState state = blockItem.getBlock().getStateForPlacement(context);
            return state != null && context.canPlace() ? state : null;
        } catch (RuntimeException e) {
            return null;
        } finally {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
    }

    private int inventoryScanLimit(boolean wholeInventory) {
        return wholeInventory && NavSettings.get().allowInventory
                ? Math.min(36, player.getInventory().getNonEquipmentItems().size())
                : Math.min(9, player.getInventory().getNonEquipmentItems().size());
    }
    private Double aimY(BuildTaskRecord.Target target) {
        return target.topHalf() == null
                ? null
                : target.pos().getY() + (target.topHalf() ? 0.72 : 0.28);
    }

    private boolean lowerBlocked(BuildTaskRecord.Target target) {
        BlockPos below = target.pos().below();
        BlockPos below2 = target.pos().below(2);
        return incompleteTargetAt(below) || incompleteTargetAt(below2);
    }

    private boolean incompleteTargetAt(BlockPos pos) {
        BuildTaskRecord.Target target = targetByPos.get(pos.asLong());
        return target != null && (!usesLayers() || target.pos().getY() <= layerTop)
                && !target.matches(player.level().getBlockState(target.pos()));
    }

    private boolean needsBreak(BuildTaskRecord.Target target) {
        BlockState state = player.level().getBlockState(target.pos());
        return !(state.getBlock() instanceof LiquidBlock)
                && !target.matches(state)
                && (isAirTarget(target) || !state.isAir());
    }

    private static boolean isAirTarget(BuildTaskRecord.Target target) {
        return target.block() == Blocks.AIR;
    }

    private boolean isReplaceable(BlockPos pos) {
        return isReplaceable(pos, player.level().getBlockState(pos));
    }

    private boolean isReplaceable(BlockPos pos, BlockState state) {
        return MovementHelper.isReplaceable(pos.getX(), pos.getY(), pos.getZ(), state,
                ChunkLoadedTest.ALWAYS);
    }

    private boolean safeToBreak(BlockPos pos) {
        BuildTaskRecord.Target target = targetByPos.get(pos.asLong());
        BlockState state = player.level().getBlockState(pos);
        if (target != null && target.matches(state)) {
            return false;
        }
        return !BlockHelper.shouldAvoidBreaking(player.level(), pos);
    }

    private boolean placementPlausible(BlockPos pos, BlockState state) {
        Level level = player.level();
        VoxelShape shape = state.getCollisionShape(level, pos);
        return shape.isEmpty() || level.isUnobstructed(null,
                shape.move(pos.getX(), pos.getY(), pos.getZ()));
    }
    private boolean blockedByOtherEntity(BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(player.level(), pos);
        return !shape.isEmpty() && !player.level().isUnobstructed(player,
                shape.move(pos.getX(), pos.getY(), pos.getZ()));
    }
    private boolean hasItem(Item item) {
        return mainInventoryCount(item) > 0;
    }

    private boolean hasItem(Item item, boolean wholeInventory) {
        return wholeInventory && NavSettings.get().allowInventory ? hasItem(item) : hasItemOnHotbar(item);
    }

    private boolean hasItemOnHotbar(Item item) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                return true;
            }
        }
        return false;
    }

    private int findSlot(Item item, boolean wholeInventory) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                return i;
            }
        }
        if (wholeInventory && NavSettings.get().allowInventory) {
            return findMainInventorySlot(item);
        }
        return -1;
    }

    private int mainInventoryCount(Item item) {
        Inventory inventory = player.getInventory();
        int count = 0;
        int limit = Math.min(36, inventory.getNonEquipmentItems().size());
        for (int i = 0; i < limit; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int findMainInventorySlot(Item item) {
        Inventory inventory = player.getInventory();
        int limit = Math.min(36, inventory.getNonEquipmentItems().size());
        for (int i = 9; i < limit; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                return i;
            }
        }
        return -1;
    }

    private Set<Item> availableItems(boolean wholeInventory) {
        Set<Item> items = new HashSet<>();
        for (BuildTaskRecord.Target target : r.targets) {
            if (hasItem(target.item(), wholeInventory)) {
                items.add(target.item());
            }
        }
        return items;
    }

    private Set<BlockState> availableStates(boolean wholeInventory) {
        Set<BlockState> states = new HashSet<>();
        Inventory inventory = player.getInventory();
        int limit = wholeInventory && NavSettings.get().allowInventory ? Math.min(36, inventory.getNonEquipmentItems().size()) : 9;
        for (int i = 0; i < limit; i++) {
            addAvailableState(states, inventory.getItem(i));
        }
        return states;
    }

    private void addAvailableState(Set<BlockState> states, ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return;
        }
        try {
            BlockPos feet = playerFeet();
            BlockHitResult hit = new BlockHitResult(player.position(), Direction.UP, feet, false);
            BlockState state = blockItem.getBlock().getStateForPlacement(new BlockPlaceContext(new UseOnContext(
                    player.level(), player, InteractionHand.MAIN_HAND, stack, hit) {}));
            if (state != null) {
                states.add(state);
            }
        } catch (RuntimeException e) {
            states.add(blockItem.getBlock().defaultBlockState());
        }
    }

    private Map<Long, BuildTaskRecord.Target> activeTargetMap() {
        Map<Long, BuildTaskRecord.Target> out = new LinkedHashMap<>();
        for (BuildTaskRecord.Target target : activeLayerTargets()) {
            out.put(target.pos().asLong(), target);
        }
        return out;
    }

    private void updateCompleted() {
        if (observedCompleted == null) {
            observedCompleted = new LongOpenHashSet();
        }
        int completed = 0;
        BlockGetter view = LoadedOnlyView.of(player.level());
        LoadedOnlyView loadedView = view instanceof LoadedOnlyView v ? v : null;
        for (BuildTaskRecord.Target target : r.targets) {
            BlockPos pos = target.pos();
            boolean loaded = loadedView == null || loadedView.isLoaded(pos.getX(), pos.getZ());
            if (loaded) {
                if (target.matches(view.getBlockState(pos))) {
                    observedCompleted.add(pos.asLong());
                    completed++;
                } else {
                    observedCompleted.remove(pos.asLong());
                }
            } else if (observedCompleted.contains(pos.asLong())) {
                completed++;
            }
        }
        r.completed(completed);
    }

    private void clearNoShot() {
        noShotPos = null;
        noShotTicks = 0;
    }

    private boolean readyToBreak(BlockPos pos, Vec3 aim) {
        return currentCrosshairHits(pos) || rotationReady(aim);
    }

    private boolean readyToPlace(LocalPlacement placement) {
        return placementRaycastMatches(placement.resolution().hit(), player.getYRot(), player.getXRot());
    }

    private boolean nextTickCanReach(PlaceResolution resolution) {
        if (placementRaycastMatches(resolution.hit(), player.getYRot(), player.getXRot())) {
            return true;
        }
        if (!resolution.hasRotation()) {
            return false;
        }
        AimProcessor.Rotation next = AIM.step(player.getYRot(), player.getXRot(),
                resolution.yaw(), resolution.pitch());
        return placementRaycastMatches(resolution.hit(), next.yaw(), next.pitch());
    }

    private boolean currentCrosshairHits(BlockPos pos) {
        HitResult raw = player.pick(MovementHelper.blockReachDistance(player), 1.0f, false);
        return raw instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(pos);
    }

    private boolean placementRaycastMatches(BlockHitResult expected, float yaw, float pitch) {
        BlockHitResult hit = rayTrace(placementEye(true), yaw, pitch,
                MovementHelper.blockReachDistance(player));
        return hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(expected.getBlockPos())
                && hit.getDirection().equals(expected.getDirection());
    }

    private BlockHitResult rayTrace(Vec3 eye, float yaw, float pitch, double reach) {
        Vec3 end = eye.add(direction(yaw, pitch).scale(reach));
        return player.level().clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    }

    private Vec3 placementEye(boolean wouldSneak) {
        return wouldSneak
                ? new Vec3(player.getX(), player.getY() + player.getEyeHeight(Pose.CROUCHING), player.getZ())
                : player.getEyePosition();
    }

    private static Vec3 direction(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        return new Vec3(-Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad), Math.cos(yawRad) * cosPitch);
    }

    private boolean rotationReady(Vec3 point) {
        Vec3 eye = player.getEyePosition();
        return rotationReady(MovementHelper.yawTo(eye, point), MovementHelper.pitchTo(eye, point));
    }

    private boolean rotationReady(Float yaw, Float pitch) {
        if (yaw == null || pitch == null) {
            return false;
        }
        return Math.abs(AimProcessor.normalizeDelta(player.getYRot() - yaw)) < 0.01f
                && Math.abs(player.getXRot() - pitch) < 0.01f;
    }

    private void applySteppedAim(PlaceResolution resolution) {
        if (resolution.hasRotation()) {
            applySteppedAim(resolution.yaw(), resolution.pitch());
        } else {
            applySteppedAim(resolution.hit().getLocation());
        }
    }

    private void applySteppedAim(Vec3 point) {
        Vec3 eye = player.getEyePosition();
        applySteppedAim(MovementHelper.yawTo(eye, point), MovementHelper.pitchTo(eye, point));
    }

    private void applySteppedAim(float yaw, float pitch) {
        AimProcessor.Rotation next = AIM.step(player.getYRot(), player.getXRot(), yaw, pitch);
        player.setYRot(next.yaw());
        player.setYHeadRot(next.yaw());
        player.setXRot(next.pitch());
    }
    private BlockPos playerFeet() {
        return PathExecutor.playerFeet(player);
    }




    private void tickPlaceDelay() {
        if (placeDelayTicks > 0) {
            placeDelayTicks--;
        }
    }

    private void registerProvider() {
        if (!providerRegistered) {
            BuildPlacementRegistry.register(player, this);
            providerRegistered = true;
        }
    }

    private void unregisterProvider() {
        if (providerRegistered) {
            BuildPlacementRegistry.unregister(player, this);
            providerRegistered = false;
        }
    }

    @Override
    public BlockState desiredState(BlockPos placeAt) {
        BuildTaskRecord.Target target = targetByPos.get(placeAt.asLong());
        if (target == null || (usesLayers() && target.pos().getY() > layerTop)) {
            return null;
        }
        if (isAirTarget(target)) {
            return null;
        }
        if (target.matches(player.level().getBlockState(target.pos()))) {
            return null;
        }
        return target.desiredState();
    }

    @Override
    public boolean acceptsPlacement(BlockPos placeAt, BlockState state) {
        BuildTaskRecord.Target target = targetByPos.get(placeAt.asLong());
        return target != null && target.acceptsPlacedState(state);
    }

    @Override
    public CalculationContext forSearch(NumenPlayer player, LongSet sacred, LongSet deniedPlace) {
        return ContextFactory.forSearch(player, sacred, deniedPlace,
                (p, view, loaded, safe, s, denied) -> new BuildCalculationContext(
                        p, view, loaded, safe, s, denied, activeTargetMap(), availableStates(true), r.replaceExisting));
    }

    @Override
    public CalculationContext forExecution(NumenPlayer player, LongSet sacred, LongSet deniedPlace) {
        return ContextFactory.forExecution(player, sacred, deniedPlace,
                (p, view, loaded, safe, s, denied) -> new BuildCalculationContext(
                        p, view, loaded, safe, s, denied, activeTargetMap(), availableStates(true), r.replaceExisting));
    }

    @Override
    public void suspend() {
        super.suspend();
        unregisterProvider();
    }

    @Override
    public void resume() {
        registerProvider();
    }

    @Override
    protected void cleanup() {
        super.cleanup();
        unregisterProvider();
        digger.cancel();
        player.setShiftKeyDown(false);
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("requested", r.targets.size());
        data.put("completed", r.completed());
        data.put("placed", r.placed());
        data.put("cleared", r.broken());
        data.put("layer_top", layerTop);
        data.put("layered", usesLayers());
        return data;
    }

    @Override
    protected String successMessage() {
        return "built " + r.completed() + "/" + r.targets.size()
                + " block(s); placed " + r.placed() + ", cleared " + r.broken()
                + " (" + note + ")";
    }

    @Override
    protected String timeoutMessage() {
        return "timed out while building; completed " + r.completed() + "/" + r.targets.size()
                + " (" + note + ")";
    }

    @Override
    protected String cancelledMessage() {
        return "build interrupted after " + r.completed() + "/" + r.targets.size() + " block(s)";
    }

    private static String label(Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    private static final class JankyGoal implements NavGoal {
        private final NavGoal primary;
        private final NavGoal fallback;

        JankyGoal(NavGoal primary, NavGoal fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }

        @Override public boolean isAt(BlockPos feet) {
            return primary.isAt(feet) || fallback.isAt(feet);
        }

        @Override public double heuristic(BlockPos from) {
            return primary.heuristic(from);
        }

        @Override public BlockPos center() {
            return primary.center();
        }
    }

    private record LocalPlacement(BuildTaskRecord.Target target, PlaceResolution resolution, int slot) {}

    private static final class BuildPlan {
        private final List<BuildTaskRecord.Target> placeable = new ArrayList<>();
        private final List<BuildTaskRecord.Target> breakable = new ArrayList<>();
        private final List<BlockPos> sourceLiquids = new ArrayList<>();
        private final List<BlockPos> flowingLiquids = new ArrayList<>();
        private final Map<Item, Integer> missing = new LinkedHashMap<>();

        boolean hasGoal() {
            return !placeable.isEmpty() || !breakable.isEmpty() || !sourceLiquids.isEmpty();
        }

        List<BuildTaskRecord.Target> targets() {
            List<BuildTaskRecord.Target> out = new ArrayList<>(placeable.size() + breakable.size());
            out.addAll(placeable);
            out.addAll(breakable);
            return out;
        }

        private boolean hasPlaceable(BlockPos pos) {
            for (BuildTaskRecord.Target target : placeable) {
                if (target.pos().equals(pos)) {
                    return true;
                }
            }
            return false;
        }

        NavGoal goal(BuildCompanionTask task) {
            List<NavGoal> toPlace = new ArrayList<>();
            for (BuildTaskRecord.Target target : placeable) {
                if (!hasPlaceable(target.pos().below()) && !hasPlaceable(target.pos().below(2))) {
                    toPlace.add(task.placementGoal(target));
                }
            }
            for (BlockPos liquid : sourceLiquids) {
                // 源液体格用无 y 加权的精确目标(站到其上方格),填液优先级不被 y*100 压过。
                toPlace.add(NavGoal.exact(liquid.above()));
            }
            List<NavGoal> toBreak = new ArrayList<>();
            for (BuildTaskRecord.Target target : breakable) {
                toBreak.add(task.breakGoal(target.pos()));
            }
            if (!toPlace.isEmpty()) {
                NavGoal primary = toPlace.size() == 1 ? toPlace.get(0) : NavGoal.composite(toPlace);
                if (toBreak.isEmpty()) {
                    return primary;
                }
                NavGoal fallback = toBreak.size() == 1 ? toBreak.get(0) : NavGoal.composite(toBreak);
                return new JankyGoal(primary, fallback);
            }
            if (toBreak.isEmpty()) {
                return null;
            }
            return toBreak.size() == 1 ? toBreak.get(0) : NavGoal.composite(toBreak);
        }
    }
}