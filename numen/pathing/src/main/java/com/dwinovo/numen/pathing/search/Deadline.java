package com.dwinovo.numen.pathing.search;

import java.util.Objects;

/**
 * 一次搜索最多想多久:看哪块表({@link SearchClock})、多少纳秒。表在搜索真开跑时才开始走(排队的时间不算),
 * 时间到了交出手里最好的半程({@link SearchResult.Stop#TIME})。
 *
 * <p>时限是整个规划周期的:搜索没到目标时,交出半程之前还要学习(抬高估价,{@link Learning}),它的工作量随搜到的边数线性长,
 * 所以搜索只用时限的 {@code (LEARNING_SHARE_DENOMINATOR - 1) / LEARNING_SHARE_DENOMINATOR},余下的留给学习
 * (LSS-LRTA* 把学习算在每次规划的预算里 [Koenig &amp; Sun 2009])。表({@link SearchClock})量的只是搜索那一段。
 *
 * <p>{@link #NONE} 不限时:只搜不走的规划、诊断"为什么没路"、单测,结论只由节点上限定,与时钟无关。
 */
public record Deadline(SearchClock clock, long nanos) {

    /** 不限时。 */
    public static final Deadline NONE = new Deadline(nanos -> (expanded, checked) -> false, Long.MAX_VALUE);

    public Deadline {
        Objects.requireNonNull(clock, "clock");
        if (nanos <= 0) {
            throw new IllegalArgumentException("时限要是正数:" + nanos);
        }
    }

    /**
     * 留给学习的份额是时限的这分之一。学习的耗时与搜索成固定比例(随搜到的边数线性长,边数随展开数线性长):
     * 靶场实测(AutoDL,3 s 与 0.5 s 两档,mountains、hills、forest、caves 的整批题)学习占搜索的中位 4.5%、P90 6.3%,
     * 取 1/16(6.25%)让 P90 的搜索也在时限内收场。
     */
    static final long LEARNING_SHARE_DENOMINATOR = 16;

    /** 开搜:搜索那一段最多想时限去掉学习份额之后那么久。 */
    SearchClock.Timer start() {
        return clock.start(nanos - nanos / LEARNING_SHARE_DENOMINATOR);
    }
}
