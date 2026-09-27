package com.dwinovo.numen.pathing.api;

import java.util.List;

import com.dwinovo.numen.pathing.search.Route;

/**
 * 一次规划的结论:搜出来的候选路线,各带一份预算账;一条都没有时,{@code outcome} 说为什么(与导航收场的结局同一套)。
 *
 * @param outcome 一条候选都没有时的结局;有候选为 null
 */
public record PlanResult(List<Candidate> candidates, Outcome outcome) {

    /** 一条候选:路线与它的预算账。 */
    public record Candidate(Route route, Bill bill) {}

    public PlanResult {
        candidates = List.copyOf(candidates);
    }
}
