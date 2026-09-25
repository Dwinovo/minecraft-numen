package com.dwinovo.numen.core.task.mine;

/**
 * 一次「找不到路」算不算「剩下的都够不着」的证据。
 *
 * <h2>为什么要分这一道</h2>
 * 够不着就收工,所以它得基于证据。而寻路说的「没路」永远是<b>就我现在看到的而言</b>——
 * 目标图还没回来时,她只认得地上的掉落物,朝它们搜不出路说明不了名单里将会有的那些目标。
 *
 * <h2>为什么只看图回没回来</h2>
 * 目标查询按工作量收工({@link com.dwinovo.numen.core.scan.BlockSearch}),回来的就是这个问法在这个
 * 世界上的完整答案,截断也是确定的截断:重查不会更全,所以图回来之后的无路就是证据。图没回来之前
 * 的无路一律不算,也不设"等够几次就算"的上限——那样的上限数的是寻路失败得多快、查询回来得多慢,
 * 结论就随机器快慢变了;而查询的工作量有界,图总会回来。
 */
public final class NoPathVerdict {

    /** 这一次没路该怎么办。 */
    public enum Verdict {
        /** 图还没回来,不足以定罪:等它,别拉黑。 */
        REQUERY,
        /** 按回来的图看确实没路:剩下的都够不着。 */
        UNREACHABLE
    }

    private NoPathVerdict() {}

    /** @param mapped 目标查询有没有回来过 */
    public static Verdict of(boolean mapped) {
        return mapped ? Verdict.UNREACHABLE : Verdict.REQUERY;
    }
}
