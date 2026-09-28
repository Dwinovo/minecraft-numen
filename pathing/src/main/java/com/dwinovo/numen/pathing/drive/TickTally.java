package com.dwinovo.numen.pathing.drive;

/**
 * 一刻里主线程上寻路做的活用了多久:段状态机(连同撤回垫块)每刻开头 {@link #begin}、结尾 {@link #end},中间把拷快照、组成本模型、
 * 复核前提、经宿主的手动手各记一笔。整刻超过 {@link PathLog#MAIN_THREAD_WARN_NANOS} 就记一行 WARN,说清这一刻做了哪几件、
 * 各用了多久;余下的是认步、按键、转头、准星与拿东西到手上。套着跑的(撤回垫块时走一趟)只在最外层开头清零、结尾结算。
 */
final class TickTally {

    private int depth;
    private long start;
    private long snapshot;
    private long model;
    private long recheck;
    private long acted;
    private int searches;
    private int rechecks;

    void begin() {
        if (depth++ == 0) {
            start = System.nanoTime();
            snapshot = 0;
            model = 0;
            recheck = 0;
            acted = 0;
            searches = 0;
            rechecks = 0;
        }
    }

    /** 派了一次搜索:拷快照与组成本模型各用了多久。 */
    void dispatched(long snapshotNanos, long modelNanos) {
        snapshot += snapshotNanos;
        model += modelNanos;
        searches++;
    }

    /** 复核了一步的前提(连同为它组的成本模型),用了多久。 */
    void rechecked(long nanos) {
        recheck += nanos;
        rechecks++;
    }

    /** 经宿主的手动了一下(挖、放、开门、倒水),用了多久。 */
    void acted(long nanos) {
        acted += nanos;
    }

    void end(String who) {
        if (--depth > 0) {
            return;
        }
        long total = System.nanoTime() - start;
        if (total > PathLog.MAIN_THREAD_WARN_NANOS) {
            PathLog.warn("{} 主线程这一刻寻路用了 {}(阈值 {}):派搜索 {} 次(拷快照 {} 组成本模型 {}) 复核 {} 次 {} 动手 {} 其余 {}",
                    who, PathLog.ms(total), PathLog.ms(PathLog.MAIN_THREAD_WARN_NANOS), searches, PathLog.ms(snapshot),
                    PathLog.ms(model), rechecks, PathLog.ms(recheck), PathLog.ms(acted),
                    PathLog.ms(total - snapshot - model - recheck - acted));
        }
    }
}
