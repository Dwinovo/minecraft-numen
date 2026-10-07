package com.dwinovo.numen.pathing.api;

import com.dwinovo.numen.pathing.drive.PathLog;
import com.dwinovo.numen.pathing.search.Pending;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.search.Search;
import com.dwinovo.numen.pathing.search.SearchResult;
import com.dwinovo.numen.pathing.search.Searches;

/**
 * 一次在工作线程上跑的规划。不占身体、不碰世界;发起方每刻 {@link #poll} 一次,拿到结论为止。没有路线时,
 * 接着在同一份快照上诊断为什么没路。
 */
public final class Planning {

    private Pending<SearchResult> planning;
    private final Search search;
    /** 日志里的"谁"。 */
    private final String who;
    private Pending<Outcome> diagnosis;
    /** 没有路线时朝目标推进的那一截,等诊断回来一并交出;没有为 null。 */
    private Route partial;
    private PlanResult result;

    /** 起点上身体待不住,当场就有结论。 */
    Planning(PlanResult result) {
        this.search = null;
        this.who = null;
        this.result = result;
    }

    /**
     * @param search 这次规划的那一次搜索:没有路线时同一份快照、成本模型、起点、目标、预算上拿它诊断
     */
    Planning(Pending<SearchResult> planning, Search search, String who) {
        this.planning = planning;
        this.search = search;
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
                PathLog.info("{} 规划 {} 去 {} {} 没有路线 -> {} 诊断 {}", who, PathLog.pos(search.start()), search.goal(),
                        PathLog.spec(search.model().spec()), Navigation.describe(outcome), PathLog.ms(diagnosis.ranNanos()));
                result = new PlanResult(null, outcome, partial);
            }
            return result;
        }
        SearchResult found = planning.poll();
        if (found == null) {
            return null;
        }
        if (found.arrived()) {
            PathLog.info("{} 规划 {} 去 {} {} 用时 {} 排队 {}:{}", who, PathLog.pos(search.start()), search.goal(),
                    PathLog.spec(search.model().spec()), PathLog.ms(planning.ranNanos()), PathLog.ms(planning.queuedNanos()),
                    PathLog.route(found.route()));
            result = new PlanResult(found.route(), null, null);
            return result;
        }
        partial = found.route();
        diagnosis = Searches.submit(cancelled -> Diagnosis.of(found.stop(), found.breathless(), search, cancelled));
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
