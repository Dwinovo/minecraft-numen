package com.dwinovo.numen.pathing.api;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.drive.PathLog;
import com.dwinovo.numen.pathing.search.Pending;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.search.RoutePlanner;
import com.dwinovo.numen.pathing.search.Search;
import com.dwinovo.numen.pathing.search.Searches;

/**
 * 一次在工作线程上跑的规划。不占身体、不碰世界;发起方每刻 {@link #poll} 一次,拿到结论为止。候选一条都没有时,
 * 接着在同一份快照上诊断为什么没路。
 */
public final class Planning {

    private Pending<RoutePlanner.Plan> planning;
    private final Search probe;
    /** 日志里的"谁"。 */
    private final String who;
    private Pending<Outcome> diagnosis;
    /** 没有候选时朝目标推进的那一截,等诊断回来一并交出;没有为 null。 */
    private PlanResult.Candidate partial;
    private PlanResult result;

    /** 起点上身体待不住,当场就有结论。 */
    Planning(PlanResult result) {
        this.probe = null;
        this.who = null;
        this.result = result;
    }

    /**
     * @param probe 与这次规划同一份快照、成本模型、起点、目标、预算的一次搜索:没有候选时拿它诊断
     */
    Planning(Pending<RoutePlanner.Plan> planning, Search probe, String who) {
        this.planning = planning;
        this.probe = probe;
        this.who = who;
    }

    /** 有结论了就交出,没有为 null。 */
    public PlanResult poll() {
        if (result != null) {
            return result;
        }
        if (diagnosis != null) {
            Outcome outcome = diagnosis.poll();
            if (outcome != null) {
                PathLog.info("{} 规划 {} 去 {} {} 没有候选 -> {} 诊断 {}", who, PathLog.pos(probe.start()), probe.goal(),
                        PathLog.spec(probe.model().spec()), Navigation.describe(outcome), PathLog.ms(diagnosis.ranNanos()));
                result = new PlanResult(List.of(), outcome, partial);
            }
            return result;
        }
        RoutePlanner.Plan plan = planning.poll();
        if (plan == null) {
            return null;
        }
        if (!plan.candidates().isEmpty()) {
            List<PlanResult.Candidate> candidates = new ArrayList<>();
            StringBuilder listed = new StringBuilder();
            for (Route route : plan.candidates()) {
                candidates.add(PlanResult.Candidate.of(route));
                listed.append(" [").append(candidates.size()).append("] ").append(PathLog.route(route));
            }
            PathLog.info("{} 规划 {} 去 {} {} 候选 {} 条 用时 {} 排队 {}:{}", who, PathLog.pos(probe.start()), probe.goal(),
                    PathLog.spec(probe.model().spec()), candidates.size(), PathLog.ms(planning.ranNanos()),
                    PathLog.ms(planning.queuedNanos()), listed);
            result = new PlanResult(candidates, null, null);
            return result;
        }
        partial = plan.partial() == null ? null : PlanResult.Candidate.of(plan.partial());
        diagnosis = Searches.submit(cancelled -> Diagnosis.of(plan.unreached(), plan.breathless(), probe, cancelled));
        return null;
    }

    /** 不要了。 */
    public void cancel() {
        if (planning != null) {
            planning.cancel();
        }
        if (diagnosis != null) {
            diagnosis.cancel();
        }
    }
}
