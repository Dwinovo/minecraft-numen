package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.provider.Usage;

/**
 * 内核对外说的全部的话。内核不碰界面、不碰记账:打字机、语音、气泡、聊天行、提示条、token 台账、
 * 工作站坐标,都是订阅这些事件的人自己做。
 */
public sealed interface LoopEvent {

    /** 开了一次 run。 */
    record RunStarted(long runId) implements LoopEvent {}

    /**
     * 这次 run 里要调一次模型了(注入已经做完)。
     *
     * @param ownerSpoke 主人的话还没被回应——语音硬停上一轮;否则句界衔接
     */
    record TurnStarted(long runId, boolean ownerSpoke) implements LoopEvent {}

    /** 流式回复的一小段(正文 / 思考,已解码)。只在对话调用里发,整理记忆不发。 */
    record ModelDelta(long runId, String content, String reasoning) implements LoopEvent {}

    /** 模型的一条回复进了历史。有工具调用的话,接下来就派工具。 */
    record AssistantMessage(long runId, AssistantTurn turn) implements LoopEvent {}

    record ToolStarted(long runId, LlmToolCall call) implements LoopEvent {}

    /** 一个工具调用结算了,结果已进历史。 */
    record ToolFinished(long runId, LlmToolCall call, String resultJson) implements LoopEvent {}

    record RunEnded(long runId, RunEnd end) implements LoopEvent {}

    /** 调用失败而且不再重试——该让主人看见了。 */
    record TurnFailed(String words) implements LoopEvent {}

    /**
     * 执行了一次 {@link AgentLoop#halt}。不管当时有没有 run 都发:停止键在闲时同样要让语音闭嘴、
     * 让目标收工。
     */
    record Halted(HaltReason reason) implements LoopEvent {}

    /**
     * 停牌变了。
     *
     * @param hold   新的停牌;{@code null} = 解开了
     * @param reason 进入这个停牌时给主人看的原因(端点不可用时是那句话);其余为 {@code null}
     */
    record HoldChanged(Hold hold, String reason) implements LoopEvent {}

    /** 一次模型调用报回了用量。 */
    record ModelUsed(Usage usage, Purpose purpose) implements LoopEvent {}

    /** 历史出现了一条边界:压缩换掉了旧历史、清空、或者某一轮在这里被切断。 */
    record TranscriptBoundary(Boundary kind) implements LoopEvent {}

    /** 用量是替什么花的。 */
    enum Purpose {
        /** 一次 run 里的对话调用。 */
        TURN,
        /** 整理记忆。 */
        COMPACT,
        /** 长期目标的评估:不是 run,经 {@link AgentLoop#consult} 发出。 */
        GOAL
    }

    enum Boundary {
        COMPACT,
        CLEAR,
        HALT
    }
}
