package com.dwinovo.numen.core.task.mine;

/**
 * 一次「找不到路」算不算「剩下的都够不着」的证据。
 *
 * <h2>为什么要分这一道</h2>
 * 够不着就收工,所以它得基于证据。而寻路说的「没路」永远是<b>就我现在看到的而言</b>——
 * 目标图不完整时,这句话只说明她还没看全。
 *
 * <p>真实案例:退出存档再进入,共享目标索引随世界一起丢掉了,第一次查询烧完构建预算
 * 也没扫完请求半径({@code complete=false}),名单里只剩几十格外的一簇,脚边那片还没进
 * 图。她朝唯一知道的那片走,走不到——而一秒后图补齐,她就在脚边正常开挖。拿那一次无路
 * 收工纯属冤枉。
 *
 * <h2>为什么还是会收工</h2>
 * 图一直补不齐时不能无限客气:原地重查一辈子比如实收工更坏。所以等到
 * {@link #MAX_COLD_MAP_FAILS} 次就当它就这样了,回到正常路子。
 */
public final class NoPathVerdict {

    /** 图没查完时,连续容忍多少次无路。索引构建按预算分摊,正常几刻就补齐。 */
    public static final int MAX_COLD_MAP_FAILS = 40;

    /** 这一次没路该怎么办。 */
    public enum Verdict {
        /** 图不完整,不足以定罪:重查,别拉黑。 */
        REQUERY,
        /** 按完整的图看确实没路(或已经等够了):剩下的都够不着。 */
        UNREACHABLE
    }

    private NoPathVerdict() {}

    /**
     * @param mapComplete    上一次目标查询有没有扫完请求半径
     * @param coldFailsSoFar 在此之前,图不完整状态下已经连续无路多少次
     */
    public static Verdict of(boolean mapComplete, int coldFailsSoFar) {
        if (mapComplete) {
            return Verdict.UNREACHABLE;
        }
        return coldFailsSoFar + 1 < MAX_COLD_MAP_FAILS ? Verdict.REQUERY : Verdict.UNREACHABLE;
    }
}
