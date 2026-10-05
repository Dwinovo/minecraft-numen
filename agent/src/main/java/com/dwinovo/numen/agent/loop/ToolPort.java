package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.provider.LlmToolCall;

import java.util.List;

/**
 * 工具那一侧:按顺序执行一批调用,结果逐条报回。身体只有一个动作槽,所以不并行;顺序与等待见 {@link SerialCalls}。
 */
public interface ToolPort {

    /**
     * 执行模型这次回复里的调用,一个做完了才派下一个。
     *
     * @param sink     每个调用派出、结算时各报一次;全部结算后 {@link Sink#settled} 一次
     */
    void run(List<LlmToolCall> calls, Sink sink);

    /**
     * 这一批还没结算时,一条输入到了(内核在入队之后逐条转来)。{@code urgent} 是队列的急件规则算出来的:它要不要立刻叫醒她。
     * 这一批里有一段程序在服务端跑着时,它是急件就让程序停在调用之间。
     */
    void arrived(EventQueue.Entry entry, boolean urgent);

    /**
     * 放弃这批里还没结果的调用,只按它们自己的调用 id 收拾——外接模型挂着的调用不是这批的,不动。在服务端跑着的程序先叫它当场停下,
     * 它的 id 也在放弃的里面。
     *
     * @param stopBody 要不要连身体一起叫停(由切断原因决定,见 {@link HaltReason#stopsBody})
     * @return 被放弃的调用 id
     */
    List<String> cancel(boolean stopBody);

    /** 结果回报口。回调在内核的线程上。 */
    interface Sink {
        void started(LlmToolCall call);

        void finished(LlmToolCall call, String resultJson);

        /**
         * 程序 {@code program} 怎么结束的、它每次 API 调用的结局(结构化的,不在回执的文字里;评测读它)。在 {@link #finished} 之前;
         * 不是程序的工具不调,不看就不管。
         */
        default void ended(LlmToolCall program, com.dwinovo.numen.agent.script.ScriptCall.Ending ending,
                           java.util.List<com.dwinovo.numen.agent.script.ScriptCall.Called> calls) {
        }

        void settled();

        /** 程序 {@code program} 里的一次 API 调用有了结局(服务端把每次调用的结局随回执送回来;评测按函数统计用);不看就不管。 */
        default void called(LlmToolCall program, com.dwinovo.numen.agent.script.ScriptCall.Called called) {
        }
    }
}
