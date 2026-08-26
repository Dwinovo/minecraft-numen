package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.ai.AiLog;
import com.dwinovo.numen.agent.http.HttpLlmTransport;
import com.dwinovo.numen.agent.provider.Usage;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.ProviderRegistry;

import com.dwinovo.numen.agent.provider.AnthropicProvider;
import com.dwinovo.numen.agent.provider.DeepSeekProvider;
import com.dwinovo.numen.agent.provider.IToolSpec;
import com.dwinovo.numen.agent.provider.LlmProvider;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.provider.MoonshotProvider;
import com.dwinovo.numen.agent.provider.OpenAIProvider;
import com.dwinovo.numen.agent.provider.StreamAccumulator;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

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

    private final HttpLlmTransport transport;
    private final LlmProvider provider;
    private final String fullUrl;
    private final String apiKey;
    private final String model;
    /** Reasoning effort: {@code auto}(send nothing) | {@code off}(明确关) | {@code low} | {@code medium} | {@code high}. */
    private final String reasoningEffort;
    /** 玩家在用户文件里给该模型配的生成参数(null/0 = 不发,吃服务器默认)。 */
    private final Double genTemperature;
    private final int genMaxTokens;

    private NumenLlmClient(LlmEndpoint endpoint) {
        this.provider = pickProvider(endpoint.provider());
        String siteBase = com.dwinovo.numen.agent.provider.ProviderRegistry.baseUrl(endpoint.provider());
        this.fullUrl = composeUrl(endpoint.baseUrl(), siteBase, provider);
        this.apiKey = endpoint.apiKey();
        this.transport = new HttpLlmTransport(endpoint.proxy(),
                com.dwinovo.numen.agent.provider.ProviderRegistry.headers(endpoint.provider()),
                provider.authHeaders(endpoint.apiKey()));
        String configured = endpoint.model();
        this.model = (configured == null || configured.isBlank()) ? "gpt-5.4-mini" : configured;
        this.reasoningEffort = normalizeReasoning(endpoint.reasoningEffort());
        ProviderRegistry.Model registered = ProviderRegistry.model(endpoint.provider(), this.model);
        this.genTemperature = registered == null ? null : registered.temperature();
        this.genMaxTokens = registered == null ? 0 : registered.maxTokens();
        AiLog.LOG.info("[numen-llm] client initialised: provider={}, model={}, url={}, streaming={}",
                provider.name(), model, fullUrl, provider.supportsStreaming());
    }

    /**
     * Compose the chat URL. Base URL precedence: an explicit per-config override wins;
     * else the site's registered base URL (from {@code numen_providers.json} — so "+ add site" URLs and
     * adapter-less sites like Gemini/Grok actually take effect); else the adapter's hard-coded default.
     * Then normalise: trim a trailing slash and append the provider's chat path
     * ({@code /chat/completions} / {@code /messages}) if absent.
     */
    private static String composeUrl(String userBase, String siteBase, LlmProvider provider) {
        String base = nonBlank(userBase) ? userBase
                : nonBlank(siteBase) ? siteBase
                : provider.defaultBaseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith(provider.chatPath())) return base;
        return base + provider.chatPath();
    }

    /**
     * One immutable client per distinct endpoint, keyed by VALUE — same connection
     * parameters reuse the same client (and its HTTP pool); different companions
     * pointing at different providers each get their own. Bounded by the number of
     * distinct endpoints a player configures (a handful).
     */
    private static final java.util.concurrent.ConcurrentHashMap<LlmEndpoint, NumenLlmClient> CLIENTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The client for an explicit endpoint (a companion's selected provider entry). */
    public static NumenLlmClient forEndpoint(LlmEndpoint endpoint) {
        return CLIENTS.computeIfAbsent(endpoint, NumenLlmClient::new);
    }

    /**
     * Drop every cached client so subsequent {@link #forEndpoint} calls rebuild
     * from freshly-resolved endpoints. Called by the settings GUI after the
     * user changes provider / API key / model / base URL — lets players
     * switch backends without restarting the game.
     *
     * <p>In-flight requests on the old instances complete with the old
     * credentials (we don't try to cancel them); only subsequent calls
     * see the new config. Per-entity {@code ConvoState} is unaffected, so
     * conversations continue with the new backend.
     */
    public static void reset() {
        AiLog.LOG.info("[numen-llm] clearing {} cached client(s) (next calls rebuild from current config)",
                CLIENTS.size());
        CLIENTS.clear();
    }

    /**
     * Final turn plus the usage the backend reported for it. {@code promptTokens}
     * is the request's true context size as the API counted it — the signal the
     * agent loop's auto-compaction triggers on (no client-side token estimation
     * needed). Zero when the backend sent no usage frame.
     */
    public record ChatResult(AssistantTurn turn, Usage usage) {

        /** 提示词的完整体量——自动压缩的触发判据(API 数的,不用客户端估)。 */
        public long promptTokens() {
            return usage.promptTokens();
        }

        /** 提示词加输出。 */
        public long totalTokens() {
            return usage.total();
        }

        /** 这一轮真正新处理的量:缓存读不计。 */
        public long freshTokens() {
            return usage.fresh();
        }
    }

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
                                                       Collection<? extends IToolSpec> tools,
                                                       String systemPrompt,
                                                       Consumer<JsonObject> onChunk) {
        // -- 1. Build wire-format messages and tool list via provider.
        List<JsonObject> wire = new ArrayList<>(messages.size());
        for (ConvoState.Msg m : messages) {
            switch (m) {
                case ConvoState.Msg.User u -> wire.add(provider.buildUserMessage(u.content()));
                case ConvoState.Msg.Assistant a -> wire.add(provider.assistantToRequestMessage(a.turn()));
                case ConvoState.Msg.Tool t -> wire.add(provider.buildToolResultMessage(t.toolCallId(), t.content()));
            }
        }
        JsonArray toolList = provider.buildToolList(tools);
        JsonObject body = provider.buildRequestBody(model, systemPrompt, wire, toolList);

        // -- 1a. 玩家配置的生成参数(temperature/max_tokens),opt-in 才上盘;
        //        必须先于思考开关——预算类方言在 max_tokens 之上加码。
        provider.applyGenerationParams(body, genTemperature, genMaxTokens);

        // -- 1b. Reasoning / deep-thinking: only when the user opted in (never on "auto"), so a
        //        non-reasoning model is never handed an unexpected parameter it would 400 on.
        if (!"auto".equals(reasoningEffort)) {
            provider.applyReasoning(body, reasoningEffort);
        }

        // -- 2. Enable streaming + usage reporting (protocol-dialect shape).
        provider.applyStreaming(body);

        if (AiLog.LOG.isDebugEnabled()) {
            AiLog.LOG.debug("[numen-llm] chat start: provider={}, model={}, msgs={}, tools={}, system_prompt_chars={}",
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
                AiLog.LOG.warn("[numen-llm] accumulator failed on chunk: {}", ex.getMessage());
            }
        }).thenApply(v -> {
            AssistantTurn turn = provider.finalizeStream(acc);
            logCallSummary(t0, acc, turn);
            return new ChatResult(turn, provider.usage(acc.usage));
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
        AiLog.LOG.info(
                "[numen-llm] chat done in {}ms, chunks={}, tokens={}, finish={}, tool_calls=[{}], content=\"{}\"",
                elapsedMs, acc.chunkCount, tokens,
                acc.finishReason == null ? "?" : acc.finishReason,
                toolSummary, contentSnippet);
    }

    private static boolean nonBlank(String s) { return s != null && !s.isBlank(); }

    /** Coerce a stored reasoning value to auto/off/low/medium/high ("auto" = don't send the parameter,
     *  "off" = 明确关闭思考——由 provider 按方言翻译,无开关的方言静默)。 */
    private static String normalizeReasoning(String v) {
        if (v == null) return "auto";
        return switch (v.trim().toLowerCase()) {
            case "off", "low", "medium", "high" -> v.trim().toLowerCase();
            default -> "auto";
        };
    }

    private static int jsonInt(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return 0;
        try { return o.get(key).getAsInt(); } catch (RuntimeException ex) { return 0; }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public LlmProvider provider() { return provider; }
    public String model() { return model; }

    /**
     * Map a config {@code provider} string to a concrete {@link LlmProvider}.
     *
     * <p>Only backends with genuine behavioural differences get a subclass
     * (DeepSeek's cache billing, Moonshot's {@code reasoning_content}
     * fallback). Every other site — zhipu, siliconflow, minimax, volcengine,
     * dashscope, gemini, grok, "+ add site" customs — is a plain
     * OpenAI-compatible endpoint whose name and default base URL come from
     * {@code numen_providers.json} ({@link ProviderRegistry}), the single source
     * the settings UI reads too — base URL, thinking dialect, and wire
     * protocol ({@code protocol:"anthropic"} rows get the Anthropic Messages
     * adapter instead of the OpenAI-compat one). Alias resolution
     * ({@code kimi}/{@code doubao}/{@code glm}/...) lives in
     * {@link ProviderRegistry#canonicalId} — the one and only alias table.
     * Unknown values fall back to {@code openai} with a warning log.
     */
    public static LlmProvider pickProvider(String name) {
        String id = ProviderRegistry.canonicalId(name);
        if (id.equals(DeepSeekProvider.NAME)) return new DeepSeekProvider();
        if (id.equals(MoonshotProvider.NAME)) return new MoonshotProvider();
        if (id.equals(OpenAIProvider.NAME)) return new OpenAIProvider();
        if (ProviderRegistry.has(id)) {
            String baseUrl = ProviderRegistry.baseUrl(id);
            if ("anthropic".equals(ProviderRegistry.protocol(id))) {
                return new AnthropicProvider(id,
                        baseUrl == null || baseUrl.isBlank() ? AnthropicProvider.DEFAULT_BASE_URL : baseUrl,
                        ProviderRegistry.thinkingFormat(id));
            }
            return new OpenAIProvider(id,
                    baseUrl == null || baseUrl.isBlank() ? OpenAIProvider.DEFAULT_BASE_URL : baseUrl,
                    ProviderRegistry.thinkingFormat(id));
        }
        AiLog.LOG.warn("[numen-llm] unknown provider '{}', falling back to openai", name);
        return new OpenAIProvider();
    }
}
