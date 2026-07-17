package com.dwinovo.numen.core.pathing.execute;

import java.util.Optional;

import com.dwinovo.numen.core.pathing.astar.Favoring;
import com.dwinovo.numen.core.pathing.astar.NavPath;
import com.dwinovo.numen.core.pathing.astar.PathCalcResult;
import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;

import net.minecraft.core.BlockPos;

/**
 * 路径搜索派发接口:段规划状态机把一次搜索交出去,拿回一个可轮询的
 * 句柄。实现决定搜索跑在哪里(线程池 / 同步直跑)。
 *
 * <p>线程契约:{@code submit} 与句柄的全部方法都只在 tick 线程调用;
 * 异步实现须保证 {@link SearchHandle#poll} 完成后结果对 tick 线程可见,
 * 且传入的 {@code context} 是可在 worker 线程安全读取的冻结快照
 * ({@code context.safeForThreadedUse})。
 */
public interface SearchDispatcher {

    /**
     * 派发一次搜索。
     *
     * @param realStart 玩家真实脚位(路径退化为单节点时的假起点素材)
     * @param start A* 展开起点(pathStart 规则的产物)
     * @param goal 目标
     * @param context 本次搜索的成本上下文
     * @param favoring 上一段路径的偏好折扣表
     * @param primaryTimeoutMs 已有可用部分路径后的预算(毫秒)
     * @param failureTimeoutMs 毫无可用结果时烧满的预算(毫秒)
     */
    SearchHandle submit(BlockPos realStart, BlockPos start, Goal goal,
                        CalculationContext context, Favoring favoring,
                        long primaryTimeoutMs, long failureTimeoutMs);

    /** 一次在飞搜索的句柄。 */
    interface SearchHandle {

        /** A* 展开起点(在飞搜索的合法性检查用)。 */
        BlockPos searchStart();

        /** 计算进行中的当前最优部分路径(回头暂停判定用);暂无候选时为空。 */
        Optional<NavPath> bestPathSoFar();

        /** 结果就绪时返回;仍在计算返回空。 */
        Optional<PathCalcResult> poll();

        /** 协作取消;结果最终以 CANCELLATION 从 {@link #poll} 流出。 */
        void cancel();
    }
}
