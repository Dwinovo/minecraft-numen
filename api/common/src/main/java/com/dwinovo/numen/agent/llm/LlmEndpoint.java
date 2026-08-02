package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.platform.services.INumenConfig;

/**
 * Fully-resolved, immutable LLM connection parameters — the ONLY thing
 * {@link NumenLlmClient} depends on. Where the values came from (the global
 * settings screen, a provider-library entry a companion selected) is resolved
 * BEFORE this point; the client never reads a settings store.
 *
 * <p>Also the identity key for client caching: same endpoint values → same
 * cached client (clients hold an HTTP connection pool worth reusing).
 */
public record LlmEndpoint(String provider, String model, String apiKey,
                          String baseUrl, String proxy, String reasoningEffort) {

    /** The endpoint the GLOBAL settings currently describe — resolved fresh on
     *  every call, so a settings change takes effect on the next request with
     *  no invalidation ceremony. */
    public static LlmEndpoint fromGlobal(INumenConfig config) {
        return new LlmEndpoint(
                config.getProvider(), config.getModel(), config.getApiKey(),
                config.getBaseUrl(), config.getProxy(), config.getReasoningEffort());
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
