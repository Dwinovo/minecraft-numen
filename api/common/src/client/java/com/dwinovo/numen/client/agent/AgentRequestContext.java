package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.llm.ConvoState;

import java.util.ArrayList;
import java.util.List;

/** Adds ephemeral runtime state to one model request without persisting it in conversation history. */
final class AgentRequestContext {

    private AgentRequestContext() {}

    /**
     * 把运行期状态挂进这一次请求:<b>恒作为一条 role=user 的消息追加在末尾</b>。源列表与其中的
     * 消息一个字不动。
     *
     * <h2>为什么必须是 user</h2>
     * "这一轮发出去的 user 消息"得是一个<b>完整</b>的答案。把它挂到工具结果上,
     * 这句话就有了例外——而例外只能靠"再去别处看一眼"补,面板、日志、排查的人
     * 各补各的。顺带工具结果也就不再是服务端原样交回的那串,读日志时会以为
     * 工具自己吐了个 {@code <runtime_state>}。
     *
     * <h2>为什么不看尾巴长什么样</h2>
     * 尾巴是 user 时跟它挨着、尾巴是还没等到结果的 tool_calls 时插在中间,这两种都不合协议,
     * 但都不在这里处理:转成服务商格式之前的唯一出口
     * {@link com.dwinovo.numen.agent.llm.ProtocolView#forWire} 会合并相邻的 user、给悬空调用补结果。
     * 这里再判一遍就是第二份配对规则。
     */
    static List<ConvoState.Msg> attach(List<ConvoState.Msg> messages, String runtimeXml) {
        if (runtimeXml == null || runtimeXml.isBlank()) return List.copyOf(messages);
        List<ConvoState.Msg> out = new ArrayList<>(messages);
        out.add(new ConvoState.Msg.User(runtimeXml));
        return List.copyOf(out);
    }
}
