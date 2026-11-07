package com.dwinovo.numen.api.agent.loop;

import java.util.List;
import java.util.Optional;

/**
 * 上下文整理:压缩与清空。内核只管什么时候做(自动压缩在注入插话之前、控制条目在闲时),
 * 做什么——阈值、切分、压缩提示词、摘要怎么落地——都在这一侧。
 */
public interface MemoryPort {

    /** 自动压缩到点了没有:上一次请求的体量逼近上下文窗口、历史够长、没有被连续失败熔断、压得出东西(见 {@link #compaction})。 */
    boolean compactionDue();

    /**
     * 切好这一次压缩。{@code auto} = 内核自己发起的,否则是主人按的。{@code focus} 是主人说摘要要特别留意的(没有是空串)。
     * 没有新消息可总结时为空:不压,也不发请求。
     */
    Optional<Compaction> compaction(boolean auto, String focus);

    /** 清空上下文:她带进下一轮的历史清成白纸,记录留档。 */
    void clear();

    /** 切好的一次压缩:要发的请求,和摘要回来之后怎么落地。 */
    interface Compaction {

        /**
         * 要发的摘要请求,按顺序一份份发(一份答完再发下一份):较早的历史一份,一轮被切开时那一轮的前缀再一份。
         * 请求里不带工具。
         */
        List<ModelRequest> requests();

        /** 请求都答完了(答复与 {@link #requests} 一一对应),换掉历史;任何一份摘要是空的就返回 {@code false},历史不动。 */
        boolean apply(List<ModelOutcome.Answered> answers);

        /** 这次没压成(调用失败或摘要为空)。 */
        void failed(String why);
    }
}
