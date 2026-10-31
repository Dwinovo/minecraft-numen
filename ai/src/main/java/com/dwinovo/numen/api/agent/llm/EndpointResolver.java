package com.dwinovo.numen.api.agent.llm;

import com.dwinovo.numen.api.agent.catalog.Api;
import com.dwinovo.numen.api.agent.catalog.Catalog;
import com.dwinovo.numen.api.agent.catalog.ModelInfo;
import com.dwinovo.numen.api.agent.catalog.ProviderInfo;
import com.dwinovo.numen.api.agent.catalog.ThinkingLevel;
import com.dwinovo.numen.api.agent.catalog.ThinkingTable;
import com.dwinovo.numen.api.agent.provider.Compats;
import com.dwinovo.numen.api.agent.provider.ModelSpec;
import com.dwinovo.numen.api.agent.provider.RequestOptions;

import java.util.Map;

/**
 * 档案 + 目录 → {@link LlmEndpoint}:全仓唯一做这件事的地方。每个字段取值的优先级也只在这里:
 * 玩家在档案里填的 &gt; 目录里的(目录内部的来源优先级见 {@link Catalog})。
 * 不合并、不猜:目录里查不到的型号就是"不知道能力",只给自动思考、64k 窗口。
 */
public final class EndpointResolver {

    /** 目录和档案都没给窗口时的上下文长度。 */
    public static final int DEFAULT_CTX = 64_000;
    /** 整理记忆的预留:档案没填时用这个(照 pi 的 reserveTokens)。 */
    public static final int DEFAULT_RESERVE_TOKENS = 16_384;
    /** 整理时原文保留的近段预算:档案没填时用这个(照 pi 的 keepRecentTokens)。 */
    public static final int DEFAULT_KEEP_RECENT_TOKENS = 20_000;

    private EndpointResolver() {}

    /**
     * @param globalProxy 档案没填代理时跟随的全局代理
     */
    public static LlmEndpoint resolve(ConnectionProfile p, Catalog catalog, String globalProxy) {
        String providerId = nz(p.provider());
        String modelId = nz(p.model());
        ProviderInfo vendor = catalog.provider(providerId);
        ModelInfo info = catalog.model(providerId, modelId);

        Api api = first(Api.byId(p.protocol()), info == null ? null : info.api(),
                vendor == null ? null : vendor.api(), Api.CHAT);
        String baseUrl = firstNonBlank(p.baseUrl(), info == null ? null : info.baseUrl(),
                vendor == null ? null : vendor.baseUrl());
        ThinkingTable thinking = info == null ? ThinkingTable.AUTO_ONLY : info.thinking();
        int ctx = p.ctx() > 0 ? p.ctx()
                : info != null && info.contextWindow() > 0 ? info.contextWindow() : DEFAULT_CTX;

        return new LlmEndpoint(
                providerId,
                baseUrl,
                vendor == null ? Map.of() : vendor.headers(),
                p.apiKey(),
                firstNonBlank(p.proxy(), globalProxy),
                new ModelSpec(modelId, thinking, info == null || info.temperature()),
                ctx,
                Compats.resolve(api, providerId, modelId, baseUrl, thinking, p.compat()),
                new RequestOptions(p.temperature(), p.maxTokens(), ThinkingLevel.byId(p.reasoningEffort())),
                p.reserveTokens() > 0 ? p.reserveTokens() : DEFAULT_RESERVE_TOKENS,
                p.keepRecentTokens() > 0 ? p.keepRecentTokens() : DEFAULT_KEEP_RECENT_TOKENS);
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }

    @SafeVarargs
    private static <T> T first(T... values) {
        for (T v : values) {
            if (v != null) return v;
        }
        throw new IllegalArgumentException("no value");
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.strip();
        }
        return "";
    }
}
