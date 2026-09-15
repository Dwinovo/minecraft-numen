package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.provider.LlmToolCall;

import java.util.List;
import java.util.Set;

/**
 * 工具那一侧:串行执行一批调用,结果逐条报回。身体只有一个动作槽,所以不并行。
 */
public interface ToolPort {

    /**
     * 执行模型这次回复里的调用,一个结算了才派下一个。
     *
     * @param callable 发出这批调用的那次请求里模型看得见定义的工具(见 {@link ModelRequest#callable})
     * @param sink     每个调用派出、结算时各报一次;全部结算后 {@link Sink#settled} 一次
     */
    void run(List<LlmToolCall> calls, Set<String> callable, Sink sink);

    /**
     * 放弃这批里还没结果的调用,只按它们自己的调用 id 收拾——外接模型挂着的调用不是这批的,不动。
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
