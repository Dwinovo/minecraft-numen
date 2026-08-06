package com.dwinovo.numen.core.combat;

/**
 * 这一刻该怎么打。<b>本能链与 {@code attack} 工具共用这一份</b>——爬行者该退多远,
 * 不该在反射里写一遍、在工具里再写一遍。
 *
 * <h2>两个维度,不是一张表</h2>
 * <ul>
 *   <li><b>够不够得着</b> → 决定拿什么武器。够得着就砍,够不着但走得到就走过去,
 *       走不到才用远程。</li>
 *   <li><b>该不该靠近</b> → 决定要不要保持距离。见 {@link Menace}。</li>
 * </ul>
 *
 * <h2>「会飞」不是一档</h2>
 * 它只是"够不着"的一个来源,而且是个会骗人的来源:幻翼会俯冲下来,站着等它下来一剑一个,
 * 比朝天上放空箭容易得多;恶魂则永远够不着。真判据是寻路走不走得到——
 * {@code PlayerNav} 的 {@code FAILED} 就是答案,不必先认识这只怪是什么。
 *
 * <h2>点火与否为什么不在这里</h2>
 * 因为它不改变结论。走近一只没点火的爬行者,它就会点火(原版 3 格内起爆倒计时),
 * 所以「该不该靠近」的答案与当前引信状态无关。引信只决定<b>躲得多急</b>,那是势场权重的事。
 */
public final class AttackPlan {

    /** 这一刻走哪条路。 */
    public enum Stance {
        /** 就地挥击。 */
        MELEE,
        /** 走过去,近到能挥击为止。 */
        CLOSE_IN,
        /** 拉开弓弩射它。 */
        RANGED,
        /** 主动拉开距离——它够得着但不该贴上去。 */
        AVOID,
        /** 这只打不了:走不到,又没有远程手段。 */
        ABANDON
    }

    /**
     * 判据的全部输入。<b>一个方法一个入参,没有重载</b>——一个状态若既能进这里又能当
     * 补充参数传,迟早会出现两处不一致的调用。
     *
     * @param distance         身体到目标的距离
     * @param meleeReach       她这一刻的近战够到距离
     * @param reachable        寻路还没判定"到不了"
     * @param hasRanged        背包里有能立刻用的弓弩(带箭,或已上弦的弩)
     * @param keepAway         这个目标够得着也不该贴上去,见 {@link Menace#keepAwayFrom}
     * @param keepAwayDistance 该保持的距离;{@code keepAway} 为假时不看
     */
    public record Situation(double distance,
                            double meleeReach,
                            boolean reachable,
                            boolean hasRanged,
                            boolean keepAway,
                            double keepAwayDistance) {}

    private AttackPlan() {}

    public static Stance decide(Situation s) {
        if (s.keepAway()) {
            // 站得够远才谈得上出手;而近战对这种目标从来不是选项——贴上去就是进爆炸半径。
            if (s.distance() < s.keepAwayDistance()) {
                return Stance.AVOID;
            }
            return s.hasRanged() ? Stance.RANGED : Stance.ABANDON;
        }
        if (s.distance() <= s.meleeReach()) {
            return Stance.MELEE;
        }
        if (s.reachable()) {
            return Stance.CLOSE_IN;   // 走得到就走过去砍:这同时也是省箭的那一支
        }
        return s.hasRanged() ? Stance.RANGED : Stance.ABANDON;
    }
}
