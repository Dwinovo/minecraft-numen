package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.pathing.bridge.ContextFactory;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.scan.BlockScanner;
import com.dwinovo.numen.core.scan.ScanExecutor;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * goto 的 FIND(就近方块)子系统:离线环形扫描出候选、按 mine 同一道
 * 剪枝入册、编译 anyOf 导航契约、打不通时逐个除名轮换。它与 goto 的
 * 坐标三态(BLOCK/COLUMN/YLEVEL)不共享任何逻辑——此前十二个字段和
 * 五处 switch 分支散在任务里,现在收进一个组件,任务只管驱动。
 */
final class NearestBlockFinder {

    /** 候选上限、扫描同层判据/最远环半径、离线扫描的放弃时限、行程预算基准。 */
    private static final int MAX_CANDIDATES = 64;
    private static final int Y_THRESHOLD = 10;
    private static final int MAX_CHUNK_RADIUS = 32;
    private static final long SCAN_TIMEOUT_TICKS = 40;
    static final int BUDGET_BLOCKS = 128;

    private final NumenPlayer player;
    private final Block target;
    /** 仍在册的候选格(打不通的会被逐个除名)。 */
    private final List<BlockPos> candidates = new ArrayList<>();
    /** 在飞的离线扫描;完成/超时后归 null。 */
    private CompletableFuture<List<BlockScanner.Hit>> scan;
    private long scanDeadline;
    private boolean scanDrained;
    /** 候选集编译出的导航契约(候选变动时重建)。 */
    private GoalCompiler.Compiled contract;

    NearestBlockFinder(NumenPlayer player, Block target) {
        this.player = player;
        this.target = target;
    }

    /** 踢一次离线扫描:主线程环形捕获身体周围的已加载 chunk 引用(捕获止于
     *  加载区边缘),后台按环序由近及远扫,凑够即提前收工。 */
    void kickScan() {
        var level = player.level();
        // 圆心用寻路口径的脚位格(0.1251 上抬 + 台阶取上格)——同层判据与
        // section 遍历序都从它导出。
        var cap = BlockScanner.captureRings(level,
                BlockHelper.playerFeet(level, player.getX(), player.getY(), player.getZ()));
        scan = ScanExecutor.submit(() -> BlockScanner.scanRings(
                level, cap, Set.of(target), MAX_CANDIDATES, Y_THRESHOLD, MAX_CHUNK_RADIUS));
        scanDeadline = level.getGameTime() + SCAN_TIMEOUT_TICKS;
    }

    /** 收割离线扫描:按距离取最近的前 {@link #MAX_CANDIDATES} 个入册。 */
    void drain() {
        if (scan == null) {
            return;
        }
        if (!scan.isDone()) {
            if (player.level().getGameTime() > scanDeadline) {
                scan.cancel(false);
                scan = null;
                scanDrained = true;
            }
            return;
        }
        List<BlockScanner.Hit> hits;
        try {
            hits = scan.join();
        } catch (Exception e) {
            hits = List.of();
        }
        scan = null;
        scanDrained = true;
        // 入册前过与 mine 同一道目标剪枝:挖不动/禁挖(贴液体等)/基岩上下
        // 夹死的格不作候选——省得选中一个走近了也没法处置的目标。
        var ctx = ContextFactory.forExecution(player);
        hits.stream()
                .sorted(Comparator.comparingDouble(BlockScanner.Hit::distance))
                .map(h -> h.pos().immutable())
                .filter(p -> MineCompanionTask.plausibleToBreak(
                        ctx, p, ctx.get(p.getX(), p.getY(), p.getZ())))
                .limit(MAX_CANDIDATES)
                .forEach(candidates::add);
        rebuildContract();
    }

    boolean hasCandidates() {
        return !candidates.isEmpty();
    }

    /** 扫描已收割/超时,且没有候选可给了。 */
    boolean exhausted() {
        return scan == null && scanDrained;
    }

    /** 当前候选集的导航契约;无候选为 null。 */
    GoalCompiler.Compiled contract() {
        return contract;
    }

    private void rebuildContract() {
        contract = candidates.isEmpty() ? null : GoalCompiler.anyOf(candidates);
    }

    /** 打不通时的轮换:还有得换就把最近候选除名并重建契约,只剩一个则不动。 */
    boolean rotateAfterFailure() {
        if (candidates.size() <= 1) {
            return false;
        }
        int nearest = nearestIndex();
        if (nearest >= 0) {
            candidates.remove(nearest);
        }
        rebuildContract();
        return true;
    }

    /** 离身体最近的在册候选;无候选返回 null。 */
    BlockPos nearest() {
        int i = nearestIndex();
        return i < 0 ? null : candidates.get(i);
    }

    private int nearestIndex() {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            BlockPos c = candidates.get(i);
            double d = player.distanceToSqr(c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }
}
