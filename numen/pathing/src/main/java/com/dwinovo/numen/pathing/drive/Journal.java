package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.pathing.plan.MoveKind;

/**
 * 这次导航的行程:段状态机一路上搜了几次、几次因节点预算停下交出半程、几次扔掉路重搜、几步走不下去、几步卡住、复核了几次前提。
 * 每种走法走完的步数、实际共用的刻数与最短耗时共多少刻,看得出哪种走法实际比几何最短慢。
 * 计数记在事件发生的那一处——与写日志的是同一处——不另行推算;随实际账({@link EditLedger},含实际走完的各种步)一起交出。
 */
public final class Journal {

    private int searches;
    private final List<Integer> expansions = new ArrayList<>();
    private int budgetStops;
    private int replans;
    private int blockages;
    private int stuck;
    private int rechecks;
    private final Map<MoveKind, Timing> timings = new EnumMap<>(MoveKind.class);

    /**
     * 一种走法走完的步的用时。
     *
     * @param steps     走完几步(有时一步没走完身体已落在后面某一步上,这样的不计)
     * @param ticks     这些步实际共用的刻数
     * @param estimated 这些步开始时算的几何最短耗时(运动学加挖掘的刻数)共多少刻,也是看护定期限的基数
     */
    public record Timing(int steps, int ticks, double estimated) {}

    /** 一次搜索有了结论;{@code budgetStop} 是它因节点预算停下(交出半程路线,接着再搜),{@code expanded} 是它展开了几个节点。 */
    void searched(boolean budgetStop, int expanded) {
        searches++;
        expansions.add(expanded);
        if (budgetStop) {
            budgetStops++;
        }
    }

    /** 扔掉在走的路,从身体脚下重搜。 */
    void replanned() {
        replans++;
    }

    /** 一步走不下去。 */
    void blocked() {
        blockages++;
    }

    /** 一步卡住(做了一阵超过期限)。 */
    void gotStuck() {
        stuck++;
    }

    /** 复核了一步的前提。 */
    void rechecked() {
        rechecks++;
    }

    /** 一步 {@code kind} 走完:实际用了 {@code ticks} 刻,开始时算的最短耗时是 {@code estimated} 刻。 */
    void timed(MoveKind kind, int ticks, double estimated) {
        timings.merge(kind, new Timing(1, ticks, estimated),
                (a, b) -> new Timing(a.steps + b.steps, a.ticks + b.ticks, a.estimated + b.estimated));
    }

    /** 搜索有了结论的次数(不含为"没有路"而做的诊断)。 */
    public int searches() {
        return searches;
    }

    /** 每次搜索展开的节点数,按先后。 */
    public List<Integer> expansions() {
        return Collections.unmodifiableList(expansions);
    }

    /** 其中因节点预算停下的次数。 */
    public int budgetStops() {
        return budgetStops;
    }

    /** 扔掉路重搜的次数。 */
    public int replans() {
        return replans;
    }

    /** 走不下去的次数(同一步三次就收场)。 */
    public int blockages() {
        return blockages;
    }

    /** 卡住的次数。 */
    public int stuck() {
        return stuck;
    }

    /** 复核前提的次数。 */
    public int rechecks() {
        return rechecks;
    }

    /** 每种走法走完的步的用时;没走过的走法不在里面。 */
    public Map<MoveKind, Timing> timings() {
        return Collections.unmodifiableMap(timings);
    }
}
