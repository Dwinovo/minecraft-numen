package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.Verdict;

/**
 * 规则命中记账的口:真动手前的那一问({@link Gate#judgeLive})定下答复的那一行规则,在哪一层、哪张表,记一次。
 * 账本是主人的存档({@link PermissionStore#hit}),规则列表上显示。
 */
@FunctionalInterface
public interface Hits {

    /** 不记账:测试与规划用。 */
    Hits NONE = (layer, table, rule) -> { };

    /**
     * @param layer {@link Layers#OWNER}、{@link Layers#FACTORY} 或 {@link Verdict#HARD}
     * @param table 这行规则在哪张表,也就是它给出的答复
     */
    void record(int layer, Verdict.Kind table, Rule rule);
}
