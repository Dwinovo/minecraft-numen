package com.dwinovo.numen.pathing.drive;

/**
 * 这次导航的行程:段状态机一路上搜了几次、几次因节点预算停下交出半程、几次扔掉路重搜、几步走不下去、几步卡住、复核了几次前提。
 * 计数记在事件发生的那一处——与写日志的是同一处——不另行推算;随实际账({@link EditLedger},含实际走完的各种步)一起交出。
 */
public final class Journal {

    private int searches;
    private int budgetStops;
    private int replans;
    private int blockages;
    private int stuck;
    private int rechecks;

    /** 一次搜索有了结论;{@code budgetStop} 是它因节点预算停下(交出半程路线,接着再搜)。 */
    void searched(boolean budgetStop) {
        searches++;
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

    /** 搜索有了结论的次数(不含为"没有路"而做的诊断)。 */
    public int searches() {
        return searches;
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
}
