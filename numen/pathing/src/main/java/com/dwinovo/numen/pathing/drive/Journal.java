package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.search.SearchResult;

/**
 * 这次导航的行程:段状态机一路上搜了几次、几次没到目标(抬高了估价)、按什么原因重规划了几次、在活世界上判了几次前提、
 * 判前提共用了多久。每种走法走完的步数、实际共用的刻数与最短耗时共多少刻,看得出哪种走法实际比几何最短慢。
 * 计数记在事件发生的那一处——与写日志的是同一处——不另行推算;随实际账({@link EditLedger},含实际走完的各种步)一起交出。
 */
public final class Journal {

    /**
     * 重规划的原因。段状态机里每一次扔掉在走的路、或从路线终点接着搜,都按这里的一种记一次({@link Driver} 只在一处记)。
     * {@link #blocks} 的几种是某一步走不通:那一步在这一趟的记忆里记为不通,以后的搜索不再走它。
     */
    public enum Replan {
        /** 一步开始前往前核,接下来几步里有一步的前提在活世界上不成立({@link Lookahead})。 */
        AHEAD("往前核不成立", true),
        /** 一步走着走不下去:动不了手、憋不住气、落回前一步的起点、落到路线之外、停不到终点。 */
        FAILED("一步走不下去", true),
        /** 一步超过了期限({@link Watchdog})。 */
        TIMEOUT("一步超时", true),
        /** 还没开始走下一步,上一步的冲劲把身体带出了路线。 */
        OFF_ROUTE("离开路线", false),
        /** 路线走完、停稳了,到达判定说要重搜(世界在终点那里变了)。 */
        ARRIVAL("到了不算到", false),
        /** 接续段证明路线的终点是死路。 */
        DEAD_END("终点是死路", false),
        /** 半程路线快走完,从它的终点接着搜下一段({@link Continuation})。 */
        CONTINUE("半程快走完", false);

        /** 日志与汇总里的叫法。 */
        public final String label;
        /** 是某一步走不通。 */
        public final boolean blocks;

        Replan(String label, boolean blocks) {
            this.label = label;
            this.blocks = blocks;
        }
    }

    private int searches;
    private final List<Integer> expansions = new ArrayList<>();
    private final List<SearchResult.Stop> stops = new ArrayList<>();
    private final List<Long> searchNanos = new ArrayList<>();
    private int learned;
    /** 各次搜索取出候选时精确判断的账,加在一起。 */
    private final SearchResult.Checks checks = SearchResult.Checks.none();
    private double routeCost;
    private final Map<Replan, Integer> replans = new EnumMap<>(Replan.class);
    private int rechecks;
    private long recheckNanos;
    private final Map<MoveKind, Timing> timings = new EnumMap<>(MoveKind.class);

    /**
     * 一种走法走完的步的用时。
     *
     * @param steps     走完几步(冲劲把身体带过几步、落在后面某一步上时,带过的几步也算走完)
     * @param ticks     这些步实际共用的刻数(一次走完几步的,按各步最短耗时的比例分)
     * @param estimated 这些步的几何最短耗时(运动学加挖掘的刻数)共多少刻
     */
    public record Timing(int steps, double ticks, double estimated) {}

    /**
     * 一次搜索有了结论;{@code expanded} 是它展开了几个节点,{@code nanos} 是它在工作线程上跑了多久,{@code stop} 是它为什么停,{@code learned} 是它没到目标、
     * 展开过的格子的估价抬高了,{@code checks} 是它取出候选时精确判断的账。
     */
    void searched(int expanded, long nanos, boolean learned, SearchResult.Stop stop, SearchResult.Checks checks) {
        searches++;
        stops.add(stop);
        for (int k = 0; k < checks.checked().length; k++) {
            this.checks.checked()[k] += checks.checked()[k];
            this.checks.refused()[k] += checks.refused()[k];
            this.checks.dearer()[k] += checks.dearer()[k];
        }
        expansions.add(expanded);
        searchNanos.add(nanos);
        if (learned) {
            this.learned++;
        }
    }

    /** 按 {@code why} 重规划了一次。 */
    void replanned(Replan why) {
        replans.merge(why, 1, Integer::sum);
    }

    /** 在活世界上判了 {@code count} 步的前提(连同为它们组的成本模型),共用了 {@code nanos}。 */
    void rechecked(int count, long nanos) {
        rechecks += count;
        recheckNanos += nanos;
    }

    /** 走完了一步,它在规划里的代价是 {@code cost}。 */
    void walked(double cost) {
        routeCost += cost;
    }

    /** 一步 {@code kind} 走完:实际用了 {@code ticks} 刻,几何最短耗时是 {@code estimated} 刻。 */
    void timed(MoveKind kind, double ticks, double estimated) {
        timings.merge(kind, new Timing(1, ticks, estimated),
                (a, b) -> new Timing(a.steps + b.steps, a.ticks + b.ticks, a.estimated + b.estimated));
    }

    /** 这一趟各次搜索取出候选时精确判断的账(按走法),加在一起:判了几次、几次不成立、几次真价比估价贵。 */
    public SearchResult.Checks checks() {
        return new SearchResult.Checks(checks.checked().clone(), checks.refused().clone(), checks.dearer().clone());
    }

    /** 搜索有了结论的次数(不含为"没有路"而做的诊断)。 */
    public int searches() {
        return searches;
    }

    /** 每次搜索展开的节点数,按先后。 */
    public List<Integer> expansions() {
        return Collections.unmodifiableList(expansions);
    }

    /** 每次搜索为什么停,按先后。 */
    public List<SearchResult.Stop> stops() {
        return Collections.unmodifiableList(stops);
    }

    /** 每次搜索在工作线程上跑了多少纳秒,按先后。 */
    public List<Long> searchNanos() {
        return Collections.unmodifiableList(searchNanos);
    }

    /** 实际走完的各步按规划算的代价之和:这条路线值多少钱。 */
    public double routeCost() {
        return routeCost;
    }

    /** 其中没到目标、抬高了估价往边界推进的次数。 */
    public int learned() {
        return learned;
    }

    /** 按 {@code why} 重规划的次数。 */
    public int replans(Replan why) {
        return replans.getOrDefault(why, 0);
    }

    /** 每种原因重规划的次数;没发生过的原因不在里面。 */
    public Map<Replan, Integer> replans() {
        return Collections.unmodifiableMap(replans);
    }

    /** 走不通的步数:按 {@link Replan#blocks} 的原因重规划的次数之和,每一步在这一趟里记为不通,以后的搜索不再走它。 */
    public int blockages() {
        int n = 0;
        for (Map.Entry<Replan, Integer> e : replans.entrySet()) {
            if (e.getKey().blocks) {
                n += e.getValue();
            }
        }
        return n;
    }

    /** 在活世界上判前提的步数(每一步开始前往前核的每一步各算一次)。 */
    public int rechecks() {
        return rechecks;
    }

    /** 在活世界上判前提共用了多少纳秒(主线程)。 */
    public long recheckNanos() {
        return recheckNanos;
    }

    /** 每种走法走完的步的用时;没走过的走法不在里面。 */
    public Map<MoveKind, Timing> timings() {
        return Collections.unmodifiableMap(timings);
    }
}
