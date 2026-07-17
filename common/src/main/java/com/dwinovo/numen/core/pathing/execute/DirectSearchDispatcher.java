package com.dwinovo.numen.core.pathing.execute;

import java.util.Optional;

import com.dwinovo.numen.core.pathing.astar.AStarPathFinder;
import com.dwinovo.numen.core.pathing.astar.Favoring;
import com.dwinovo.numen.core.pathing.astar.NavPath;
import com.dwinovo.numen.core.pathing.astar.PathCalcResult;
import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;

import net.minecraft.core.BlockPos;

/**
 * 同步直跑的搜索派发实现:submit 在调用线程当场算完,句柄一轮询即得
 * 结果。供单测与线程池接线前的临时使用;submit 会阻塞到预算耗尽为止,
 * 不适合生产 tick 线程。
 */
public final class DirectSearchDispatcher implements SearchDispatcher {

    @Override
    public SearchHandle submit(BlockPos realStart, BlockPos start, Goal goal,
                               CalculationContext context, Favoring favoring,
                               long primaryTimeoutMs, long failureTimeoutMs) {
        AStarPathFinder finder = new AStarPathFinder(
                realStart, start.getX(), start.getY(), start.getZ(), goal, favoring, context);
        PathCalcResult result = finder.calculate(primaryTimeoutMs, failureTimeoutMs);
        return new SearchHandle() {

            @Override
            public BlockPos searchStart() {
                return finder.getStart();
            }

            @Override
            public Optional<NavPath> bestPathSoFar() {
                return finder.bestPathSoFar();
            }

            @Override
            public Optional<PathCalcResult> poll() {
                return Optional.of(result);
            }

            @Override
            public void cancel() {
                finder.cancel();
            }
        };
    }
}
