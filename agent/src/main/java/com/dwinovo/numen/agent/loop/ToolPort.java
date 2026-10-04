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
     * 这一批还没结算时,这条输入是不是在跑的程序等着的那件身体活的收尾:是就归程序(内核不把它放进队列,随后经 {@link #arrived}
     * 交来),它的账写进程序的回执,只说这一次。
     */
    boolean awaits(EventQueue.Entry entry);

    /**
     * 这一批还没结算时,一条输入到了(内核在入队之后逐条转来;程序等着的收尾不入队,先于同一批的别的条目转来)。{@code urgent} 是队列的急件规则算出来的:它要不要
     * 立刻叫醒她。这一批正等着某件身体任务收尾时,它是那件的收尾就接着派下一个,它是急件就不再等。
     */
    void arrived(EventQueue.Entry entry, boolean urgent);

    /**
     * 放弃这批里还没结果的调用,只按它们自己的调用 id 收拾——外接模型挂着的调用不是这批的,不动。在跑的脚本不放弃:
     * 它在返回之前经 {@link Sink#finished} 交出停在哪一行的回执,所以内核在作废这次 run 之前调它。
     *
     * @param stopBody 要不要连身体一起叫停(由切断原因决定,见 {@link HaltReason#stopsBody})
     * @return 被放弃的调用 id
     */
    List<String> cancel(boolean stopBody);

    /** 结果回报口。回调在内核的线程上。 */
    interface Sink {
        void started(LlmToolCall call);

        void finished(LlmToolCall call, String resultJson);

        void settled();
    }
}
