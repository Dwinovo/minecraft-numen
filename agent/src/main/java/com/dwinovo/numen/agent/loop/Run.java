package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.http.CancelToken;

/**
 * 内核手上正在做的一件事。{@code run == null} 就是闲;异步回调带着 {@link #id} 回来,不是当前这件就丢。
 */
final class Run {

    /** 递增编号。 */
    final long id;
    /**
     * 闲时执行的控制条目(主人按的整理记忆),不是一次对话:不发 {@link LoopEvent.RunStarted}/{@link LoopEvent.RunEnded},
     * 被切断时不写切断点,不重试。
     */
    final boolean control;
    /** 传给这件事发出的每一次模型调用;切断时取消。 */
    final CancelToken cancel = new CancelToken();
    Phase phase;
    /**
     * 主人的话已经注入、还没等到模型回话。下一次调模型时语音据此选硬停上一轮还是句界衔接;
     * 重试接着上一次的值——失败的那次同样没回上主人的话。
     */
    boolean ownerSpoke;
    /** 这条链已经为一次失败重试过。模型回了话就清掉:之后再失败,又有一次重试。 */
    boolean retried;

    Run(long id, boolean control, boolean retried, boolean ownerSpoke) {
        this.id = id;
        this.control = control;
        this.retried = retried;
        this.ownerSpoke = ownerSpoke;
    }
}
