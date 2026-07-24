/**
 * 同伴身体的服务端导航:规划、移动生成与真实执行,面向可改造世界里的
 * fake {@code ServerPlayer}。
 *
 * <h2>包布局</h2>
 * <ul>
 *   <li>{@code goals/} —— 内核目标族:单格/双格/邻域/列/层/复合/外逃等
 *       到达语义与各自的启发式。</li>
 *   <li>{@code moves/} —— 成本模型与移动原语:动作成本表、成本上下文
 *       ({@code CalculationContext},吸收 sacred/deniedPlace
 *       三个语义开关)、谓词库({@code MovementHelper})、工具评估
 *       ({@code ToolSet})与八类移动原语的计价+逐 tick 状态机。</li>
 *   <li>{@code astar/} —— 搜索器:二叉堆开集、系数分档的部分路径候选、
 *       favoring 折扣、路径装配(截尾/拼接/裁剪)。</li>
 *   <li>{@code execute/} —— 执行层:段规划状态机({@code PathingCore})、
 *       单段逐移动驱动({@code PathExecutor})、输入/视角落地
 *       ({@code ExecHarness}/{@code AimProcessor})。</li>
 *   <li>{@code bridge/} —— 服务端接线:快照上下文工厂、共享池异步搜索
 *       调度、任务层目标词表到内核目标族的映射。</li>
 *   <li>{@code cache/} —— 每 tick 发布的已加载 chunk 快照与搜索侧世界
 *       视图(worker 线程可安全读取)。</li>
 *   <li>{@code calc/} —— 任务层词表与共享 worker 池:{@code NavGoal}
 *       (任务层目标接口)与 {@code PathPlannerPool}。</li>
 *   <li>{@code goal/} —— 意图契约层:{@code GoalCompiler} 把任务意图
 *       编译成 goal + sacred + 到达原料的完整导航契约。</li>
 *   <li>{@code exec/} —— 对外正门:{@code PlayerNav} 三态合同。</li>
 *   <li>{@code settings/} —— 全部旋钮({@code NavSettings})。</li>
 *   <li>{@code util/} —— 方块谓词库({@code BlockHelper})与离线可答的
 *       方块实体视图接口({@code BlockEntityAware})。</li>
 * </ul>
 *
 * <p>对外契约:任务层只经 {@code exec.PlayerNav} + {@code goal.GoalCompiler}
 * (以及自定义目标用的 {@code calc.NavGoal} 词表)使用本包;其余包均为
 * 内部实现,不承诺稳定。
 */
package com.dwinovo.numen.core.pathing;
