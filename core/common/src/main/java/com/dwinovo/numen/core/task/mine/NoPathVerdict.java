package com.dwinovo.numen.core.task.mine;

/**
 * 一次「找不到路」算不算「那块够不着」的证据。
 *
 * <h2>为什么要分这一道</h2>
 * 拉黑是<b>永久</b>的(这个任务的余生里不再考虑那个方块),所以它得基于证据。而寻路说
 * 的「没路」永远是<b>就我现在看到的而言</b>——目标图不完整时,这句话只说明她还没看全。
 *
 * <p>真实案例:退出存档再进入,共享目标索引随世界一起丢掉了,第一次查询烧完构建预算
 * 也没扫完请求半径({@code complete=false}),名单里只剩几十格外的一簇,脚边那片还没进
 * 图。她朝唯一知道的那片走,走不到,于是把一个<b>完全正常</b>的方块永久拉黑了。一秒后
 * 图补齐,她就在脚边正常开挖——那一黑纯属冤枉。
 *
 * <p>同一条纪律在「走到了却够不着」那条分支上早就有了(连续 dud 到阈值才拉黑,注释写着
 * 拉黑只该给真正失败的路)。这里把它补齐到「没路」这条分支。
 *
 * <h2>为什么还是会拉黑</h2>
 * 图一直补不齐时不能无限客气:死循环比拉错一个方块更坏。所以等到
 * {@link #MAX_COLD_MAP_FAILS} 次就当它就这样了,回到正常路子。
 */
public final class NoPathVerdict {

    /** 图没查完时,连续容忍多少次无路。索引构建按预算分摊,正常几刻就补齐。 */
    public static final int MAX_COLD_MAP_FAILS = 40;

    /** 这一次没路该怎么办。 */
    public enum Verdict {
        /** 图不完整,不足以定罪:重查,别拉黑。 */
        REQUERY,
        /** 按完整的图看确实没路(或已经等够了):拉黑最近的那个。 */
        BLACKLIST
    }

    private NoPathVerdict() {}

    /**
     * @param mapComplete    上一次目标查询有没有扫完请求半径
     * @param coldFailsSoFar 在此之前,图不完整状态下已经连续无路多少次
     */
    public static Verdict of(boolean mapComplete, int coldFailsSoFar) {
        if (mapComplete) {
            return Verdict.BLACKLIST;
        }
        return coldFailsSoFar + 1 < MAX_COLD_MAP_FAILS ? Verdict.REQUERY : Verdict.BLACKLIST;
    }
}
