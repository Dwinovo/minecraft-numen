package com.dwinovo.numen.api.agent.llm;

import com.dwinovo.numen.api.agent.catalog.Api;
import com.dwinovo.numen.api.agent.catalog.ThinkingTable;
import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.api.agent.provider.Compat;
import com.dwinovo.numen.api.agent.provider.Compats;
import com.dwinovo.numen.api.agent.provider.LlmProvider;
import com.dwinovo.numen.api.agent.provider.ModelSpec;
import com.dwinovo.numen.api.agent.provider.RequestOptions;
import com.dwinovo.numen.api.agent.provider.ThinkingPlan;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * 档案 + 目录解析出来的结果:一次请求要用的一切(去哪、用什么协议和兼容项、哪个型号及其能力、
 * 玩家的生成参数、窗口多大)。只由 {@link EndpointResolver} 一处造出来;请求、压缩阈值、界面显示
 * 都从同一个结果取,谁也不再自己去查目录。
 *
 * <p>也是客户端缓存的键:相同的端点值得到同一个客户端(客户端持有 HTTP 连接池,值得复用)。
 *
 * @param providerId    厂家 id
 * @param baseUrl       接口基址(不含 {@code /chat/completions} 之类的路径段);可能为空——厂家没有固定地址又没填
 * @param headers       厂家要求的额外报头
 * @param apiKey        密钥,已去首尾空白——复制来的 key 常带空格或换行,发出去就是一个错的 key
 * @param proxy         代理 {@code host:port};空 = 直连
 * @param model         型号及其能力
 * @param contextWindow 上下文窗口(token):压缩闸门与水位显示的唯一口径;没解析出端点时是 0
 * @param compat        这个端点的兼容项,同时决定协议
 * @param options       玩家的生成参数
 * @param reserveTokens 整理记忆的预留:上下文超过 窗口 − 预留 就整理(档案没填是 {@link EndpointResolver#DEFAULT_RESERVE_TOKENS})
 * @param keepRecentTokens 整理时原文保留的近段预算(档案没填是 {@link EndpointResolver#DEFAULT_KEEP_RECENT_TOKENS})
 */
public record LlmEndpoint(String providerId, String baseUrl, Map<String, String> headers, String apiKey,
                          String proxy, ModelSpec model, int contextWindow, Compat compat,
                          RequestOptions options, int reserveTokens, int keepRecentTokens) {

    public LlmEndpoint {
        apiKey = apiKey == null ? "" : apiKey.strip();
        headers = Map.copyOf(headers);
    }

    /** 什么都没解析出来的端点(档案不存在):没地址、没密钥,两种“发不了”的判断照常成立。 */
    public static LlmEndpoint unresolved(String proxy) {
        return new LlmEndpoint("", "", Map.of(), "", proxy,
                new ModelSpec("", ThinkingTable.AUTO_ONLY, true), 0,
                Compats.resolve(Api.CHAT, "", "", "", ThinkingTable.AUTO_ONLY, null), RequestOptions.NONE,
                EndpointResolver.DEFAULT_RESERVE_TOKENS, EndpointResolver.DEFAULT_KEEP_RECENT_TOKENS);
    }

    public boolean hasApiKey() {
        return !apiKey.isEmpty();
    }

    public boolean hasUrl() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    public Api api() {
        return compat.api();
    }

    /** 这个端点上这个型号的思考计划:界面列哪几档、请求写什么,都问它(与协议实现问的是同一个)。 */
    public ThinkingPlan thinkingPlan() {
        return ThinkingPlan.of(model.thinking(), compat);
    }

    /**
     * 回合的出处戳:{@code 协议/厂家/型号}。回合带着它落盘,换绑之后认得出哪些私货不是自家的
     * (同一个厂家的另一个型号、同一个型号的另一个厂家,私货都不通用)。
     */
    public String origin() {
        return api().id() + "/" + providerId + "/" + model.id();
    }

    /** 今天这个窗口里,上下文超过多少就该整理记忆。 */
    public int compactionThreshold() {
        return contextWindow - reserveTokens;
    }

    /**
     * 这一次请求真正发的生成参数:输出上限取玩家设的(没设就是型号的输出上限),再收到不超过窗口里还剩的量
     * (窗口 − 请求输入的估计 − {@value #OUTPUT_SAFETY_TOKENS} 的余量,至少 1)——普通一轮与整理的摘要都这样定,
     * 请求本身很大时输出不会超出窗口。上限和窗口都不知道时不发,吃服务端和协议的缺省。
     *
     * @param inputTokens 这次请求的输入(历史、系统提示、工具表)按 {@link CompactSplit} 那把尺估的量
     */
    public RequestOptions optionsFor(int inputTokens) {
        int desired = options.maxTokens() > 0 ? options.maxTokens() : model.thinking().maxOutput();
        if (desired <= 0 || contextWindow <= 0) {
            return options;
        }
        long room = (long) contextWindow - inputTokens - OUTPUT_SAFETY_TOKENS;
        return options.withMaxTokens((int) Math.min(desired, Math.max(1, room)));
    }

    /** 输出上限留给窗口的余量:输入的估计是粗的。 */
    private static final int OUTPUT_SAFETY_TOKENS = 4096;

    /**
     * 一条助手回合发给这个端点时的线格式——请求怎么写它、压缩估算按什么量它,都问这一处。
     * 上一家的私货(思考签名、reasoning_content)只有产它的那家认:出处对不上就脱掉再发,
     * 原样递过去轻则浪费,重则被 400 拒,而换绑模型是面板上一个按钮的事。
     */
    public List<JsonObject> assistantWireItems(AssistantTurn turn) {
        return LlmProvider.of(compat).assistantToRequestItems(
                turn.sameOrigin(origin()) ? turn : turn.withoutProviderPrivateFields());
    }
}
