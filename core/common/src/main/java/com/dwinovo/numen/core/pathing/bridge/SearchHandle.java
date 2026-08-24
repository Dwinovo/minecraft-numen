package com.dwinovo.numen.core.pathing.bridge;

import java.util.Optional;

import com.dwinovo.numen.core.pathing.astar.NavPath;
import com.dwinovo.numen.core.pathing.astar.PathCalcResult;

/**
 * 一次在飞搜索的轮询句柄。tick 线程每 tick 调 {@link #poll} 消化完成
 * 回调;计算进行中可经 {@link #bestPathSoFar}/{@link #mostRecentConsidered}
 * 窥视近似进度(worker 并发写节点,读到的是近似快照)。
 */
public interface SearchHandle {

    /**
     * 未完成返回 null;完成后返回最终结论,且此后每次调用返回同一个
     * 结果对象(幂等,tick 轮询无需自记已消化)。
     */
    PathCalcResult poll();

    /** 计算是否已出结论(与 {@code poll() != null} 同义)。 */
    boolean isDone();

    /** 协作取消:置搜索器取消位并取消 future;结论收敛为 CANCELLATION。 */
    void cancel();

    /** 计算进行中的当前最优部分路径(执行层回头暂停判定用)。 */
    Optional<NavPath> bestPathSoFar();

    /** 到最近弹出考虑节点的路径(渲染/诊断用)。 */
    Optional<NavPath> mostRecentConsidered();
}
