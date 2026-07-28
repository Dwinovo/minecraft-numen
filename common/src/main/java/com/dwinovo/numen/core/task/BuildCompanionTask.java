package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.act.BlockDigger;
import com.dwinovo.numen.core.act.Interaction;
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
    private static final int POST_BREAK_PLACE_DELAY_TICKS = 5;
    private static final int LOCAL_ACTION_RADIUS = 5;
    private static final double DISTANCE_TRIM_SQR = 200.0;
    /** Give up (rather than spin to the deadline) if no cell completes for this long. */
    private static final int STALL_LIMIT_TICKS = 30 * 20;
    /** 每次放置后的间隔刻:施工有节奏感,也天然限制每 tick 的世界写入量。 */
    private static final int PLACE_INTERVAL_TICKS = 2;
    /** 工完场清:建成后走回开工时的落脚点再报成功——站在成品屋顶上收工,交付
     *  物完好但人挂在高处,下一个指令还得先救人。回撤沿用本任务的成本上下文
     *  (挖已建对的格子有重罚金),借道破坏由近旁修补随行回填;这里是回撤的
     *  时限,走不回去就原地交付,不为收尾赖掉一个成功的任务。 */
    private static final Direction[] PLACE_GOAL_FACES = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN
    };

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
    /** 开工时的落脚点——建成后的回撤目的地。 */
    private BlockPos noShotPos;
    private int noShotTicks;
    private int placeDelayTicks;
    private int stallTicks;
    private int highWaterCompleted;
    /** 本遍施工的确定性顺序(蛇形逐层;骨架在前、贴附在后)。null = 待重建。 */
    private List<BuildTaskRecord.Target> passOrder;
    /** 游标:passOrder 中下一个要处理的下标。 */
    private int cursor;
    /** 本遍里放弃的格子(寻路不达/到场仍看不见/缺料),遍末统一裁决。 */
    private final LongOpenHashSet passSkipped = new LongOpenHashSet();
    /** 本遍缺料统计(遍末报告用)。 */
    private final Map<Item, Integer> passMissing = new LinkedHashMap<>();
    /** 遍起点的完成数,用于判定"整遍零进展"。 */
    private int passStartCompleted = -1;
    /** 连续零进展的遍数;达到上限才判"真不可达"。 */
    private int zeroProgressPasses;
    private static final int MAX_ZERO_PROGRESS_PASSES = 3;
    /** 本遍构建时的施工层顶,层推进后遍作废重排。 */
    private int passLayerTop = Integer.MIN_VALUE;
    /** 当前导航针对的游标格(换格重建导航)。 */
    private BlockPos navTargetCell;
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
        layerTop = usesLayers() ? Math.min(maxY, minY + layerHeightEffective() - 1) : maxY;
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
        } else if (nav != null && nav.planningInFlight()) {
            // 身体站着等异步搜索返回:不是停滞,是规划器在飞——这些刻不计入
            // 停滞预算(与任务 deadline 的冻结同一原则)。
        } else if (++stallTicks >= STALL_LIMIT_TICKS) {
            com.dwinovo.numen.Constants.LOG.info(
                    "[numen-task] build STALL-FUSE completed={}/{} feet={} note={}",
                    r.completed(), r.targets.size(), player.blockPosition().toShortString(), note);
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

        // —— 游标流水线:确定性顺序(蛇形逐层,骨架先、贴附后),一次盯一格 ——
        // 到位判据是"臂展内 + 眼到格心一条诚实射线"。看得见就转头挥手放,看不见
        // 就寻路换位;到场仍不行的格子跳过,遍末统一裁决——像玩家一样承认够不着,
        // 绝不原地转头等待一个永远不会命中的角度。
        if (activeBreak != null) {
            if (!canInterruptPath()) {
                activeBreak = null;
                digger.cancel();
                return TaskState.RUNNING;
            }
            if (nav != null) {
                nav.pause();
            }
            return tickBreak(wasSneaking);
        }

        ensurePassOrder();
        BuildTaskRecord.Target cur = advanceCursor();
        if (cur == null) {
            return endOfPass();
        }

        BlockState curState = player.level().getBlockState(cur.pos());
        if (needsBreak(cur) && !curState.isAir() && !(curState.getBlock() instanceof LiquidBlock)) {
            // 先清后放:格里有错块。够得着就地清,够不着走过去。
            if (canInterruptPath() && MovementHelper.reachableAimPoint(player, cur.pos()) != null) {
                if (nav != null) {
                    nav.pause();
                }
                activeBreak = cur;
                return tickBreak(wasSneaking);
            }
            return driveNavTo(cur, true);
        }

        if (!isAirTarget(cur)) {
            if (r.consumeMaterials && !hasItem(cur.item(), true)) {
                passMissing.merge(cur.item(), 1, Integer::sum);
                skipCell(cur);
                return TaskState.RUNNING;
            }
            if (placeDelayTicks == 0 && canWorkOn(cur)) {
                if (nav != null) {
                    nav.pause();
                }
                placeCursorCell(cur);
                return TaskState.RUNNING;
            }
            if (placeDelayTicks > 0 && canWorkOn(cur)) {
                return TaskState.RUNNING;   // 落定间隔:站着等一两刻,不折返
            }
            return driveNavTo(cur, false);
        }

        // 空气目标且已是空气/流体:交给 updateCompleted 判定,推进游标
        markObserved(cur, cur.matches(curState));
        return TaskState.RUNNING;
    }

    // ------------------------------------------------------------------
    // 游标流水线
    // ------------------------------------------------------------------

    /** 重建本遍顺序:活动层内目标,骨架(实心)在前、贴附(火把/门这类非实心)
     *  在后;组内蛇形——y 升序,z 升序,x 按 z 的奇偶折返(牛耕式,人不用来回
     *  跑对角)。确定性顺序没有"智能选点"可坏,也让断点续建成为可能。 */
    private void ensurePassOrder() {
        if (passOrder != null && passLayerTop == layerTop) {
            return;
        }
        List<BuildTaskRecord.Target> solids = new ArrayList<>();
        List<BuildTaskRecord.Target> decor = new ArrayList<>();
        for (BuildTaskRecord.Target target : r.targets) {
            if (usesLayers() && target.pos().getY() > layerTop) {
                continue;
            }
            if (isAirTarget(target) || isSolidish(target.desiredState())) {
                solids.add(target);
            } else {
                decor.add(target);
            }
        }
        Comparator<BuildTaskRecord.Target> snake = Comparator
                .comparingInt((BuildTaskRecord.Target t) -> t.pos().getY())
                .thenComparingInt(t -> t.pos().getZ())
                .thenComparingInt(t -> (t.pos().getZ() & 1) == 0 ? t.pos().getX() : -t.pos().getX());
        solids.sort(snake);
        decor.sort(snake);
        solids.addAll(decor);
        passOrder = solids;
        passLayerTop = layerTop;
        cursor = 0;
        passSkipped.clear();
        passMissing.clear();
        passStartCompleted = r.completed();
    }

    /** 骨架块:有碰撞体的都算;贴附物(火把、门、床、作物、地毯…)后置。 */
    private static boolean isSolidish(BlockState state) {
        return !state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                BlockPos.ZERO).isEmpty();
    }

    /** 游标推进:跳过已正确与本遍已放弃的格子,返回下一个待处理目标。 */
    private BuildTaskRecord.Target advanceCursor() {
        while (passOrder != null && cursor < passOrder.size()) {
            BuildTaskRecord.Target target = passOrder.get(cursor);
            if (passSkipped.contains(target.pos().asLong())) {
                cursor++;
                continue;
            }
            if (target.matches(player.level().getBlockState(target.pos()))) {
                markObserved(target, true);
                cursor++;
                continue;
            }
            return target;
        }
        return null;
    }

    /** 放弃当前游标格(本遍内不再回头),游标前移。 */
    private void skipCell(BuildTaskRecord.Target target) {
        passSkipped.add(target.pos().asLong());
        dropNav();
    }

    /** 一遍扫完:全部就位 → 顶部下一 tick 进回撤;有剩且整遍零进展 → 先试跳层,
     *  不能跳就快速教学失败报进度(玩家没材料/够不着也盖不完房子);有进展 → 开
     *  下一遍(补漏/重试)。 */
    private TaskState endOfPass() {
        updateCompleted();
        if (r.completed() >= r.targets.size()) {
            passOrder = null;
            return TaskState.RUNNING;
        }
        boolean progressed = r.completed() > passStartCompleted;
        if (!progressed) {
            // 单遍零进展未必是真不可达:并发搜索抢预算的瞬时枯竭也长这样。
            // 连续几遍都颗粒无收才认输——重试一遍的代价极低(已正确格秒过)。
            if (++zeroProgressPasses < MAX_ZERO_PROGRESS_PASSES) {
                passOrder = null;
                return TaskState.RUNNING;
            }
            String reason = passFailureReason();
            if (skipFailedLayer(reason)) {
                zeroProgressPasses = 0;
                passOrder = null;
                return TaskState.RUNNING;
            }
            fail(reason + "; completed " + r.completed() + "/" + r.targets.size(),
                    passMissing.isEmpty() ? FailureType.NO_PATH : FailureType.NO_MATERIAL);
            return TaskState.FAILED;
        }
        zeroProgressPasses = 0;
        passOrder = null;
        return TaskState.RUNNING;
    }

    private String passFailureReason() {
        if (!passMissing.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<Item, Integer> entry : passMissing.entrySet()) {
                parts.add(label(entry.getKey()) + " x" + entry.getValue());
            }
            return "missing building materials: " + String.join(", ", parts);
        }
        if (!passSkipped.isEmpty()) {
            return passSkipped.size() + " build cell(s) stayed out of reach or sight";
        }
        return "no build cell could be worked on this pass";
    }

    /** 就位判据(workable 唯一真源):臂展内、眼到格心一条诚实射线不被挡、且放
     *  下去的方块不与任何实体(包括自己)相撞。三个条件都随身位变化,统一在这
     *  里判——导航的到达谓词与放置分支共用,谁也不会跟谁打架:站在目标格里时
     *  这里为假,导航自然驱动"站到旁边去"。无状态一问一答,没有收敛过程,也就
     *  没有"永远转不到位"这类病。 */
    private boolean canWorkOn(BuildTaskRecord.Target target) {
        BlockPos pos = target.pos();
        Vec3 eye = player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        if (eye.distanceToSqr(center) > MovementHelper.blockReachDistance(player)
                * MovementHelper.blockReachDistance(player)) {
            return false;
        }
        if (blockedByEntity(pos, target.desiredState())
                || !placementPlausible(pos, target.desiredState())) {
            return false;
        }
        BlockHitResult hit = player.level().clip(new ClipContext(eye, center,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    /** 放下去:转头看一眼(动画,不做判定)、挥手、按期望状态写入世界、扣料。
     *  写入用 setBlock 而不是模拟右键——朝向敏感方块(楼梯/原木轴向)一次准确
     *  落位;玩家看到的真实是走过来、看着那里、方块出现、材料减少。 */
    private void placeCursorCell(BuildTaskRecord.Target target) {
        applySteppedAim(Vec3.atCenterOf(target.pos()));
        player.swing(InteractionHand.MAIN_HAND);
        int slot = r.consumeMaterials ? findSlot(target.item(), true) : -1;
        if (slot >= 0) {
            player.holdInHand(slot);
        }
        player.level().setBlock(target.pos(), target.desiredState(), 3);
        if (r.consumeMaterials) {
            consumeOne(target.item());
        }
        r.placedOne();
        noSupport.clear();
        markObserved(target, true);
        placeDelayTicks = PLACE_INTERVAL_TICKS;
        dropNav();
    }

    /** 从背包扣掉一个该物品(主手优先已由 holdInHand 保证展示,扣哪格无所谓)。 */
    private void consumeOne(Item item) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                stack.shrink(1);
                return;
            }
        }
    }

    /** 为游标格开车:换格即换导航;到场仍干不了活或寻路失败,跳过该格——
     *  绝不留在原地空等。 */
    private TaskState driveNavTo(BuildTaskRecord.Target target, boolean forBreak) {
        if (nav == null || !target.pos().equals(navTargetCell)) {
            dropNav();
            navTargetCell = target.pos();
            NavGoal goal = forBreak ? breakGoal(target.pos()) : placementGoal(target);
            nav = PlayerNav.to(player, () -> new GoalCompiler.Compiled(goal, protectedCells(), null, false),
                    WALK_SPEED, () -> canWorkOn(target), this);
            nav.setHighlights(this::activePositions);
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> {
                if (!canWorkOn(target)
                        && (forBreak ? MovementHelper.reachableAimPoint(player, target.pos()) == null : true)) {
                    // 站位目标满足了,活还是干不了(视线/臂展不达)——认输换下一格
                    skipCell(target);
                }
                dropNav();
                yield TaskState.RUNNING;
            }
            case FAILED -> {
                com.dwinovo.numen.Constants.LOG.info(
                        "[numen-task] build cell unreachable ({}): {} cell={}",
                        nav.failType(), nav.failReason(), target.pos().toShortString());
                skipCell(target);
                yield TaskState.RUNNING;
            }
        };
    }

    private void dropNav() {
        navTargetCell = null;
        stopNav();
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
        for (Direction facing : PLACE_GOAL_FACES) {
            BlockPos against = pos.relative(facing);
            // 只按世界几何选目标形状,不掺任何随身位变化的谓词(身体正压着目标格
            // 之类的事交执行期 canWorkOn):站位目标必须编译稳定,否则一次恰好
            // 站在格上的取样会把目标劣化成"站上未建格的正上方"这种不可能任务。
            if (MovementHelper.canPlaceAgainst(player.level(), against)) {
                return goalAdjacent(pos, against);
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

    private NavGoal goalAdjacent(BlockPos target, BlockPos no) {
        BlockPos t = target.immutable();
        BlockPos excluded = no.immutable();
        return new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                if (feet.equals(t) || feet.equals(excluded)) {
                    return false;
                }
                if (feet.getY() == t.getY() + 1
                        && Math.abs(feet.getX() - t.getX()) + Math.abs(feet.getZ() - t.getZ()) <= 1) {
                    // 站在旁边高一格、朝脚边的洞里放——玩家补地板的标准姿势
                    return true;
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
        layerTop = Math.min(maxY, layerTop + layerHeightEffective());
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
            layerTop = Math.min(maxY, layerTop + layerHeightEffective());
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

    /** 生效分层高:显式传参优先;未传(0)时,高于两格的结构自动按 1 格层
     *  自底向上推进——自由顺序建高结构,身体必须穿透已建成的部分去够低处的
     *  格子,和自己的作品抢同一空间(拆补拉锯的几何根源);逐层让身体始终站
     *  在施工面上方。矮结构(≤2 格高)无此问题,维持整体一次成型。 */
    private int layerHeightEffective() {
        if (r.layerHeight > 0) {
            return r.layerHeight;
        }
        return (maxY - minY + 1) > 2 ? 1 : 0;
    }

    private boolean usesLayers() {
        return layerHeightEffective() > 0;
    }

    private boolean placementPossible(BuildTaskRecord.Target target) {
        BlockState state = target.desiredState();
        return state.canSurvive(player.level(), target.pos())
                && canCreateDesiredState(target);
    }

    /** setBlock 直写期望状态,凡持有对应物品即视为可产出。 */
    private boolean canCreateDesiredState(BuildTaskRecord.Target target) {
        return hasItem(target.item(), true);
    }

    private int inventoryScanLimit(boolean wholeInventory) {
        return wholeInventory && NavSettings.get().allowInventory
                ? Math.min(36, player.getInventory().items.size())
                : Math.min(9, player.getInventory().items.size());
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
    /** 谁都不豁免——包括同伴自己:身体占着/正落进的格子不可放置。放置对自己
     *  身体让路,是"挖开脚下想下去、又立刻把方块补回自己脚底"这类自我拉扯的
     *  物理性防线,不需要任何显式停火状态。 */
    private boolean blockedByEntity(BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(player.level(), pos);
        return !shape.isEmpty() && !player.level().isUnobstructed(null,
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
        int limit = Math.min(36, inventory.items.size());
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
        int limit = Math.min(36, inventory.items.size());
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
        int limit = wholeInventory && NavSettings.get().allowInventory ? Math.min(36, inventory.items.size()) : 9;
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
                } else if (observedCompleted.remove(pos.asLong())) {
                    com.dwinovo.numen.Constants.LOG.debug(
                            "[numen-task] build cell LOST {} (now {})",
                            pos.toShortString(), view.getBlockState(pos).getBlock());
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

    private boolean currentCrosshairHits(BlockPos pos) {
        HitResult raw = player.pick(MovementHelper.blockReachDistance(player), 1.0f, false);
        return raw instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(pos);
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
                        p, view, loaded, safe, s, denied, activeTargetMap(), availableStates(true),
                        r.replaceExisting));
    }

    @Override
    public CalculationContext forExecution(NumenPlayer player, LongSet sacred, LongSet deniedPlace) {
        return ContextFactory.forExecution(player, sacred, deniedPlace,
                (p, view, loaded, safe, s, denied) -> new BuildCalculationContext(
                        p, view, loaded, safe, s, denied, activeTargetMap(), availableStates(true),
                        r.replaceExisting));
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