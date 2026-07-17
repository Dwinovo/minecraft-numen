package com.dwinovo.numen.core.pathing.execute;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.pathing.astar.NavPath;
import com.dwinovo.numen.core.pathing.astar.SplicedPath;
import com.dwinovo.numen.core.pathing.astar.CutoffPath;
import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;
import com.dwinovo.numen.core.pathing.moves.Input;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.moves.MovementState;
import com.dwinovo.numen.core.pathing.moves.MovementStatus;
import com.dwinovo.numen.core.pathing.moves.MutableMoveResult;
import com.dwinovo.numen.core.pathing.moves.movements.MovementAscend;
import com.dwinovo.numen.core.pathing.moves.movements.MovementDescend;
import com.dwinovo.numen.core.pathing.moves.movements.MovementDiagonal;
import com.dwinovo.numen.core.pathing.moves.movements.MovementFall;
import com.dwinovo.numen.core.pathing.moves.movements.MovementParkour;
import com.dwinovo.numen.core.pathing.moves.movements.MovementTraverse;
import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.phys.Vec3;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;

/**
 * 路径执行器:驱动一条已算好的路径逐移动执行完。每 tick 一次
 * {@link #onTick()},完整职责链:
 * 终点推进 → 位置重定位(回退扫/前跳扫)→ 脱轨双带看门狗 →
 * 边界暂停 → 成本核验(进入新移动时前瞻重算)→ 本移动逐 tick 涨价
 * 中止 → 回头暂停 → 移动状态机分派 → 疾跑整体决策 → 单移动超时。
 *
 * <p>疾跑不走按键:移动原语请求的 SPRINT 键被执行器没收,由执行器
 * 结合前后移动的上下文(直跳上台、下坡连锁、V 形谷、坠落前越)统一
 * 决策,直接写实体疾跑状态。
 */
public final class PathExecutor {

    /** 离路径超过该距离立即取消。 */
    static final double MAX_MAX_DIST_FROM_PATH = 3;
    /** 离路径超过该距离开始计时。 */
    static final double MAX_DIST_FROM_PATH = 2;
    /** 脱轨计时超过该 tick 数取消(10 秒)。 */
    static final double MAX_TICKS_AWAY = 200;

    private final NavPath path;
    private final NumenPlayer player;
    private final ExecHarness harness;
    /** 执行期重算成本用的上下文(活世界视图,主线程)。 */
    private final Supplier<CalculationContext> contextSupplier;
    /** 在飞搜索的当前最优部分路径;无在飞搜索或暂无候选时为空。 */
    private final Supplier<Optional<NavPath>> inProgressBestPath;
    private final ChunkLoadedTest loadedTest;

    private int pathPosition;
    private int ticksAway;
    private int ticksOnCurrent;
    private Double currentMovementOriginalCostEstimate;
    private Integer costEstimateIndex;
    private boolean failed;
    private boolean sprintNextTick;

    public PathExecutor(NavPath path, NumenPlayer player, ExecHarness harness,
                        Supplier<CalculationContext> contextSupplier,
                        Supplier<Optional<NavPath>> inProgressBestPath,
                        ChunkLoadedTest loadedTest) {
        this.path = path;
        this.player = player;
        this.harness = harness;
        this.contextSupplier = contextSupplier;
        this.inProgressBestPath = inProgressBestPath;
        this.loadedTest = loadedTest;
        this.pathPosition = 0;
    }

    /**
     * 脚位约定:实体坐标 y 加 0.1251(灵魂沙/农田顶面矮一截仍归上格),
     * 落在台阶格里再上抬一格。
     */
    public static BlockPos playerFeet(ServerPlayer player) {
        BlockPos feet = BlockPos.containing(
                player.position().x, player.position().y + 0.1251, player.position().z);
        if (player.level().getBlockState(feet).getBlock() instanceof SlabBlock) {
            return feet.above();
        }
        return feet;
    }

    /**
     * 推进一 tick。
     *
     * @return true 表示一个移动刚完成/路径进入暂停等"稳定"状态,
     *         false 表示位置刚被重定位(本 tick 已递归重跑);进行中
     *         返回该移动的 safeToCancel。
     */
    public boolean onTick() {
        if (pathPosition == path.length() - 1) {
            pathPosition++; // 最后一个格位没有对应移动,直接完成
        }
        if (pathPosition >= path.length()) {
            return true;
        }
        Movement movement = path.movements().get(pathPosition);
        movement.setExecutionDelegate(harness);
        BlockPos feet = playerFeet(player);
        if (!movement.getValidPositions().contains(feet)) {
            // 回退扫:被击退/卡顿回传后落在已走过的移动里,退回去重走
            int back = findBackwardMatch(path.movements(), pathPosition, feet);
            if (back != -1) {
                int previousPos = pathPosition;
                pathPosition = back;
                for (int j = pathPosition; j <= previousPos; j++) {
                    path.movements().get(j).reset();
                }
                onChangeInPathPosition();
                onTick();
                return false;
            }
            // 前跳扫:从 +3 起(+1 由移动自己报成功,+2 不查),身位落在
            // 后面的移动里则跳过去
            int forward = findForwardSkip(path.movements(), pathPosition, feet);
            if (forward != -1) {
                pathPosition = forward - 1;
                onChangeInPathPosition();
                onTick();
                return false;
            }
        }
        double distFromPath = closestPathPosDist();
        if (possiblyOffPath(distFromPath, MAX_DIST_FROM_PATH)) {
            ticksAway++;
            if (ticksAway > MAX_TICKS_AWAY) {
                Constants.LOG.debug("离路径太远太久,取消({} tick)", ticksAway);
                cancel();
                return false;
            }
        } else {
            ticksAway = 0;
        }
        if (possiblyOffPath(distFromPath, MAX_MAX_DIST_FROM_PATH)) {
            Constants.LOG.debug("离路径过远,立即取消");
            cancel();
            return false;
        }
        // TODO 若接可视化,这里对 pathPosition±10 的移动重算 toBreak/toPlace
        //      集合供渲染;服务端无渲染需求,暂略。
        if (pathPosition < path.movements().size() - 1) {
            Movement next = path.movements().get(pathPosition + 1);
            if (!loadedTest.isLoaded(next.getDest().getX(), next.getDest().getZ())) {
                Constants.LOG.debug("下一移动的终点在已加载区块边缘,暂停");
                harness.clearAllKeys();
                return true;
            }
        }
        boolean canCancel = movement.safeToCancel();
        CalculationContext context = contextSupplier.get();
        if (costEstimateIndex == null || costEstimateIndex != pathPosition) {
            // 进入新移动:钉住路径计算时的估价做对照基准,并向前核验
            costEstimateIndex = pathPosition;
            currentMovementOriginalCostEstimate = movement.getCost();
            prepareSupplies(movement);
            int lookahead = NavSettings.get().costVerificationLookahead;
            for (int i = 1; i < lookahead && pathPosition + i < path.length() - 1; i++) {
                Movement future = path.movements().get(pathPosition + i);
                if (future.calculateCost(context, new MutableMoveResult()) >= COST_INF && canCancel) {
                    Constants.LOG.debug("世界已变化,后续移动不可行,取消");
                    cancel();
                    return true;
                }
            }
        }
        double currentCost = movement.recalculateCost(context);
        if (currentCost >= COST_INF && canCancel) {
            Constants.LOG.debug("世界已变化,当前移动不可行,取消");
            cancel();
            return true;
        }
        if (!movement.calculatedWhileLoaded()
                && currentCost - currentMovementOriginalCostEstimate > NavSettings.get().maxCostIncrease
                && canCancel) {
            // 只对"当时在未加载区块里估出来的"移动生效:加载后货不对板;
            // 加载时算的涨价属于自己路径的连锁反应,不管
            Constants.LOG.debug("移动估价 {} 涨到 {},取消", currentMovementOriginalCostEstimate, currentCost);
            cancel();
            return true;
        }
        if (shouldPause()) {
            Constants.LOG.debug("在飞搜索的最优路径会回头经过脚下,暂停");
            harness.clearAllKeys();
            return true;
        }
        MovementStatus movementStatus = movement.update();
        if (movementStatus == MovementStatus.UNREACHABLE || movementStatus == MovementStatus.FAILED) {
            Constants.LOG.debug("移动报 {},取消", movementStatus);
            cancel();
            return true;
        }
        if (movementStatus == MovementStatus.SUCCESS) {
            pathPosition++;
            onChangeInPathPosition();
            onTick();
            return true;
        } else {
            sprintNextTick = shouldSprintNextTick();
            if (!sprintNextTick) {
                player.setSprinting(false); // 松开按键不会自动停疾跑
            }
            ticksOnCurrent++;
            if (ticksOnCurrent > currentMovementOriginalCostEstimate
                    + NavSettings.get().movementTimeoutTicks) {
                Constants.LOG.debug("移动耗时 {} tick,超出估价 {} 太多,取消",
                        ticksOnCurrent, currentMovementOriginalCostEstimate);
                cancel();
                return true;
            }
        }
        return canCancel;
    }

    // ==================== 重定位(纯逻辑,可测) ====================

    /** 回退扫:在 [0, pathPosition) 里找第一个合法位含 feet 的移动;无则 -1。 */
    static int findBackwardMatch(List<Movement> movements, int pathPosition, BlockPos feet) {
        for (int i = 0; i < pathPosition && i < movements.size(); i++) {
            if (movements.get(i).getValidPositions().contains(feet)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 前跳扫:在 [pathPosition+3, movements.size()) 里找第一个合法位含
     * feet 的移动;无则 -1。刻意跳过 +1(该移动自己会报 SUCCESS)与 +2。
     */
    static int findForwardSkip(List<Movement> movements, int pathPosition, BlockPos feet) {
        for (int i = pathPosition + 3; i < movements.size(); i++) {
            if (movements.get(i).getValidPositions().contains(feet)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 提前接段判定(纯逻辑):空中且不在液体里不接;严格下沉(竖速
     * < -0.1)不接(可能正穿水下坠);feet 恰在格位序列里 → 返回该
     * 下标,否则 -1。
     */
    static int snipsnapIndex(List<BlockPos> positions, BlockPos feet,
                             boolean onGround, boolean feetInLiquid, double deltaY) {
        if (!onGround && !feetInLiquid) {
            return -1;
        }
        if (deltaY < -0.1) {
            return -1;
        }
        return positions.indexOf(feet);
    }

    // ==================== 脱轨 ====================

    /** 玩家到全路径所有合法位格中心的最小距离。 */
    private double closestPathPosDist() {
        double best = -1;
        for (Movement movement : path.movements()) {
            for (BlockPos pos : movement.getValidPositions()) {
                double dist = entityDistanceToCenter(pos);
                if (dist < best || best == -1) {
                    best = dist;
                }
            }
        }
        return best;
    }

    /**
     * 是否可能脱轨。坠落中同时远离起点与终点属正常,当前移动是坠落时
     * 改用到坠落终点的水平距离判定。
     */
    private boolean possiblyOffPath(double distanceFromPath, double leniency) {
        if (distanceFromPath <= leniency) {
            return false;
        }
        if (path.movements().get(pathPosition) instanceof MovementFall) {
            BlockPos fallDest = path.positions().get(pathPosition + 1);
            return entityFlatDistanceToCenter(fallDest) >= leniency;
        }
        return true;
    }

    private double entityDistanceToCenter(BlockPos pos) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dy = pos.getY() + 0.5 - player.getY();
        double dz = pos.getZ() + 0.5 - player.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double entityFlatDistanceToCenter(BlockPos pos) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dz = pos.getZ() + 0.5 - player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ==================== 回头暂停 / 提前接段 ====================

    /**
     * 在飞搜索的最优部分路径(≥3 格,去掉首格后)包含脚下 → 新路径
     * 会经过这里,不必再往前走。仅在站稳、身位通透、当前移动可取消
     * 时生效。
     */
    private boolean shouldPause() {
        Optional<NavPath> currentBest = inProgressBestPath.get();
        if (currentBest.isEmpty()) {
            return false;
        }
        if (!player.onGround()) {
            return false;
        }
        BlockPos feet = playerFeet(player);
        var level = player.level();
        if (!MovementHelper.canWalkOn(level, feet.below())) {
            return false; // 站位本身可疑(可能跑酷中),别停
        }
        if (!MovementHelper.canWalkThrough(level, feet)
                || !MovementHelper.canWalkThrough(level, feet.above())) {
            return false; // 身位被埋,别停
        }
        if (!path.movements().get(pathPosition).safeToCancel()) {
            return false;
        }
        List<BlockPos> positions = currentBest.get().positions();
        if (positions.size() < 3) {
            return false; // 太短,远不能确定真会走这条
        }
        return positions.subList(1, positions.size()).contains(feet);
    }

    /** 无论当前推进到哪,身位恰在路径格位上时直接吸附过去。 */
    public boolean snipsnapifpossible() {
        BlockPos feet = playerFeet(player);
        boolean feetInLiquid = !player.level().getFluidState(feet).isEmpty();
        int index = snipsnapIndex(path.positions(), feet,
                player.onGround(), feetInLiquid, player.getDeltaMovement().y);
        if (index == -1) {
            return false;
        }
        pathPosition = index;
        harness.clearAllKeys();
        return true;
    }

    // ==================== 疾跑决策 ====================

    /**
     * 疾跑整体决策。先没收移动原语请求的 SPRINT 键(疾跑由执行器
     * 直接写实体状态,不靠按键),再按当前移动类型与前后文判定。
     */
    private boolean shouldSprintNextTick() {
        boolean requested = harness.isKeyRequested(Input.SPRINT);
        harness.forceKey(Input.SPRINT, false);

        // 与成本模型同判据:允许疾跑且饥饿值足够
        if (!(NavSettings.get().allowSprint && player.getFoodData().getFoodLevel() > 6)) {
            return false;
        }
        Movement current = path.movements().get(pathPosition);

        // 平走→上台直跳:跳过平走那步,原地起跳直接冲上去
        if (current instanceof MovementTraverse traverse && pathPosition < path.length() - 3) {
            Movement next = path.movements().get(pathPosition + 1);
            if (next instanceof MovementAscend ascend
                    && sprintableAscend(traverse, ascend, path.movements().get(pathPosition + 2))) {
                if (skipNow(current)) {
                    Constants.LOG.debug("跳过平走,直跳上台");
                    pathPosition++;
                    onChangeInPathPosition();
                    onTick();
                    harness.forceKey(Input.JUMP, true);
                    return true;
                }
            }
        }

        if (requested) {
            return true;
        }

        // 下降与上升不自行请求疾跑(它们不知道后面接什么),在此按前后文补
        if (current instanceof MovementDescend descend) {
            if (pathPosition < path.length() - 2) {
                Movement next = path.movements().get(pathPosition + 1);
                CalculationContext context = contextSupplier.get();
                if (MovementHelper.canUseFrostWalker(context, context.get(next.getDest().below()))) {
                    // 霜行者只在贴地跨过方块边缘时结冰,可能冲过头;下一步
                    // 同向平走/跑酷时强制慢速直进(跑酷且有耗材可放置替代除外)
                    if (next instanceof MovementTraverse || next instanceof MovementParkour) {
                        boolean couldPlaceInstead = NavSettings.get().allowPlace
                                && context.hasThrowaway && next instanceof MovementParkour;
                        boolean sameFlatDirection =
                                !current.getDirection().above().offset(next.getDirection()).equals(BlockPos.ZERO)
                                && current.getDirection().above().cross(next.getDirection()).equals(BlockPos.ZERO);
                        if (sameFlatDirection && !couldPlaceInstead) {
                            descend.forceSafeMode();
                        }
                    }
                }
            }
            if (descend.safeMode() && !descend.skipToAscend()) {
                return false; // 冲下去不安全
            }
            if (pathPosition < path.length() - 2) {
                Movement next = path.movements().get(pathPosition + 1);
                if (next instanceof MovementAscend
                        && current.getDirection().above().equals(next.getDirection().below())) {
                    // V 形:同向下降接上升,直接跳到上升步冲过去
                    pathPosition++;
                    onChangeInPathPosition();
                    onTick();
                    Constants.LOG.debug("V 形谷,跳过下降直接上升");
                    return true;
                }
                if (canSprintFromDescendInto(current, next)) {
                    if (next instanceof MovementDescend && pathPosition < path.length() - 3) {
                        Movement nextNext = path.movements().get(pathPosition + 2);
                        if (nextNext instanceof MovementDescend
                                && !canSprintFromDescendInto(next, nextNext)) {
                            return false; // 连锁下降的下一环接不上,别开冲
                        }
                    }
                    if (playerFeet(player).equals(current.getDest())) {
                        pathPosition++;
                        onChangeInPathPosition();
                        onTick();
                    }
                    return true;
                }
            }
        }
        if (current instanceof MovementAscend && pathPosition != 0) {
            Movement prev = path.movements().get(pathPosition - 1);
            if (prev instanceof MovementDescend
                    && prev.getDirection().above().equals(current.getDirection().below())) {
                // V 形谷底:动量还在,高度够了就松跳直接冲上去
                BlockPos center = current.getSrc().above();
                // 0.07 的余量吸收农田/灵魂沙顶面的矮一截
                if (player.position().y >= center.getY() - 0.07) {
                    harness.forceKey(Input.JUMP, false);
                    return true;
                }
            }
            if (pathPosition < path.length() - 2 && prev instanceof MovementTraverse traverse
                    && sprintableAscend(traverse, (MovementAscend) current,
                            path.movements().get(pathPosition + 1))) {
                return true;
            }
        }
        if (current instanceof MovementFall fall) {
            Vec3 overrideTarget = overrideFallTarget(fall);
            if (overrideTarget != null) {
                BlockPos fallDest = overrideFallDest(fall);
                if (!path.positions().contains(fallDest)) {
                    throw new IllegalStateException("坠落前越落点 " + fallDest + " 不在路径上");
                }
                if (playerFeet(player).equals(fallDest)) {
                    pathPosition = path.positions().indexOf(fallDest);
                    onChangeInPathPosition();
                    onTick();
                    return true;
                }
                // 疾跑冲下坡不减速:清键、直接压目标视线与前进
                harness.clearAllKeys();
                Vec3 eye = player.getEyePosition();
                harness.applyRotation(new MovementState.MovementTarget(
                        MovementHelper.yawTo(eye, overrideTarget),
                        MovementHelper.pitchTo(eye, overrideTarget), false));
                harness.forceKey(Input.MOVE_FORWARD, true);
                return true;
            }
        }
        return false;
    }

    /**
     * 坠落前越:落差 ≤3 且无待挖块时,向后收集 ≤2 个同向平走,整柱
     * 通透且各落脚可站 → 把目标点押到扩展终点略前(len−0.4),冲刺
     * 直接飞过去。返回扩展的瞄准点;不可前越返回 null。
     */
    private Vec3 overrideFallTarget(MovementFall movement) {
        int extension = overrideFallExtension(movement);
        if (extension == 0) {
            return null;
        }
        Vec3i dir = movement.getDirection();
        Vec3i flatDir = new Vec3i(dir.getX(), 0, dir.getZ());
        double len = extension - 0.4;
        BlockPos dest = movement.getDest();
        return new Vec3(flatDir.getX() * len + dest.getX() + 0.5,
                dest.getY(),
                flatDir.getZ() * len + dest.getZ() + 0.5);
    }

    /** 坠落前越的扩展落点(路径上真实存在的格位)。 */
    private BlockPos overrideFallDest(MovementFall movement) {
        int extension = overrideFallExtension(movement);
        Vec3i dir = movement.getDirection();
        return movement.getDest().offset(dir.getX() * extension, 0, dir.getZ() * extension);
    }

    /** 前越可扩展的同向平走步数(0 = 不可前越)。 */
    private int overrideFallExtension(MovementFall movement) {
        Vec3i dir = movement.getDirection();
        if (dir.getY() < -3) {
            return 0;
        }
        if (needsToBreak(movement)) {
            return 0;
        }
        var level = player.level();
        Vec3i flatDir = new Vec3i(dir.getX(), 0, dir.getZ());
        int i;
        outer:
        for (i = pathPosition + 1; i < path.length() - 1 && i < pathPosition + 3; i++) {
            Movement next = path.movements().get(i);
            if (!(next instanceof MovementTraverse)) {
                break;
            }
            if (!flatDir.equals(next.getDirection())) {
                break;
            }
            for (int y = next.getDest().getY(); y <= movement.getSrc().getY() + 1; y++) {
                BlockPos chk = new BlockPos(next.getDest().getX(), y, next.getDest().getZ());
                if (!MovementHelper.fullyPassable(level, chk)) {
                    break outer;
                }
            }
            if (!MovementHelper.canWalkOn(level, next.getDest().below())) {
                break;
            }
        }
        i--;
        return i - pathPosition;
    }

    /** 平走→上台直跳的起跳时机:已对中,且身后头顶通透或已走出足够远。 */
    private boolean skipNow(Movement current) {
        double offTarget = Math.abs(current.getDirection().getX()
                        * (current.getSrc().getZ() + 0.5 - player.position().z))
                + Math.abs(current.getDirection().getZ()
                        * (current.getSrc().getX() + 0.5 - player.position().x));
        if (offTarget > 0.1) {
            return false;
        }
        BlockPos headBonk = current.getSrc().subtract(current.getDirection()).above(2);
        if (MovementHelper.fullyPassable(player.level(), headBonk)) {
            return true;
        }
        // 身后头顶不通:再走出 0.8 才敢跳(免得起跳磕头)
        double flatDist = Math.abs(current.getDirection().getX()
                        * (headBonk.getX() + 0.5 - player.position().x))
                + Math.abs(current.getDirection().getZ()
                        * (headBonk.getZ() + 0.5 - player.position().z));
        return flatDist > 0.8;
    }

    /**
     * 平走接上升可否直跳疾跑:同向共线、两个落脚都可站、上升无待挖块、
     * 起跳柱与前柱三格全通透、头顶两处无危险格。
     */
    private boolean sprintableAscend(MovementTraverse current, MovementAscend next, Movement nextNext) {
        if (!NavSettings.get().sprintAscends) {
            return false;
        }
        if (!current.getDirection().equals(next.getDirection().below())) {
            return false;
        }
        if (nextNext.getDirection().getX() != next.getDirection().getX()
                || nextNext.getDirection().getZ() != next.getDirection().getZ()) {
            return false;
        }
        var level = player.level();
        if (!MovementHelper.canWalkOn(level, current.getDest().below())) {
            return false;
        }
        if (!MovementHelper.canWalkOn(level, next.getDest().below())) {
            return false;
        }
        if (needsToBreak(next)) {
            return false;
        }
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 3; y++) {
                BlockPos chk = current.getSrc().above(y);
                if (x == 1) {
                    chk = chk.offset(current.getDirection());
                }
                if (!MovementHelper.fullyPassable(level, chk)) {
                    return false;
                }
            }
        }
        if (MovementHelper.avoidWalkingInto(level.getBlockState(current.getSrc().above(3)))) {
            return false;
        }
        return !MovementHelper.avoidWalkingInto(level.getBlockState(next.getDest().above(2)));
    }

    /** 下降可否疾跑冲进下一步:同向下降恒可;落点前方可站时同向平走/对角亦可。 */
    private boolean canSprintFromDescendInto(Movement current, Movement next) {
        if (next instanceof MovementDescend && next.getDirection().equals(current.getDirection())) {
            return true;
        }
        if (!MovementHelper.canWalkOn(player.level(),
                current.getDest().offset(current.getDirection()))) {
            return false;
        }
        if (next instanceof MovementTraverse && next.getDirection().equals(current.getDirection())) {
            return true;
        }
        return next instanceof MovementDiagonal && NavSettings.get().allowOvershootDiagonalDescend;
    }

    /** 该移动是否还有实际要挖的格。 */
    private boolean needsToBreak(Movement movement) {
        for (BlockPos pos : movement.toBreakAll()) {
            if (!MovementHelper.canWalkThrough(player.level(), pos)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 备货 / 状态迁移 ====================

    /**
     * 进入新移动时的执行期备货:要放置的移动把背包深处的耗材搬进快捷栏
     * (规划期按全背包判有料),超过安全落差且落点无水的坠落备水桶。
     */
    private void prepareSupplies(Movement movement) {
        if (movement.getToPlace() != null) {
            harness.ensureThrowawayInHotbar();
        }
        if (movement instanceof MovementFall
                && movement.getSrc().getY() - movement.getDest().getY() > NavSettings.get().maxFallHeightNoWater
                && !MovementHelper.isWater(player.level().getBlockState(movement.getDest()))) {
            harness.ensureWaterBucketInHotbar();
        }
    }

    private void onChangeInPathPosition() {
        harness.clearAllKeys();
        ticksOnCurrent = 0;
    }

    /** 取消:清键、停挖、把推进下标押出末尾并标失败。 */
    private void cancel() {
        harness.clearAllKeys();
        harness.stopBreaking();
        pathPosition = path.length() + 3;
        failed = true;
    }

    // ==================== 拼接 / 历史裁剪 ====================

    /**
     * 把下一段拼进当前路径(保持推进状态);无法拼接或无下一段时,
     * 已执行历史超限则裁掉开头一截。
     */
    public PathExecutor trySplice(PathExecutor next) {
        if (next == null) {
            return cutIfTooLong();
        }
        return SplicedPath.trySplice(path, next.path, false).map(spliced -> {
            if (!spliced.getDest().equals(next.getPath().getDest())) {
                throw new IllegalStateException(
                        "拼接后终点 " + spliced.getDest() + " 与下一段终点 " + next.getPath().getDest() + " 不符");
            }
            PathExecutor ret = newWithSamePlumbing(spliced);
            ret.pathPosition = pathPosition;
            ret.currentMovementOriginalCostEstimate = currentMovementOriginalCostEstimate;
            ret.costEstimateIndex = costEstimateIndex;
            ret.ticksOnCurrent = ticksOnCurrent;
            return ret;
        }).orElseGet(this::cutIfTooLong);
    }

    private PathExecutor cutIfTooLong() {
        if (pathPosition > NavSettings.get().maxPathHistoryLength) {
            int cutoffAmt = NavSettings.get().pathHistoryCutoffAmount;
            CutoffPath newPath = new CutoffPath(path, cutoffAmt, path.length() - 1);
            if (!newPath.getDest().equals(path.getDest())) {
                throw new IllegalStateException("裁剪历史后终点变了:" + newPath.getDest() + " != " + path.getDest());
            }
            Constants.LOG.debug("已执行历史过长,路径从 {} 裁到 {}", path.length(), newPath.length());
            PathExecutor ret = newWithSamePlumbing(newPath);
            ret.pathPosition = pathPosition - cutoffAmt;
            ret.currentMovementOriginalCostEstimate = currentMovementOriginalCostEstimate;
            if (costEstimateIndex != null) {
                ret.costEstimateIndex = costEstimateIndex - cutoffAmt;
            }
            ret.ticksOnCurrent = ticksOnCurrent;
            return ret;
        }
        return this;
    }

    private PathExecutor newWithSamePlumbing(NavPath newPath) {
        return new PathExecutor(newPath, player, harness, contextSupplier, inProgressBestPath, loadedTest);
    }

    // ==================== 只读面 ====================

    public int getPosition() {
        return pathPosition;
    }

    public NavPath getPath() {
        return path;
    }

    public Goal getGoal() {
        return path.getGoal();
    }

    public boolean failed() {
        return failed;
    }

    public boolean finished() {
        return pathPosition >= path.length();
    }

    /** 本 tick 执行器的疾跑决策结果(由拥有者落到实体上)。 */
    public boolean isSprinting() {
        return sprintNextTick;
    }
}
