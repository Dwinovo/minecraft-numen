package com.dwinovo.numen.pathing.drive;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.pathing.plan.Maneuver;

/**
 * 复原:什么时候再试、什么时候认输。两件事,常数随它走:
 * <ul>
 *   <li>一步走不下去:同一步(走法、起点、落点)记一次,第 {@link #STRIKES} 次就收场,否则从身体此刻的位置重搜;走成了的步一笔勾销,
 *       别的步记着的不清——同一处一圈圈地绕回来、在同一步上没走成,次数要攒得起来;</li>
 *   <li>半程路线连续 {@link #STALE_PARTIALS} 段没让离目标更近,就按那次搜索的停因收场。</li>
 * </ul>
 */
final class Recovery {

    /** 同一步走不下去几次就收场。 */
    static final int STRIKES = 3;
    /** 半程路线连续几段没让离目标更近就收场。 */
    static final int STALE_PARTIALS = 3;

    /** 一步没走成之后怎么办。 */
    record Verdict(int count, boolean giveUp) {}

    private final Map<List<Object>, Integer> strikes = new HashMap<>();
    private Blockage lastBlockage;
    private double bestEstimate = Double.POSITIVE_INFINITY;
    private int stale;

    /** {@code m} 这一步没走成,原因 {@code blockage}。 */
    Verdict failed(Maneuver m, Blockage blockage) {
        lastBlockage = blockage;
        int count = strikes.merge(key(m), 1, Integer::sum);
        return new Verdict(count, count >= STRIKES);
    }

    /** {@code m} 这一步走成了:它的次数一笔勾销,上一次没走成的原因也不再作数。 */
    void walked(Maneuver m) {
        strikes.remove(key(m));
        lastBlockage = null;
    }

    /** 最近一次没走成的原因;之后走成了一步就清掉,没有为 null。 */
    Blockage lastBlockage() {
        return lastBlockage;
    }

    /**
     * 一段没到目标的半程路线交出来了,它的终点离目标的估价是 {@code estimate}。
     *
     * @return 连续几段都没更近,该收场了
     */
    boolean stalled(double estimate) {
        if (estimate < bestEstimate - 1) {
            bestEstimate = estimate;
            stale = 0;
            return false;
        }
        return ++stale >= STALE_PARTIALS;
    }

    /** 目标换了:离目标有多近重新比。 */
    void retarget() {
        bestEstimate = Double.POSITIVE_INFINITY;
        stale = 0;
    }

    private static List<Object> key(Maneuver m) {
        return List.of(m.kind(), m.from(), m.to());
    }
}
