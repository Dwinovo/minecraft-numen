package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 历史发给模型之前的样子——全仓唯一做协议配对的地方。
 *
 * <p>{@link ConvoState} 如实记录发生了什么:模型的回复、工具结果、某一轮在哪里被
 * {@link ConvoState.Msg.Halt} 切断。打断、死亡、读盘、失败收尾都不在当时修补历史;服务商要的
 * 合法序列只在这里、在 {@link NumenLlmClient#chatStreaming} 转线格式之前现算一次——正常一轮、
 * 压缩、目标评估都经过那个出口,所以谁也绕不过去。
 *
 * <p>三条规则:
 * <ol>
 *   <li><b>悬空的工具调用补结果。</b>assistant 的 tool_calls 到下一条 assistant、user、Halt 或结尾
 *       为止没等到结果的,补一条 {@code {"success":false,"message":"<原因>"}}(上游见到没有结果的
 *       调用会直接 400)。原因取紧随其后的那条 Halt;没有 Halt 说明游戏在结果回来前被直接关掉了,
 *       用 {@link #CLOSED_BEFORE_RESULT}。</li>
 *   <li><b>Halt 本身不发。</b>它切断的若是一次模型回复(那一刻没有悬空调用),在下一条 user 消息
 *       开头加一行"(上一轮被打断:原因)"——模型该知道上一次没说完不是它自己停的。下一条 user
 *       之前模型又回过话,那次切断就已经翻篇,不再提。</li>
 *   <li><b>相邻 user 合并。</b>失败后又来一句话、运行期状态块、切断说明,都可能造出连续的 user,
 *       有的服务商直接拒;合成一条,中间空一行。</li>
 * </ol>
 *
 * <p>纯函数:不改入参,也不碰落盘。
 */
public final class ProtocolView {

    /** 悬空调用后面没有 Halt 可引时的原因:游戏在结果回来之前被关掉了。 */
    public static final String CLOSED_BEFORE_RESULT = "游戏关闭前没有返回结果";

    private ProtocolView() {}

    /** 把历史变成可以发给服务商的序列。输出里没有 {@link ConvoState.Msg.Halt}。 */
    public static List<ConvoState.Msg> forWire(List<ConvoState.Msg> history) {
        List<ConvoState.Msg> out = new ArrayList<>(history.size() + 1);
        // 最近一条 assistant 里还没等到结果的调用,按模型给出的顺序
        List<LlmToolCall> open = new ArrayList<>();
        // 被切断的模型回复,等着写进下一条 user 的开头
        List<String> cutReplies = new ArrayList<>();
        for (ConvoState.Msg msg : history) {
            switch (msg) {
                case ConvoState.Msg.Assistant a -> {
                    answer(out, open, CLOSED_BEFORE_RESULT);
                    cutReplies.clear();
                    out.add(a);
                    open.addAll(a.turn().toolCalls());
                }
                case ConvoState.Msg.Tool t -> {
                    open.removeIf(call -> call.id().equals(t.toolCallId()));
                    out.add(t);
                }
                case ConvoState.Msg.Halt h -> {
                    if (open.isEmpty()) {
                        cutReplies.add(h.reason());
                    } else {
                        answer(out, open, h.reason());
                    }
                }
                case ConvoState.Msg.User u -> {
                    answer(out, open, CLOSED_BEFORE_RESULT);
                    StringBuilder content = new StringBuilder();
                    for (String reason : cutReplies) {
                        content.append("（上一轮被打断：").append(reason).append("）\n");
                    }
                    cutReplies.clear();
                    appendUser(out, content.append(u.content()).toString());
                }
            }
        }
        answer(out, open, CLOSED_BEFORE_RESULT);
        return List.copyOf(out);
    }

    /** 给还没结果的调用各补一条失败结果,补完清空。 */
    private static void answer(List<ConvoState.Msg> out, List<LlmToolCall> open, String reason) {
        for (LlmToolCall call : open) {
            JsonObject result = new JsonObject();
            result.addProperty("success", false);
            result.addProperty("message", reason);
            out.add(new ConvoState.Msg.Tool(call.id(), result.toString()));
        }
        open.clear();
    }

    private static void appendUser(List<ConvoState.Msg> out, String content) {
        int last = out.size() - 1;
        if (last >= 0 && out.get(last) instanceof ConvoState.Msg.User prev) {
            out.set(last, new ConvoState.Msg.User(prev.content() + "\n\n" + content));
        } else {
            out.add(new ConvoState.Msg.User(content));
        }
    }
}
