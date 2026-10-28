package com.dwinovo.numen.pathing.search;

import java.util.Objects;

/**
 * 一次搜索最多想多久:看哪块表({@link SearchClock})、多少纳秒。表在搜索真开跑时才开始走(排队的时间不算),
 * 时间到了交出手里最好的半程({@link SearchResult.Stop#TIME})。
 *
 * <p>{@link #NONE} 不限时:只搜不走的规划、诊断"为什么没路"、单测,结论只由节点上限定,与时钟无关。
 */
public record Deadline(SearchClock clock, long nanos) {

    /** 不限时。 */
    public static final Deadline NONE = new Deadline(nanos -> expanded -> false, Long.MAX_VALUE);

    public Deadline {
        Objects.requireNonNull(clock, "clock");
        if (nanos <= 0) {
            throw new IllegalArgumentException("时限要是正数:" + nanos);
        }
    }

    /** 开搜。 */
    SearchClock.Timer start() {
        return clock.start(nanos);
    }
}
