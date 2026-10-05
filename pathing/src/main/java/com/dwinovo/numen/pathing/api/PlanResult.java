package com.dwinovo.numen.pathing.api;

import java.util.List;

import com.dwinovo.numen.pathing.search.Route;

/**
 * 一次规划的结论:搜出来的候选路线,各带一份预算账;一条都没有时,{@code outcome} 说为什么(与导航收场的结局同一套),
 * {@code partial} 是朝目标推进得最远的那一截——搜索没到目标也会把离起点够远的半程路线交出来,那一截是看清了的,之后是什么
 * 这次没看到(预算用完、伸出了快照)。
 *
 * @param outcome 一条候选都没有时的结局;有候选为 null
 * @param partial 一条候选都没有时朝目标推进的那一截,连同它的预算账;有候选、或没有离起点够远的一截为 null
 */
public record PlanResult(List<Candidate> candidates, Outcome outcome, Candidate partial) {

    /** 一条候选:路线与它的预算账。 */
    public record Candidate(Route route, Bill bill) {

        public static Candidate of(Route route) {
            return new Candidate(route, Bill.of(route));
        }
    }

    public PlanResult {
        candidates = List.copyOf(candidates);
    }
}
