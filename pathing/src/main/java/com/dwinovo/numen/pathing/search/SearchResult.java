package com.dwinovo.numen.pathing.search;

/**
 * 一次搜索的结论:为什么停,以及交出的路线。到了目标交出整条;没到时,只有离起点足够远的半程路线才交出,否则没有路线。
 *
 * @param stop     为什么停
 * @param route    交出的路线;没有为 null
 * @param expanded 展开了几个节点
 */
public record SearchResult(Stop stop, Route route, int expanded) {

    /** 搜索为什么停。"没有路"只有 {@link #EXHAUSTED} 能说;预算用完、碰到没加载的区块只是没搜完。 */
    public enum Stop {
        /** 到了目标。 */
        ARRIVED,
        /** 按这次的规格走得到的节点全搜过了:真的到不了。 */
        EXHAUSTED,
        /** 展开节点的预算用完了,还没搜完;或展开到了先交半程的节点数({@link Search#handOver}),先交出了一段。 */
        BUDGET,
        /** 走得到的都搜过了,但有路伸进了快照之外没加载的区块,那边是什么不知道。 */
        UNLOADED,
        /** 起点上身体站不住、攀不住也浮不住,无从出发。 */
        STRANDED,
        /** 被叫停了。 */
        CANCELLED
    }

    public boolean arrived() {
        return stop == Stop.ARRIVED;
    }
}
