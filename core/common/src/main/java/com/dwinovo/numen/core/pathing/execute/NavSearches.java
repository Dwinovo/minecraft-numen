package com.dwinovo.numen.core.pathing.execute;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.dwinovo.numen.core.pathing.astar.Favoring;
import com.dwinovo.numen.core.pathing.astar.NavPath;
import com.dwinovo.numen.core.pathing.astar.PathCalcResult;
import com.dwinovo.numen.core.pathing.bridge.SearchDispatcher;
import com.dwinovo.numen.core.pathing.bridge.SearchHandle;
import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;

import net.minecraft.core.BlockPos;

/**
 * 一次导航的派发口:这次导航派出的每一个搜索——状态机的段搜索、无路时的候选路线查询、改动预算下的
 * 整路规划——都从这里派出,这里记着哪些还没有被取走结论。"身体在等规划"只从这里读
 * ({@link PlayerNav#planningInFlight}):以后添一种查询,只要走这扇门就自动算进去,不必再去改那个判断。
 *
 * <p>一次搜索从派出起算在等,到派它的一方取走结论({@link SearchHandle#poll} 第一次返回非空)或取消为止。
 * 算完了还没取走的那一刻也算:任务是在取结论的那一刻才不再等它的,这样被冻结的刻数只看搜索跨了几刻,
 * 不看后台线程恰好在哪一刻算完。
 *
 * <p>只在 tick 线程上用(派发、轮询、取消都在那里)。
 */
final class NavSearches implements SearchDispatcher {

    private final SearchDispatcher inner;
    /** 派出了、结论还没被取走也没取消的搜索。 */
    private final List<Tracked> waiting = new ArrayList<>();

    NavSearches(SearchDispatcher inner) {
        this.inner = inner;
    }

    @Override
    public SearchHandle submit(BlockPos realStart, BlockPos start, Goal goal, CalculationContext context,
                               Favoring favoring, int primaryNodes, int failureNodes) {
        Tracked handle = new Tracked(inner.submit(realStart, start, goal, context, favoring,
                primaryNodes, failureNodes));
        waiting.add(handle);
        return handle;
    }

    /** 有派出的搜索还没被取走结论。 */
    boolean waiting() {
        return !waiting.isEmpty();
    }

    /** 取消这次导航派出的、还在等的全部搜索。 */
    void cancelAll() {
        for (Tracked handle : List.copyOf(waiting)) {
            handle.cancel();
        }
    }

    private final class Tracked implements SearchHandle {

        private final SearchHandle inner;

        Tracked(SearchHandle inner) {
            this.inner = inner;
        }

        @Override
        public PathCalcResult poll() {
            PathCalcResult result = inner.poll();
            if (result != null) {
                waiting.remove(this);
            }
            return result;
        }

        @Override
        public boolean isDone() {
            return inner.isDone();
        }

        @Override
        public void cancel() {
            inner.cancel();
            waiting.remove(this);
        }

        @Override
        public Optional<NavPath> bestPathSoFar() {
            return inner.bestPathSoFar();
        }

        @Override
        public Optional<NavPath> mostRecentConsidered() {
            return inner.mostRecentConsidered();
        }
    }
}
