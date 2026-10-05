/**
 * 第 1 层 规划:每种走法的前提与代价,以及它们读的成本模型。只读只读视图({@link com.dwinovo.numen.pathing.plan.WorldView})、
 * 第 0 层与成本模型,不接触实体与活世界;几何一律问第 0 层。
 *
 * <ul>
 *   <li>{@link com.dwinovo.numen.pathing.plan.Move} —— 一种走法的两个纯函数:前提({@link com.dwinovo.numen.pathing.plan.Premise},
 *       成立时交出 {@link com.dwinovo.numen.pathing.plan.Maneuver},含要做的 {@link com.dwinovo.numen.pathing.plan.Edit})与代价。
 *       全部走法在 {@link com.dwinovo.numen.pathing.plan.Moves};</li>
 *   <li>{@link com.dwinovo.numen.pathing.plan.Stance} —— 身体在节点上站着、攀着还是浮着;</li>
 *   <li>{@link com.dwinovo.numen.pathing.plan.CostModel} —— 物理代价({@link com.dwinovo.numen.pathing.plan.ActionCosts})、
 *       路线规格、许可、垫路料、身体快照、生物危险组合成的成本模型;挖与放的准入与定价只在这里;</li>
 *   <li>{@link com.dwinovo.numen.pathing.plan.DigRules}、{@link com.dwinovo.numen.pathing.plan.DigTime}、
 *       {@link com.dwinovo.numen.pathing.plan.ToolChoice} —— 挖不挖得了、挖多久、用哪件工具;</li>
 *   <li>端口 {@link com.dwinovo.numen.pathing.plan.TerrainPolicy}、{@link com.dwinovo.numen.pathing.plan.Materials}、
 *       {@link com.dwinovo.numen.pathing.plan.Threats},与值对象 {@link com.dwinovo.numen.pathing.plan.BodySnapshot}:宿主交进来的事实。</li>
 * </ul>
 */
package com.dwinovo.numen.pathing.plan;
