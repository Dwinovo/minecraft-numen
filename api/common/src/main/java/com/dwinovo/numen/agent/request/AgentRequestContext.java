package com.dwinovo.numen.agent.request;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.loop.ModelRequest;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds ephemeral runtime state to one model request without persisting it in conversation history, and assembles
 * the request of one turn. The owner's client and the bench both build their requests here.
 */
public final class AgentRequestContext {

    private AgentRequestContext() {}

    /**
     * <b>发给模型的就是这一份</b>——会话上下文加上这一轮临时挂载的运行期状态
     * ({@code <runtime_state>}/{@code <current_task>})。源会话与落盘日志一个字不动。
     *
     * <p>工具表是全份:装在模组里的、联动插件带的、接进来的 MCP,一并发出去。
     * 分批披露那套已经退役——她得先搜一次才能用的工具,省下的那点前缀是缓存本来就
     * 不收钱的部分,换来的却是每次压缩之后重搜一遍。
     *
     * @param history     会话历史的快照
     * @param runtimeXml  这一刻的运行期状态({@link RuntimeState#xml})
     * @param personaText 人设正文;没有为 {@code null}(见 {@link SystemPromptComposer#compose})
     * @param modules     她能用的模块(见 {@link SystemPromptComposer#compose})
     */
    public static ModelRequest turn(List<ConvoState.Msg> history, String runtimeXml, String personaText,
                                    com.dwinovo.numen.script.Modules modules) {
        List<NumenTool> tools = ToolRegistry.all();
        return new ModelRequest(attach(history, runtimeXml), tools, SystemPromptComposer.compose(personaText, modules));
    }

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
