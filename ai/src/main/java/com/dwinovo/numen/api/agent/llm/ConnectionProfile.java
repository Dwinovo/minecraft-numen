package com.dwinovo.numen.api.agent.llm;

import com.google.gson.JsonObject;

/**
 * 玩家为一个模型连接填的全部东西——档案的内容。能力(上下文、思考档位、协议、缺省地址)
 * 一律不在这里,由 {@link EndpointResolver} 到目录里查;这里只有玩家主动填的值,
 * 空 / 0 / null 都是"没填"。
 *
 * @param provider        厂家 id(models.dev 的 id),或 {@value #CUSTOM}(自定义厂家)
 * @param protocol        协议({@code chat}/{@code responses}/{@code anthropic});空 = 随目录
 * @param model           型号 id
 * @param apiKey          密钥(原样存,用时由 {@link LlmEndpoint} 去首尾空白)
 * @param baseUrl         接口地址覆盖;空 = 目录里的缺省
 * @param proxy           代理 {@code host:port};空 = 跟随全局设置
 * @param reasoningEffort 思考档位的存档写法({@link com.dwinovo.numen.api.agent.catalog.ThinkingLevel#id});空 = 自动
 * @param ctx             上下文长度覆盖(token);0 = 随目录
 * @param temperature     温度;null = 不发
 * @param maxTokens       输出上限(token);0 = 不发
 * @param compat          兼容项覆盖(键见 {@link com.dwinovo.numen.api.agent.provider.Compats});null = 全用缺省
 */
public record ConnectionProfile(String provider, String protocol, String model, String apiKey, String baseUrl,
                                String proxy, String reasoningEffort, int ctx, Double temperature,
                                int maxTokens, JsonObject compat) {

    /** 自定义厂家:不在目录里,地址和协议全由档案给。 */
    public static final String CUSTOM = "custom";

    /** 换掉接口地址覆盖的副本(空 = 用目录缺省)。 */
    public ConnectionProfile withBaseUrl(String baseUrl) {
        return new ConnectionProfile(provider, protocol, model, apiKey, baseUrl, proxy, reasoningEffort, ctx,
                temperature, maxTokens, compat);
    }

    /** 换掉密钥的副本。 */
    public ConnectionProfile withApiKey(String apiKey) {
        return new ConnectionProfile(provider, protocol, model, apiKey, baseUrl, proxy, reasoningEffort, ctx,
                temperature, maxTokens, compat);
    }

    /** 换掉思考档位的副本。 */
    public ConnectionProfile withReasoningEffort(String reasoningEffort) {
        return new ConnectionProfile(provider, protocol, model, apiKey, baseUrl, proxy, reasoningEffort, ctx,
                temperature, maxTokens, compat);
    }
}
