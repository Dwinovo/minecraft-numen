/**
 * 第 2 层 搜索:在派发那一刻拷下的只读快照上,按第 1 层的走法与成本模型找路。
 *
 * <ul>
 *   <li>{@link com.dwinovo.numen.pathing.search.Goal} 与 {@link com.dwinovo.numen.pathing.search.Goals} —— 唯一一族目标:
 *       六种到达(位置、距离范围、站上去、用、挖、远离)各自的到没到、估价、到达价、目标格保护、到了要看得见的;换目标后在走的路还算不算数({@link com.dwinovo.numen.pathing.search.Goal#keepsStop});</li>
 *   <li>{@link com.dwinovo.numen.pathing.search.AStar} —— 按展开节点数计预算,结论带停下的原因,改动预算在展开时生效;</li>
 *   <li>{@link com.dwinovo.numen.pathing.search.Origin} —— 从身体的真实位置定出起点节点;</li>
 *   <li>{@link com.dwinovo.numen.pathing.search.Favoring} —— 重新规划时旧路打折;</li>
 *   <li>{@link com.dwinovo.numen.pathing.search.RoutePlanner} —— 惩罚法出候选路线;</li>
 *   <li>{@link com.dwinovo.numen.pathing.search.WorldSnapshot} 与 {@link com.dwinovo.numen.pathing.search.Searches} ——
 *       快照与唯一的派发口。</li>
 * </ul>
 */
package com.dwinovo.numen.pathing.search;
