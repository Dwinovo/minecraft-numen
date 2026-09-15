package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.IToolSpec;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 发给模型的一次请求。
 *
 * @param messages     历史如实记录的样子(协议配对由 {@code ProtocolView} 在发出前统一做)
 * @param tools        随请求发出完整定义的工具;空 = 这次不带工具(压缩、目标评估)
 * @param systemPrompt 系统提示
 * @param callable     这次请求里模型看得见定义的工具名——常驻的,加上 {@code messages} 里还留着展开块的。
 *                     与 {@code messages} 出自同一份快照:派发这次回复里的调用时照它放行,不另算一份
 */
public record ModelRequest(List<ConvoState.Msg> messages,
                           Collection<? extends IToolSpec> tools,
                           String systemPrompt,
                           Set<String> callable) {}
