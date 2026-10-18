package com.dwinovo.numen.permission;

import java.util.List;

/**
 * 一次裁决用的规则集合:模式、硬规则、从高到低的几层规则。不可变,任何线程只读。
 *
 * <h2>判定顺序</h2>
 * <ol>
 *   <li><b>模式</b>:{@link Mode#OBSERVE} 拒一切改世界的动作;{@link Mode#BYPASS} 放行一切——硬规则除外(下一步先判);</li>
 *   <li><b>硬规则</b>:命中即拒,模式、主人层都解不开;</li>
 *   <li><b>任何一层的 deny</b>:命中即拒——明确的禁止永远赢,与书写顺序、与哪一层无关;</li>
 *   <li>各层按高到低,层内 <b>allow</b> 先于 <b>ask</b>:第一个命中的 allow 放行,命中的 ask 去问;</li>
 *   <li>都不中:问。</li>
 * </ol>
 * 问出来的动作若已被任务期授权覆盖({@link Grants}),就是放行——授权只覆盖问,解不开拒绝。
 * 代码里不写死"能"也不写死"不能":放行只来自 allow 行与主人选的 bypass,拒绝只来自 deny 行、硬规则与主人选的 observe。
 */
public final class Policy {

    /** 任务期授权:主人答应过的事。 */
    @FunctionalInterface
    public interface Grants {
        /** 授权覆盖不覆盖这个被问出来的动作;{@code hit} 是它命中的 ask 行,没有任何一行覆盖时为 null。 */
        boolean covers(Rule hit);
    }

    /** 没有任何任务期授权。 */
    public static final Grants NO_GRANTS = hit -> false;

    private final Mode mode;
    private final List<Rule> hard;
    /** 先查的在前:主人层,出厂层。 */
    private final List<RuleSet> layers;

    /**
     * @param hard   硬规则(都是拒绝);服主关掉的不在里面
     * @param layers 从高到低的规则层
     */
    public Policy(Mode mode, List<Rule> hard, List<RuleSet> layers) {
        this.mode = mode;
        this.hard = List.copyOf(hard);
        this.layers = List.copyOf(layers);
    }

    public Mode mode() {
        return mode;
    }

    public Verdict judge(Act act, Grants grants) {
        if (mode == Mode.OBSERVE) {
            return Verdict.deny("observe mode: " + act.verb().word() + " would change the world");
        }
        Rule forbidden = RuleSet.firstMatch(hard, act);
        if (forbidden != null) {
            return Verdict.deny("denied by hard rule " + forbidden + " (" + forbidden.describe(act) + ")", forbidden,
                    Verdict.HARD);
        }
        if (mode == Mode.BYPASS) {
            return Verdict.allow();
        }
        for (int i = 0; i < layers.size(); i++) {
            Rule denied = RuleSet.firstMatch(layers.get(i).deny(), act);
            if (denied != null) {
                return Verdict.deny("denied by rule " + denied + " (" + denied.describe(act) + ")", denied, i);
            }
        }
        Rule hit = null;
        int hitLayer = Verdict.NO_LAYER;
        for (int i = 0; i < layers.size() && hit == null; i++) {
            Rule allowed = RuleSet.firstMatch(layers.get(i).allow(), act);
            if (allowed != null) {
                return Verdict.allow(allowed, i);
            }
            hit = RuleSet.firstMatch(layers.get(i).ask(), act);
            hitLayer = i;
        }
        if (grants.covers(hit)) {
            return Verdict.allow();
        }
        return hit != null ? Verdict.ask(hit, hit.describe(act), hitLayer) : Verdict.uncovered();
    }
}
