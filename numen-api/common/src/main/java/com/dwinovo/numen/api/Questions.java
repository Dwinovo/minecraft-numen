package com.dwinovo.numen.api;

import com.dwinovo.numen.api.witness.Witness;

/**
 * Numen API 自己出的题,都列在这里。回答者经 {@link NumenApi#answer} 登记;原版的回答由 Numen 从同一扇门登记,和插件一个写法。
 */
public final class Questions {

    private Questions() {}

    /**
     * 一次动作前后变了什么:每个回答者只看一样东西(背包、血量、瞄的那一格、你的模组给物品存的状态……),动作之前记下、动作之后说出
     * 变化,拼进这次动作的回执。见 {@link Witness}。
     */
    public static final Question<Witness> WITNESS = Question.of("witness");
}
