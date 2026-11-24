package com.dwinovo.numen.permission;

import java.util.function.Function;

/**
 * 把一圈回答者的话合成一个结果,顺序固定,先碰到哪条算哪条:
 * <ol>
 *   <li>有回答者说不许 → 不许;</li>
 *   <li>在主人"不用问"的名单里 → 许;</li>
 *   <li>有回答者说要问 → 问;</li>
 *   <li>有回答者说许 → 许;</li>
 *   <li>都不表态 → 主人给这个动作定的档。</li>
 * </ol>
 * 不认识任何游戏里的东西:回答者是什么、说的话怎么得来,都是调用方的事。几个回答者说同一种话时取先问到的那个。
 * 搜索线程给每一格定价也走这里,所以只扫一遍回答者、不分配集合。
 */
public final class Merge {

    private Merge() {}

    /** 结果是哪一步定下的。 */
    public enum Step {
        ANSWERER_DENIED, LISTED, ANSWERER_ASKED, ANSWERER_ALLOWED, TIER
    }

    /**
     * @param outcome 结果:许、问、不许
     * @param step    哪一步定下的
     * @param by      定下它的那个回答者;名单与档位定下的为 null
     */
    public record Result<A>(Tier outcome, Step step, A by) {}

    /**
     * @param tier    主人给这个动作定的档
     * @param listed  对象在主人给这个动作的"不用问"名单里
     * @param answers 回答者们,按问的先后
     * @param say     一个回答者对这一件事说什么
     */
    public static <A> Result<A> merge(Tier tier, boolean listed, Iterable<A> answers, Function<A, Say> say) {
        A asked = null;
        A allowed = null;
        for (A answerer : answers) {
            switch (say.apply(answerer)) {
                case DENY -> {
                    return new Result<>(Tier.DENY, Step.ANSWERER_DENIED, answerer);
                }
                case ASK -> asked = asked == null ? answerer : asked;
                case ALLOW -> allowed = allowed == null ? answerer : allowed;
                case ABSTAIN -> { }
            }
        }
        if (listed) {
            return new Result<>(Tier.ALLOW, Step.LISTED, null);
        }
        if (asked != null) {
            return new Result<>(Tier.ASK, Step.ANSWERER_ASKED, asked);
        }
        if (allowed != null) {
            return new Result<>(Tier.ALLOW, Step.ANSWERER_ALLOWED, allowed);
        }
        return new Result<>(tier, Step.TIER, null);
    }
}
