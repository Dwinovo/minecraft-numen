package com.dwinovo.numen.agent.llm;

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

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
