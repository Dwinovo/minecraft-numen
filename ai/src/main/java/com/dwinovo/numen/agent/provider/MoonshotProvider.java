package com.dwinovo.numen.agent.provider;

import com.google.gson.JsonObject;

/**
 * Moonshot(Kimi)后端。
 *
 * <h2>行为要点</h2>
 * <ol>
 *   <li><b>基址:</b>默认 {@code https://api.moonshot.ai/v1}。</li>
 *   <li><b>{@code reasoning_content} 兜底:</b>Moonshot 推理模型
 *       (kimi-thinking-preview 与 kimi-k2.5 家族)拒绝带 {@code tool_calls}
 *       却缺 {@code reasoning_content} 字段的 assistant 消息;缺失时注入
 *       单个空格(API 接受的最小值),见 {@link #assistantToRequestMessage}。</li>
 * </ol>
 *
 * <h2>有意不做</h2>
 * <ul>
 *   <li>内容列表转字符串:agent 层始终以纯字符串发内容,无需转换。</li>
 *   <li>废弃的 {@code functions} 参数:根本不暴露(一律 {@code tools})。</li>
 *   <li>kimi-thinking-preview 不能调工具,配置它 + 注册了工具会静默失效;
 *       二期再做 init 时告警。</li>
 * </ul>
 *
 * <p>非标响应字段(推理模型的 {@code reasoning_content})由
 * {@link OpenAIProvider} 的 extras 全量捕获自动保留往返,本类无需覆写。
 */
public final class MoonshotProvider extends OpenAIProvider {

    public static final String NAME = "moonshot";
    public static final String DEFAULT_BASE_URL = "https://api.moonshot.ai/v1";

    /** 思考型号(kimi-*-thinking)常开、无线上开关——方言定 none,力度不发参数。 */
    public MoonshotProvider() {
        super(NAME, DEFAULT_BASE_URL, LlmProvider.THINKING_NONE);
    }

    @Override public String name() { return NAME; }

    @Override public String defaultBaseUrl() { return DEFAULT_BASE_URL; }

    /**
     * {@code reasoning_content} 兜底:Moonshot 推理模型要求每条含
     * {@code tool_calls} 的 assistant 消息都带该字段。父类的
     * {@code assistantToRequestMessage} 已回显捕获到的 extras;本覆写只在
     * 字段缺失时补(例如上一轮的分块流里没有携带它)。
     */
    @Override
    public JsonObject assistantToRequestMessage(AssistantTurn turn) {
        JsonObject m = super.assistantToRequestMessage(turn);
        if (m.has("tool_calls") && !m.has("reasoning_content")) {
            m.addProperty("reasoning_content", " ");
        }
        return m;
    }
}
