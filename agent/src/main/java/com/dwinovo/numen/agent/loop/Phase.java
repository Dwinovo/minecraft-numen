package com.dwinovo.numen.agent.loop;

/** 内核此刻在做的那件事。闲着的时候没有阶段({@link LoopStatus#phase} 为 {@code null})。 */
public enum Phase {
    /** 请求已发出,等模型回话(流式中)。 */
    MODEL,
    /** 模型派的这批工具调用还没全部结算。 */
    TOOLS,
    /**
     * 在整理记忆。自动压缩是一次 run 里调模型之前的一步;主人按的 {@code /compact} 在闲时执行、不开 run,
     * 期间同样占着内核,挡住开 run。
     */
    COMPACT
}
