package com.dwinovo.numen.agent.provider;

/**
 * SiliconFlow (硅基流动) provider — an OpenAI-compatible aggregator hosting many open models behind one
 * key (model ids are namespaced, e.g. {@code deepseek-ai/DeepSeek-V3}, {@code zai-org/GLM-4.6}). Only the
 * endpoint differs from OpenAI; pick the exact model in settings (or type a custom one).
 */
public final class SiliconFlowProvider extends OpenAIProvider {

    public static final String NAME = "siliconflow";
    public static final String DEFAULT_BASE_URL = "https://api.siliconflow.cn/v1";

    @Override public String name() { return NAME; }

    @Override public String defaultBaseUrl() { return DEFAULT_BASE_URL; }
}
