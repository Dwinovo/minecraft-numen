package com.dwinovo.numen.pathing.api;

import com.dwinovo.numen.pathing.search.Route;

/**
 * 一次规划的结论:搜出来的路线;没有路线时,{@code outcome} 说为什么(与导航收场的结局同一套),{@code partial} 是朝目标推进得最远的
 * 那一截——搜索没到目标也会把离起点够远的半程路线交出来,那一截是看清了的,之后是什么这次没看到(预算用完、伸出了快照)。
 *
 * @param route   搜出来的路线;没有为 null
 * @param outcome 没有路线时的结局;有路线为 null
 * @param partial 没有路线时朝目标推进的那一截;有路线、或没有离起点够远的一截为 null
 */
public record PlanResult(Route route, Outcome outcome, Route partial) {}
