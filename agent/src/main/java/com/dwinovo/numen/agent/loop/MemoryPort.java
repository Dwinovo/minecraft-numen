package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.Usage;

/**
 * 上下文整理:压缩与清空。内核只管什么时候做(自动压缩在注入插话之前、控制条目在闲时),
 * 做什么——阈值、切分、压缩提示词、摘要怎么落地——都在这一侧。
 */
public interface MemoryPort {

    /** 自动压缩到点了没有:上一次请求的体量逼近上下文窗口、历史够长、没有被连续失败熔断。 */
    boolean compactionDue();

    /** 切好这一次压缩。{@code auto} = 内核自己发起的,否则是主人按的。 */
    Compaction compaction(boolean auto);

    /** 清空上下文:她带进下一轮的历史清成白纸,记录留档。 */
    void clear();

    /** 切好的一次压缩:要发的请求,和摘要回来之后怎么落地。 */
    interface Compaction {

        ModelRequest request();

        /** 摘要回来了,换掉历史;摘要是空的就返回 {@code false},历史不动。 */
        boolean apply(AssistantTurn reply, Usage usage);

        /** 这次没压成(调用失败或摘要为空)。 */
        void failed(String why);
    }
}
