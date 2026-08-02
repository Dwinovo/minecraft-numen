package com.dwinovo.numen.agent.provider;

/**
 * DeepSeek 后端。
 *
 * <h2>行为要点</h2>
 * <ol>
 *   <li><b>基址:</b>默认 {@code https://api.deepseek.com/beta}——{@code /beta}
 *       前缀在标准 chat completions 之外解锁前缀补全家族的特性。</li>
 *   <li><b>消息处理全继承:</b>响应解析与消息重建不做任何覆写。非标响应
 *       字段(如 V4 思考模式的 {@code reasoning_content})由
 *       {@link OpenAIProvider#extractExtras} / {@link OpenAIProvider#captureChunkExtras}
 *       的未知字段全量捕获保留,不需要 DeepSeek 专有代码。</li>
 * </ol>
 *
 * <h2>有意不做</h2>
 * <ul>
 *   <li>不设 {@code reasoning_content} 缺失兜底(Moonshot 那样的注入):
 *       字段级全量保留已足够,该字段缺失时后端也接受。</li>
 *   <li>不做内容列表转字符串:agent 层始终以纯字符串发内容。</li>
 * </ul>
 *
 * <h2>可选的思考模式参数</h2>
 * DeepSeek 支持请求体 {@code thinking: {type: "enabled"}} 强制思考模式。
 * V4 模型默认即思考模式,常见场景该字段冗余,暂不设配置旋钮;有用户要
 * 强制指定时,加配置字段 + {@code buildRequestBody} 覆写即可。
 */
public final class DeepSeekProvider extends OpenAIProvider {

    public static final String NAME = "deepseek";
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com/beta";

    public DeepSeekProvider() {
        super(NAME, DEFAULT_BASE_URL, LlmProvider.THINKING_TYPE);
    }

    @Override public String name() { return NAME; }

    @Override public String defaultBaseUrl() { return DEFAULT_BASE_URL; }

    /** {@code usage.prompt_cache_miss_tokens} 直接就是缓存未命中的输入,加输出即新处理量。 */
    @Override
    public long freshTokens(com.google.gson.JsonObject usage) {
        if (usage != null && usage.has("prompt_cache_miss_tokens")) {
            return LlmProvider.usageInt(usage, "prompt_cache_miss_tokens")
                    + LlmProvider.usageInt(usage, "completion_tokens");
        }
        return super.freshTokens(usage);
    }
}
