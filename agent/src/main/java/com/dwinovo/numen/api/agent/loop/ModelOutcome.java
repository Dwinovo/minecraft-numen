package com.dwinovo.numen.api.agent.loop;

import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.api.agent.provider.Usage;

/** 一次模型调用的结果。失败不抛异常,写成带原因的值交回来,要不要重试由内核决定。 */
public sealed interface ModelOutcome {

    /** 模型回话了,附上服务端报的用量。 */
    record Answered(AssistantTurn turn, Usage usage) implements ModelOutcome {}

    /** 调用失败;{@code cause} 是结构化的原因,话由界面按当前语言说。 */
    record Failed(Cause cause) implements ModelOutcome {}
}
