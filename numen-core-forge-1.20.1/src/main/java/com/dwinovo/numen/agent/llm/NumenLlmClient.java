package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.http.HttpLlmTransport;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.DashScopeProvider;
import com.dwinovo.numen.agent.provider.DeepSeekProvider;
import com.dwinovo.numen.agent.provider.LlmProvider;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.provider.MinimaxProvider;
import com.dwinovo.numen.agent.provider.MoonshotProvider;
import com.dwinovo.numen.agent.provider.OpenAIProvider;
import com.dwinovo.numen.agent.provider.StreamAccumulator;
import com.dwinovo.numen.agent.provider.VolcengineProvider;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.client.agent.CompanionAiConfigStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mod-global LLM client (client-side singleton). Wraps {@link HttpLlmTransport}
 * + a configured {@link LlmProvider}; SSE streaming is the default so
 * users see token-by-token progress in logs (and, in the future, in a
 * chat bubble above the entity).
 *
 * <h2>Streaming flow</h2>
 * <ol>
 *   <li>{@link #chatStreaming} builds the request body via the provider,
 *       sets {@code stream:true} + {@code stream_options.include_usage:true},
 *       and dispatches to {@link HttpLlmTransport#postSse}.</li>
 *   <li>For each SSE chunk, the provider's {@code accumulateChunk} updates
 *       a per-call {@link StreamAccumulator}.</li>
 *   <li>When the stream terminates, {@code finalizeStream} produces an
 *       {@link AssistantTurn}; the future completes with it.</li>
 *   <li>Token usage and finish reason emitted as an INFO log line.</li>
 * </ol>
 *
 * <h2>Per-call request id</h2>
 * The transport tags every request with a short {@code lr-N} id used in
 * every related log line; users can grep one ID through the whole chain
 * (HTTP → provider → agent loop) when debugging.
 */
public final class NumenLlmClient {

    /** Suffix appended to base URLs that don't already end with it. */
    private static final String CHAT_COMPLETIONS_SUFFIX = "/chat/completions";

    private static final Map<UUID, NumenLlmClient> INSTANCES = new ConcurrentHashMap<>();

    private final HttpLlmTransport transport;
    private final LlmProvider provider;
    private final String fullUrl;
    private final String apiKey;
    private final String model;
    private final String reasoningEffort;

    private NumenLlmClient(CompanionAiConfigStore.Profile config) {
        this.provider = pickProvider(config.provider());
        String siteBase = com.dwinovo.numen.agent.model.ModelRegistry.baseUrl(config.provider());
        this.fullUrl = composeRequestUrl(config.baseUrl(), siteBase, provider, config.fullUrl());
        this.apiKey = config.apiKey();
        this.transport = new HttpLlmTransport(config.proxy(),
                com.dwinovo.numen.agent.model.ModelRegistry.headers(config.provider()));
        String configured = config.model();
        this.model = (configured == null || configured.isBlank()) ? "gpt-5.4-mini" : configured;
        this.reasoningEffort = config.reasoningEffort();
        Constants.LOG.info("[numen-llm] client initialised: provider={}, model={}, url={}, streaming={}",
                provider.name(), model, fullUrl, provider.supportsStreaming());
    }

    /**
     * Compose the chat completions URL. Base URL precedence: an explicit per-config override wins;
     * else the site's registered base URL (from {@code numen_models.json} — so "+ add site" URLs and
     * adapter-less sites like Gemini/Grok actually take effect); else the adapter's hard-coded default.
     * Then, LiteLLM-style: trim a trailing slash and append {@code /chat/completions} if absent.
     */
    public static String composeBaseUrl(String userBase, String siteBase, LlmProvider provider) {
        String base = nonBlank(userBase) ? userBase
                : nonBlank(siteBase) ? siteBase
                : provider.defaultBaseUrl();
        base = base.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith(CHAT_COMPLETIONS_SUFFIX)) {
            base = base.substring(0, base.length() - CHAT_COMPLETIONS_SUFFIX.length());
        }
        return base;
    }

    public static String composeChatUrl(String userBase, String siteBase, LlmProvider provider) {
        return composeBaseUrl(userBase, siteBase, provider) + CHAT_COMPLETIONS_SUFFIX;
    }

    /** Resolve the request URL, preserving an explicit complete URL byte-for-byte except whitespace. */
    public static String composeRequestUrl(String userBase, String siteBase, LlmProvider provider,
                                           boolean fullUrl) {
        if (fullUrl && nonBlank(userBase)) return userBase.trim();
        return composeChatUrl(userBase, siteBase, provider);
    }

    public static NumenLlmClient instance(UUID companion) {
        if (companion == null) throw new IllegalArgumentException("companion uuid is required");
        return INSTANCES.computeIfAbsent(companion,
                id -> new NumenLlmClient(CompanionAiConfigStore.get(id)));
    }

    public static boolean isConfigured(UUID companion) {
        String key = CompanionAiConfigStore.get(companion).apiKey();
        return key != null && !key.isBlank();
    }

    /**
     * Invalidate the cached singleton so the next {@link #instance()} call
     * rebuilds from fresh config. Called by the settings GUI after the
     * user changes provider / API key / model / base URL — lets players
     * switch backends without restarting the game.
     *
     * <p>In-flight requests on the old instance complete with the old
     * credentials (we don't try to cancel them); only subsequent calls
     * see the new config. Per-entity {@code ConvoState} is unaffected, so
     * conversations continue with the new backend.
     */
    public static void reset(UUID companion) {
        if (companion != null && INSTANCES.remove(companion) != null) {
            Constants.LOG.info("[numen-llm] resetting client for {}", companion);
        }
    }

    /**
     * Final turn plus the usage the backend reported for it. {@code promptTokens}
     * is the request's true context size as the API counted it — the signal the
     * agent loop's auto-compaction triggers on (no client-side token estimation
     * needed). Zero when the backend sent no usage frame.
     */
    public record ChatResult(AssistantTurn turn, int promptTokens, int totalTokens) {}

    /**
     * Streaming chat completion. Returns a future of the final
     * {@link ChatResult} — the {@link AssistantTurn} built up from all SSE
     * chunks via the provider's accumulator, plus reported token usage.
     *
     * @param messages       conversation history (provider translates to wire)
     * @param tools          tool list (provider serialises to wire shape;
     *                       empty = no tools field, e.g. for summarization calls)
     * @param systemPrompt   prepended automatically — pass empty / null to skip
     * @param onChunk        optional per-chunk callback (e.g. for live UI).
     *                       Receives the raw provider chunk JSON. May be null.
     */
    public CompletableFuture<ChatResult> chatStreaming(List<ConvoState.Msg> messages,
                                                       Collection<NumenTool> tools,
                                                       String systemPrompt,
                                                       Consumer<JsonObject> onChunk) {
        return chatStreaming(messages, tools, systemPrompt, onChunk, null);
    }

    /** Streaming chat completion with a caller-specific whole-request timeout. */
    public CompletableFuture<ChatResult> chatStreaming(List<ConvoState.Msg> messages,
                                                       Collection<NumenTool> tools,
                                                       String systemPrompt,
                                                       Consumer<JsonObject> onChunk,
                                                       Duration timeout) {
        // -- 1. Build wire-format messages and tool list via provider.
        List<JsonObject> wire = new ArrayList<>(messages.size());
        for (ConvoState.Msg m : messages) {
            if (m instanceof ConvoState.Msg.User u) {
                wire.add(provider.buildUserMessage(u.content()));
            } else if (m instanceof ConvoState.Msg.Assistant a) {
                wire.add(provider.assistantToRequestMessage(a.turn()));
            } else if (m instanceof ConvoState.Msg.Tool t) {
                wire.add(provider.buildToolResultMessage(t.toolCallId(), t.content()));
            } else {
                throw new IllegalStateException("Unexpected value: " + m);
            }
        }
        JsonArray toolList = provider.buildToolList(tools);
        JsonObject body = provider.buildRequestBody(model, systemPrompt, wire, toolList);
        applyReasoning(body, reasoningEffort);

        // -- 2. Enable streaming + usage reporting (both server-side flags).
        body.addProperty("stream", true);
        JsonObject streamOpts = new JsonObject();
        streamOpts.addProperty("include_usage", true);
        body.add("stream_options", streamOpts);

        if (Constants.LOG.isDebugEnabled()) {
            Constants.LOG.debug("[numen-llm] chat start: provider={}, model={}, msgs={}, tools={}, system_prompt_chars={}",
                    provider.name(), model, wire.size(), toolList.size(),
                    systemPrompt == null ? 0 : systemPrompt.length());
        }

        // -- 3. Stream the response into an accumulator.
        long t0 = System.nanoTime();
        StreamAccumulator acc = new StreamAccumulator();
        return transport.postSse(fullUrl, apiKey, body, chunk -> {
            try {
                provider.accumulateChunk(chunk, acc);
                if (onChunk != null) onChunk.accept(chunk);
            } catch (RuntimeException ex) {
                Constants.LOG.warn("[numen-llm] accumulator failed on chunk: {}", ex.getMessage());
            }
        }, timeout).thenApply(v -> {
            AssistantTurn turn = provider.finalizeStream(acc);
            logCallSummary(t0, acc, turn);
            return new ChatResult(turn,
                    jsonInt(acc.usage, "prompt_tokens"),
                    jsonInt(acc.usage, "total_tokens"));
        });
    }

    private void logCallSummary(long t0, StreamAccumulator acc, AssistantTurn turn) {
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        String tokens = "?";
        if (acc.usage != null) {
            int in = jsonInt(acc.usage, "prompt_tokens");
            int out = jsonInt(acc.usage, "completion_tokens");
            int total = jsonInt(acc.usage, "total_tokens");
            tokens = in + "/" + out + " (total " + total + ")";
        }
        StringBuilder toolSummary = new StringBuilder();
        for (LlmToolCall tc : turn.toolCalls()) {
            if (toolSummary.length() > 0) toolSummary.append(", ");
            toolSummary.append(tc.name());
        }
        String contentSnippet = turn.content().isEmpty() ? "<no text>"
                : truncate(turn.content().replace('\n', ' '), 120);
        Constants.LOG.info(
                "[numen-llm] chat done in {}ms, chunks={}, tokens={}, finish={}, tool_calls=[{}], content=\"{}\"",
                elapsedMs, acc.chunkCount, tokens,
                acc.finishReason == null ? "?" : acc.finishReason,
                toolSummary, contentSnippet);
    }

    private static boolean nonBlank(String s) { return s != null && !s.isBlank(); }

    private static int jsonInt(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return 0;
        try { return o.get(key).getAsInt(); } catch (RuntimeException ex) { return 0; }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public LlmProvider provider() { return provider; }

    /** OpenAI-compatible reasoning control; auto leaves provider defaults untouched. */
    public static void applyReasoning(JsonObject body, String effort) {
        if (body == null || effort == null || effort.isBlank() || "auto".equalsIgnoreCase(effort)) return;
        if ("none".equalsIgnoreCase(effort)) {
            return;
        } else {
            body.addProperty("reasoning_effort", effort.toLowerCase(java.util.Locale.ROOT));
        }
    }
    public String model() { return model; }

    /**
     * Map a config {@code provider} string to a concrete {@link LlmProvider}.
     *
     * <p>Accepted ids match LiteLLM's provider names plus a few Chinese
     * aliases that users naturally type:
     * <ul>
     *   <li>{@code openai} (default fallback)</li>
     *   <li>{@code deepseek} — DeepSeek (V4 / R1 family)</li>
     *   <li>{@code moonshot} / {@code kimi} — Moonshot AI (Kimi)</li>
     *   <li>{@code minimax} — MiniMax (abab / M-series)</li>
     *   <li>{@code volcengine} / {@code doubao} / {@code ark} — Doubao via Volcengine Ark</li>
     *   <li>{@code dashscope} / {@code qwen} / {@code tongyi} / {@code aliyun} — DashScope (Qwen)</li>
     * </ul>
     * Unknown values fall back to {@code openai} with a warning log.
     */
    public static LlmProvider pickProvider(String name) {
        if (name == null) return new OpenAIProvider();
        return switch (name.toLowerCase()) {
            case DeepSeekProvider.NAME -> new DeepSeekProvider();
            case MoonshotProvider.NAME, "kimi" -> new MoonshotProvider();
            case MinimaxProvider.NAME -> new MinimaxProvider();
            case VolcengineProvider.NAME, "doubao", "ark" -> new VolcengineProvider();
            case DashScopeProvider.NAME, "qwen", "tongyi", "aliyun" -> new DashScopeProvider();
            case com.dwinovo.numen.agent.provider.ZhipuProvider.NAME, "glm" ->
                    new com.dwinovo.numen.agent.provider.ZhipuProvider();
            case com.dwinovo.numen.agent.provider.SiliconFlowProvider.NAME, "silicon" ->
                    new com.dwinovo.numen.agent.provider.SiliconFlowProvider();
            case OpenAIProvider.NAME, "openai-compatible" -> new OpenAIProvider();
            // Any other registered site (Gemini, Grok, "+ add site" customs) is OpenAI-compatible — use the
            // base adapter silently; only a genuinely unknown id (typo) warns.
            default -> {
                if (!com.dwinovo.numen.agent.model.ModelRegistry.has(name)) {
                    Constants.LOG.warn("[numen-llm] unknown provider '{}', falling back to openai", name);
                }
                yield new OpenAIProvider();
            }
        };
    }
}
