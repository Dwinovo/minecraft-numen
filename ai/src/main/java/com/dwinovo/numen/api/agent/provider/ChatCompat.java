package com.dwinovo.numen.api.agent.provider;

import com.dwinovo.numen.api.agent.catalog.Api;

/**
 * Chat Completions 的兼容项。
 *
 * @param thinkingFormat         思考参数怎么写
 * @param maxTokensField         输出上限用哪个字段名
 * @param replayReasoningContent 回传助手消息时必须带 {@code reasoning_content}(带工具调用的回合缺了就补)
 * @param streamUsage            流式请求带不带 {@code stream_options.include_usage}(不带就拿不到用量)
 * @param anthropicCacheControl  请求里显式放 {@code cache_control} 断点:网关转发给 Anthropic 型号时它不会自动缓存
 *                               (其余厂家按前缀自动缓存,不需要)
 */
public record ChatCompat(ThinkingFormat thinkingFormat, MaxTokensField maxTokensField,
                         boolean replayReasoningContent, boolean streamUsage,
                         boolean anthropicCacheControl) implements Compat {

    @Override
    public Api api() {
        return Api.CHAT;
    }

    /** 思考参数的写法。是否真发、发什么档位,由型号的 {@code ThinkingTable} 定。 */
    public enum ThinkingFormat {
        /** 顶层 {@code reasoning_effort}。 */
        OPENAI("openai"),
        /** 嵌套 {@code reasoning: {effort}}。 */
        OPENROUTER("openrouter"),
        /** {@code thinking: {type: enabled|disabled}},开着时再带 {@code reasoning_effort}。 */
        DEEPSEEK("deepseek"),
        /** 只有 {@code thinking: {type: enabled|disabled}}。 */
        ZAI("zai"),
        /** 布尔开关 {@code enable_thinking}。 */
        QWEN("qwen"),
        /** 什么都不发。 */
        NONE("none");

        private final String id;

        ThinkingFormat(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        static ThinkingFormat byId(String id) {
            for (ThinkingFormat f : values()) {
                if (f.id.equals(id)) return f;
            }
            throw new IllegalArgumentException("unknown thinking_format '" + id + "'");
        }
    }

    /** 输出上限的字段名。 */
    public enum MaxTokensField {
        MAX_TOKENS("max_tokens"),
        MAX_COMPLETION_TOKENS("max_completion_tokens");

        private final String wire;

        MaxTokensField(String wire) {
            this.wire = wire;
        }

        /** 请求体里的字段名,同时是存档里的写法。 */
        public String wire() {
            return wire;
        }

        static MaxTokensField byWire(String wire) {
            for (MaxTokensField f : values()) {
                if (f.wire.equals(wire)) return f;
            }
            throw new IllegalArgumentException("unknown max_tokens_field '" + wire + "'");
        }
    }
}
