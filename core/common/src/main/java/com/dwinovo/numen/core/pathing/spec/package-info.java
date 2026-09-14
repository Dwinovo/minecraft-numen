/**
 * 路线规格:一次导航"能做什么、每样多贵"的按次传值数据。
 *
 * <ul>
 *   <li>{@link com.dwinovo.numen.core.pathing.spec.RouteSpec} —— 四组旋钮:能力开关、
 *       每类格子的代价、按坐标的代价、动作代价。不可变,搜索工作线程只读。</li>
 *   <li>{@link com.dwinovo.numen.core.pathing.spec.CellClass} —— 格子分类词汇与全仓唯一的
 *       分类函数;可穿行/可站立/危险/流体/门这些判定全部从它派生。</li>
 *   <li>{@link com.dwinovo.numen.core.pathing.spec.PositionCosts} —— 坐标到代价的叠加表,
 *       踩/穿/挖/放四栏,无穷大即禁止。</li>
 * </ul>
 *
 * <p>规格是数据,分类是函数,成本模型({@code moves.CalculationContext} 与移动原语)只读它们。
 * 服主总开关(允许挖/放/疾跑、超时、节点上限)留在 {@code settings.NavSettings},规格只能在
 * 那道天花板之下收紧。
 */
package com.dwinovo.numen.core.pathing.spec;
