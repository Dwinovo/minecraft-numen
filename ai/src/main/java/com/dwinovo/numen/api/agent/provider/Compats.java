package com.dwinovo.numen.api.agent.provider;

import com.dwinovo.numen.api.agent.catalog.Api;
import com.dwinovo.numen.api.agent.catalog.ThinkingTable;
import com.dwinovo.numen.api.agent.provider.ChatCompat.MaxTokensField;
import com.dwinovo.numen.api.agent.provider.ChatCompat.ThinkingFormat;
import com.google.gson.JsonObject;

import java.net.URI;
import java.util.Locale;

/**
 * 认出一个端点的兼容项:<b>全仓唯一按厂家 id 和地址分支的地方</b>。
 * 先按厂家 id 与接口主机认出缺省值,再让档案里玩家写的覆盖项盖上去——自定义厂家
 * 认不出时,缺省就是最普通的 OpenAI 兼容写法,玩家按需改。
 *
 * <p>覆盖项是档案里的一个 JSON 对象,键(都可省):
 * <ul>
 *   <li>chat:{@code thinking_format}、{@code max_tokens_field}、{@code replay_reasoning_content}、{@code stream_usage}、
 *       {@code anthropic_cache_control}</li>
 *   <li>responses:{@code developer_role}</li>
 *   <li>anthropic:{@code adaptive_thinking}、{@code temperature}</li>
 * </ul>
 */
public final class Compats {

    private Compats() {}

    /**
     * @param api       协议
     * @param vendorId  目录里的厂家 id(自定义厂家是 {@code custom})
     * @param modelId   型号 id(网关上的 Anthropic 型号要显式放缓存断点)
     * @param baseUrl   实际使用的接口地址
     * @param thinking  型号的思考档位表(自适应思考的缺省取决于型号)
     * @param overrides 档案里的覆盖项,可为 null
     */
    public static Compat resolve(Api api, String vendorId, String modelId, String baseUrl, ThinkingTable thinking,
                                 JsonObject overrides) {
        String id = vendorId == null ? "" : vendorId;
        String host = hostOf(baseUrl);
        JsonObject o = overrides == null ? new JsonObject() : overrides;
        return switch (api) {
            case CHAT -> chat(id, modelId == null ? "" : modelId, host, o);
            case RESPONSES -> new ResponsesCompat(flag(o, "developer_role", true));
            case ANTHROPIC -> new AnthropicCompat(
                    flag(o, "adaptive_thinking", thinking.effortOnly()),
                    flag(o, "temperature", true));
        };
    }

    private static ChatCompat chat(String id, String modelId, String host, JsonObject o) {
        boolean deepseek = id.equals("deepseek") || host.endsWith("deepseek.com");
        // 这几家(智谱/Z.ai、Kimi、火山方舟)的思考开关同形:thinking.type
        boolean typeSwitch = id.startsWith("zai") || id.startsWith("zhipuai")
                || id.startsWith("moonshotai") || id.startsWith("volcengine")
                || host.contains("api.z.ai") || host.contains("bigmodel.cn")
                || host.contains("moonshot.") || host.contains("volces.com");
        boolean moonshot = id.startsWith("moonshotai") || host.contains("moonshot.");
        boolean openRouter = id.equals("openrouter") || host.contains("openrouter.ai");
        boolean qwen = id.startsWith("alibaba") || id.startsWith("siliconflow")
                || host.contains("dashscope") || host.contains("siliconflow");
        boolean openAiOfficial = host.equals("api.openai.com");

        ThinkingFormat format = deepseek ? ThinkingFormat.DEEPSEEK
                : typeSwitch ? ThinkingFormat.ZAI
                : openRouter ? ThinkingFormat.OPENROUTER
                : qwen ? ThinkingFormat.QWEN
                : ThinkingFormat.OPENAI;
        // 官方接口的推理型号只认 max_completion_tokens;其余兼容端点普遍还是 max_tokens
        MaxTokensField maxTokens = openAiOfficial ? MaxTokensField.MAX_COMPLETION_TOKENS : MaxTokensField.MAX_TOKENS;
        // DeepSeek、Kimi 的思考型号要求带工具调用的助手消息回传 reasoning_content
        boolean replay = deepseek || moonshot;

        // OpenRouter 转发给 Anthropic 型号时不自动缓存,要像本家一样显式放断点
        boolean anthropicCache = openRouter && modelId.startsWith("anthropic/");

        return new ChatCompat(
                o.has("thinking_format") ? ThinkingFormat.byId(o.get("thinking_format").getAsString()) : format,
                o.has("max_tokens_field") ? MaxTokensField.byWire(o.get("max_tokens_field").getAsString()) : maxTokens,
                flag(o, "replay_reasoning_content", replay),
                flag(o, "stream_usage", true),
                flag(o, "anthropic_cache_control", anthropicCache));
    }

    private static boolean flag(JsonObject o, String key, boolean fallback) {
        return o.has(key) ? o.get(key).getAsBoolean() : fallback;
    }

    private static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return "";
        try {
            String host = URI.create(baseUrl.strip()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
