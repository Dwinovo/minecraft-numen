package com.dwinovo.numen.core.task.move;
import com.dwinovo.numen.core.task.mine.MineCompanionTask;

import com.dwinovo.numen.core.pathing.bridge.ContextFactory;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.scan.BlockScanner;
import com.dwinovo.numen.core.scan.BlockSearch;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * goto 的 FIND(就近方块)子系统:起一次 {@link BlockSearch} 找出候选、按 mine 同一道
 * 剪枝入册、编译 anyOf 导航契约、打不通时逐个除名轮换。它与 goto 的坐标三态
 * (BLOCK/COLUMN/YLEVEL)不共享任何逻辑,任务只管驱动。
 *
 * <p>搜索和 {@code scan_blocks} 走同一条路,只是 {@code want} 不同——所以
 * "最近的铁矿在哪"两个工具给的是同一个答案。
 */
final class NearestBlockFinder {

    /** 候选上限、最远环半径、行程预算基准。 */
    private static final int MAX_CANDIDATES = 64;
    private static final int MAX_CHUNK_RADIUS = 32;
    /**
     * 收工判据的配额。要 1 个:她只需要最近的那一个,余下的候选是寻路打不通时的备胎,
     * 顺路捡到多少算多少。要 64 个会逼着搜索一路走到能证明"这 64 个都是最近的"为止
     * ——找一把独一份的工作台时,那就是把整个加载区走一遍。
     */
    private static final int NEAREST_WANTED = 1;
    static final int BUDGET_BLOCKS = 128;

    private final NumenPlayer player;
    private final Block target;
    /** 仍在册的候选格(打不通的会被逐个除名)。 */
    private final List<BlockPos> candidates = new ArrayList<>();
    /** 在飞搜索的句柄;0 表示没有在飞的。 */
    private int scanId;
    /** 搜索回来的命中,等 {@link #drain()} 收割。 */
    private List<BlockScanner.Hit> hits;
    private boolean scanDrained;
    /** 候选集编译出的导航契约(候选变动时重建)。 */
    private GoalCompiler.Compiled contract;

    NearestBlockFinder(NumenPlayer player, Block target) {
        this.player = player;
        this.target = target;
    }

    /** 踢一次搜索:按环序由近及远走已加载地形,最近的那个一被证明就收工。 */
    void kickScan() {
        if (!(player.level() instanceof ServerLevel level)) {
            scanDrained = true;
            return;
        }
        // 圆心用寻路口径的脚位格(0.1251 上抬 + 台阶取上格)——section 遍历序从它导出。
        BlockPos feet = BlockHelper.playerFeet(level, player.getX(), player.getY(), player.getZ());
        scanId = BlockSearch.start(player.getUUID(), level, feet, MAX_CHUNK_RADIUS * 16,
                NEAREST_WANTED, Set.of(target), res -> {
                    scanId = 0;
                    hits = res.matches();
                });
    }

    /** 任务收尾:丢掉还在飞的搜索,免得回执落到一个已经没人读的组件上。 */
    void cancelScan() {
        if (scanId != 0) {
            BlockSearch.cancel(scanId);
            scanId = 0;
            scanDrained = true;
        }
    }

    /** 收割搜索结果:按距离取最近的前 {@link #MAX_CANDIDATES} 个入册。 */
    void drain() {
        if (hits == null) {
            return;   // 还在飞,或者已经收割过
        }
        List<BlockScanner.Hit> found = hits;
        hits = null;
        scanDrained = true;
        // 入册前过与 mine 同一道目标剪枝:挖不动/禁挖(贴液体等)/基岩上下
        // 夹死的格不作候选——省得选中一个走近了也没法处置的目标。
        var ctx = ContextFactory.forExecution(player);
        found.stream()
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

    /** 搜索已收割,且没有候选可给了。 */
    boolean exhausted() {
        return scanId == 0 && scanDrained;
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
