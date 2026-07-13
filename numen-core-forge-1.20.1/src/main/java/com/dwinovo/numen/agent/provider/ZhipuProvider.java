package com.dwinovo.numen.agent.provider;

/**
 * Zhipu AI (智谱 / GLM) provider — OpenAI-compatible, so just the endpoint differs. Its chat-completions
 * API lives under {@code .../api/paas/v4}; the GLM-4.x family (glm-4.6, glm-4.5-air, glm-4-flash, …) speaks
 * the standard OpenAI wire format, including tools. Reasoning fields round-trip via the base extras-capture.
 */
public final class ZhipuProvider extends OpenAIProvider {

    public static final String NAME = "zhipu";
    public static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";

    @Override public String name() { return NAME; }

    @Override public String defaultBaseUrl() { return DEFAULT_BASE_URL; }
}
