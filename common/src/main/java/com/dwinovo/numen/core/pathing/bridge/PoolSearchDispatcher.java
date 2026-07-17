package com.dwinovo.numen.core.pathing.bridge;

import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import com.dwinovo.numen.core.pathing.astar.AStarPathFinder;
import com.dwinovo.numen.core.pathing.astar.Favoring;
import com.dwinovo.numen.core.pathing.astar.NavPath;
import com.dwinovo.numen.core.pathing.astar.PathCalcResult;
import com.dwinovo.numen.core.pathing.calc.PathPlannerPool;
import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;

import net.minecraft.core.BlockPos;

/**
 * {@link SearchDispatcher} 的共享池实现:搜索器在提交线程(tick)构造
 * (世界边界四 double 在构造时取样),{@code calculate} 整场交给
 * {@link PathPlannerPool} 的 worker;句柄轮询 future,取消同时打
 * 搜索器的协作取消位与 future。
 *
 * <p>上下文未冻结({@code safeForThreadedUse=false},无快照回退场景)
 * 时不进池:同步跑在提交线程,句柄提交即完成。
 */
public final class PoolSearchDispatcher implements SearchDispatcher {

    /** 无状态,共享单例即可。 */
    public static final PoolSearchDispatcher INSTANCE = new PoolSearchDispatcher();

    public PoolSearchDispatcher() {}

    @Override
    public SearchHandle submit(BlockPos realStart, BlockPos start, Goal goal,
                               CalculationContext context, Favoring favoring,
                               long primaryMs, long failureMs) {
        AStarPathFinder finder = new AStarPathFinder(
                realStart, start.getX(), start.getY(), start.getZ(), goal, favoring, context);
        if (!context.safeForThreadedUse) {
            // 活世界上下文只能在本(主)线程读:原地算完,句柄即完成态
            return new PoolHandle(finder,
                    CompletableFuture.completedFuture(finder.calculate(primaryMs, failureMs)));
        }
        CompletableFuture<PathCalcResult> future =
                PathPlannerPool.submit(() -> finder.calculate(primaryMs, failureMs));
        return new PoolHandle(finder, future);
    }

    /** future + 搜索器的组合句柄。 */
    private static final class PoolHandle implements SearchHandle {

        private final AStarPathFinder finder;
        private final CompletableFuture<PathCalcResult> future;
        /** poll 首次消化后钉住的结论(幂等返回)。 */
        private PathCalcResult settled;

        PoolHandle(AStarPathFinder finder, CompletableFuture<PathCalcResult> future) {
            this.finder = finder;
            this.future = future;
        }

        @Override
        public PathCalcResult poll() {
            if (settled != null) {
                return settled;
            }
            if (!future.isDone()) {
                return null;
            }
            try {
                settled = future.join();
            } catch (CancellationException cancelled) {
                settled = new PathCalcResult(PathCalcResult.Type.CANCELLATION);
            } catch (RuntimeException e) {
                // calculate 内部已兜异常;这里只兜 future 自身的执行异常
                settled = new PathCalcResult(PathCalcResult.Type.EXCEPTION);
            }
            return settled;
        }

        @Override
        public boolean isDone() {
            return settled != null || future.isDone();
        }

        @Override
        public void cancel() {
            finder.cancel();
            future.cancel(false);
        }

        @Override
        public Optional<NavPath> bestPathSoFar() {
            return finder.bestPathSoFar();
        }

        @Override
        public Optional<NavPath> mostRecentConsidered() {
            return finder.pathToMostRecentNodeConsidered();
        }
    }
}
