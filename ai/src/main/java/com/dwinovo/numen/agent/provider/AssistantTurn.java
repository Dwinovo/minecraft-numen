package com.dwinovo.numen.agent.provider;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * One assistant-turn response from an LLM, decomposed into the standard
 * fields plus a provider-specific extras bag.
 *
 * <h2>Why an extras map</h2>
 * Backends extend OpenAI's spec with proprietary fields that **must be
 * echoed back** on the next request (DeepSeek's {@code reasoning_content},
 * potentially others as model families evolve). Stripping these on
 * serialisation breaks multi-turn chats. The extras bag captures whatever
 * non-standard top-level keys the parser found, and the provider's
 * {@code assistantToRequestMessage} re-injects them when constructing the
 * next request.
 *
 * <p>This is the mechanism that fixes the {@code 400 The reasoning_content
 * in the thinking mode must be passed back to the API} case.
 *
 * <h2>reasoning 与 extras 是两条独立命脉</h2>
 * {@link #reasoning} 是方言字段(reasoning_content/reasoning/reasoning_text/thinking)解码后的
 * 思考文本:面板拿它画思考块;Anthropic 回传思考块时连同 extras 里的签名一起要它原文。
 * {@link #extras} 是别家要求原样回注的字段。两样都随会话日志落盘,重启后与这一局一字不差。
 *
 * @param content      assistant message body text (may be empty when the
 *                     turn is pure tool calls)
 * @param toolCalls    tool calls in this turn (empty list = no tool use, the
 *                     model finalised with a text reply)
 * @param extras       provider-specific non-standard fields to round-trip
 *                     back. Lives at the message level (sibling to {@code role},
 *                     {@code content}, {@code tool_calls} in OpenAI's schema).
 * @param reasoning    dialect-decoded thinking text for display (empty when
 *                     the model didn't think or the backend doesn't expose it)
 * @param origin       哪家模型产的这条回合({@code 服务商/模型}),{@code null} = 不知道(老存档)。
 *                     {@link #extras} 与 {@link #reasoning} 是那一家的私货,换了模型就不能原样回塞:
 *                     DeepSeek 的 {@code reasoning_content} 递给别家会被 400 拒,Anthropic 的思考
 *                     签名更是只有它自己认。所以回合要记得自己的出处,见 {@link #sameOrigin}。
 */
public record AssistantTurn(String content,
                            List<LlmToolCall> toolCalls,
                            JsonObject extras,
                            String reasoning,
                            String origin) {

    /** 不知道出处的回合(老存档、测试、以及还没盖戳的那一刻)。 */
    public AssistantTurn(String content, List<LlmToolCall> toolCalls, JsonObject extras, String reasoning) {
        this(content, toolCalls, extras, reasoning, null);
    }

    /** 盖上出处的戳。 */
    public AssistantTurn withOrigin(String origin) {
        return new AssistantTurn(content, toolCalls, extras, reasoning, origin);
    }

    /** 出处对得上吗——对不上就别把上一家的私货递给这一家。老存档没戳,当对不上处理。 */
    public boolean sameOrigin(String current) {
        return origin != null && origin.equals(current);
    }

    /**
     * 脱掉私货:内容与工具调用照旧,{@link #extras} 与 {@link #reasoning} 留空。
     * 换了模型之后,上一家的思考与签名对这一家既没用也可能致命。
     */
    public AssistantTurn withoutProviderPrivateFields() {
        return new AssistantTurn(content, toolCalls, new JsonObject(), "", origin);
    }

    public AssistantTurn {
        if (content == null) content = "";
        if (toolCalls == null) toolCalls = List.of();
        if (extras == null) extras = new JsonObject();
        if (reasoning == null) reasoning = "";
    }

    /** 三参便捷构造:无思考文本(历史调用面与非思考后端)。 */
    public AssistantTurn(String content, List<LlmToolCall> toolCalls, JsonObject extras) {
        this(content, toolCalls, extras, "");
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public boolean hasReasoning() {
        return !reasoning.isEmpty();
    }
}
