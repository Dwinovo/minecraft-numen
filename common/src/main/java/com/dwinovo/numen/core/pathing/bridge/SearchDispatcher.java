package com.dwinovo.numen.core.pathing.bridge;

import com.dwinovo.numen.core.pathing.astar.Favoring;
import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;

import net.minecraft.core.BlockPos;

/**
 * 异步搜索调度接口:tick 线程提交一次搜索,拿回 {@link SearchHandle}
 * 逐 tick 轮询。实现负责线程编排(见 {@link PoolSearchDispatcher});
 * 调用方负责在 tick 线程消化完成结果与取消。
 */
public interface SearchDispatcher {

    /**
     * 提交一次搜索。必须在 tick 线程调用(搜索器构造时取样世界边界,
     * 且冻结上下文的构建约定在主线程)。
     *
     * @param realStart 玩家真实脚位(与 A* 起点不同且路径退化为单节点
     *                  时,路径装配补假起点用)
     * @param start     A* 展开起点
     * @param goal      内核目标(任务层词表经 {@link GoalAdapter} 映射)
     * @param context   冻结上下文({@link ContextFactory#forSearch});
     *                  传入未冻结上下文时实现须同步跑在调用线程
     * @param favoring  上一路径的成本折扣表(无则 {@link Favoring#empty()})
     * @param primaryMs 已有可用部分路径后的预算(毫秒)
     * @param failureMs 毫无可用结果时烧满的预算(毫秒)
     */
    SearchHandle submit(BlockPos realStart, BlockPos start, Goal goal,
                        CalculationContext context, Favoring favoring,
                        long primaryMs, long failureMs);

    /** 起点即真实脚位的常用形态。 */
    default SearchHandle submit(BlockPos start, Goal goal, CalculationContext context,
                                Favoring favoring, long primaryMs, long failureMs) {
        return submit(start, start, goal, context, favoring, primaryMs, failureMs);
    }
}
