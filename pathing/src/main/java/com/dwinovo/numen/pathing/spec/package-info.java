/**
 * 路线规格:一次导航"能做什么、每样多贵"的按次传值数据。
 *
 * <ul>
 *   <li>{@link com.dwinovo.numen.pathing.spec.RouteSpec} —— 四组旋钮:能力开关与上限、排除的格子种类、按位置与按种类、
 *       动作代价。不可变,搜索线程只读。</li>
 *   <li>{@link com.dwinovo.numen.pathing.spec.PositionCosts} —— 坐标上的禁令与加价,踩、穿、挖、放四栏。</li>
 *   <li>{@link com.dwinovo.numen.pathing.spec.BlockBans} —— 按方块种类的禁令,挖、放、踩三栏。</li>
 * </ul>
 *
 * <p>规格只是数据;格子是哪一种由第 0 层的 {@link com.dwinovo.numen.pathing.world.Semantics} 回答,规格只说排除哪几种。
 */
package com.dwinovo.numen.pathing.spec;
