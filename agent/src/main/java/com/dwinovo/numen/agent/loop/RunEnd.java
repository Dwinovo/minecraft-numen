package com.dwinovo.numen.agent.loop;

/** 一次 run 怎么结束的。 */
public sealed interface RunEnd {

    /** 模型说完了,也没有插话和接续要接着处理。 */
    RunEnd DONE = new Done();

    record Done() implements RunEnd {}

    /**
     * 模型调用失败。失败的回应不进历史;历史里只记一条切断点,说明这一轮没连上。
     *
     * @param words     给主人看的原因
     * @param retryable 调用本身出错(网络、超时、服务端错误)可以重试;回了空回复不重试
     */
    record Failed(String words, boolean retryable) implements RunEnd {}

    /** 被 {@link AgentLoop#halt} 切断。 */
    record Halted(HaltReason reason) implements RunEnd {}
}
