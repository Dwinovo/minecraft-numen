package com.dwinovo.numen.api;

import com.dwinovo.numen.api.describe.Describer;
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

    /**
     * 这是什么:她看一样东西时,每个回答者说它认得的那部分(告示牌上的字、里面存着什么、你的模组的机器在干什么、能对它用哪些函数),
     * 拼进这次"看"的结果。见 {@link Describer}。
     */
    public static final Question<Describer> DESCRIBE = Question.of("describe");
}
