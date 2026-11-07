package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.Policy;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.RuleSet;
import com.dwinovo.numen.permission.Verdict;

import java.util.List;

/**
 * 规则分几层、各叫什么:硬规则,和从高到低的两层——主人自己写的、出厂的。{@link Policy} 只知道层的次序,层的名字
 * (规则列表上的标题、命中计数的账目)在这里定,次序也只在这里排。
 */
public final class Layers {

    /** 主人自己写的那一层:先查。 */
    public static final int OWNER = 0;
    /** 随模组发的那一层:后查。 */
    public static final int FACTORY = 1;

    private Layers() {}

    /** 按这个次序排好的裁决规则。 */
    public static Policy policy(Mode mode, List<Rule> hard, RuleSet owner, RuleSet factory) {
        return new Policy(mode, hard, List.of(owner, factory));
    }

    /** {@link Verdict#layer} 对应的名字:{@code hard}、{@code owner}、{@code factory}。 */
    public static String name(int layer) {
        return switch (layer) {
            case Verdict.HARD -> "hard";
            case OWNER -> "owner";
            case FACTORY -> "factory";
            default -> throw new IllegalArgumentException("no such layer: " + layer);
        };
    }
}
