package com.dwinovo.numen.api.agent.llm;

import com.dwinovo.numen.api.ai.AiLog;
import com.dwinovo.numen.api.agent.http.CancelToken;
import com.dwinovo.numen.api.agent.http.HttpLlmTransport;
import com.dwinovo.numen.api.agent.provider.Usage;
import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.api.agent.provider.IToolSpec;
import com.dwinovo.numen.api.agent.provider.LlmProvider;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.provider.StreamAccumulator;
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
    private final LlmEndpoint endpoint;
    private final String fullUrl;

    private NumenLlmClient(LlmEndpoint endpoint) {
        if (!endpoint.hasUrl()) {
            throw new IllegalStateException("no base URL for provider '" + endpoint.providerId()
                    + "': the catalog has none and the profile gives none");
        }
        this.endpoint = endpoint;
        this.provider = LlmProvider.of(endpoint.compat());
        this.fullUrl = composeUrl(endpoint.baseUrl(), provider.chatPath());
        this.transport = new HttpLlmTransport(endpoint.proxy(), endpoint.headers(),
                provider.authHeaders(endpoint.apiKey()));
        AiLog.LOG.info("[numen-llm] client initialised: provider={}, api={}, model={}, url={}, streaming={}",
                endpoint.providerId(), endpoint.api().id(), endpoint.model().id(), fullUrl,
                provider.supportsStreaming());
    }

    /**
     * Compose the chat URL: trim a trailing slash from the base and append the protocol's path
     * ({@code /chat/completions} / {@code /responses} / {@code /messages}) unless the base already ends with it.
     */
    private static String composeUrl(String base, String chatPath) {
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith(chatPath)) return base;
        return base + chatPath;
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

    /** 一个 chunk 带来的正文与思考增量(任一可空串,不会两者皆空)。 */
    public record Delta(String content, String reasoning) {}

    /**
     * Streaming chat completion. Returns a future of the final
     * {@link ChatResult} — the {@link AssistantTurn} built up from all SSE
     * chunks via the provider's accumulator, plus reported token usage.
     *
     * @param messages       conversation history as recorded (Halts, dangling tool calls and
     *                       adjacent user messages included); {@link ProtocolView#forWire}
     *                       makes it provider-valid, then the provider translates it to wire
     * @param tools          tool list (provider serialises to wire shape;
     *                       empty = no tools field, e.g. for summarization calls)
     * @param systemPrompt   prepended automatically — pass empty / null to skip
     * @param sessionId      stable id of the conversation (the companion's uuid), for protocols that route
     *                       cache lookups by a client key; null = none
     * @param cancel         cancelling it closes the stream: {@code onDelta} is not called again,
     *                       nothing is retried, and the future fails with
     *                       {@link java.util.concurrent.CancellationException}
     * @param onDelta        optional live-UI callback, called once per chunk that grew the reply.
     *                       The increments are read off the accumulator, so they are exactly what
     *                       the final {@link AssistantTurn} will contain (dialect fields, {@code <think>}
     *                       spans and all). May be null.
     */
    public CompletableFuture<ChatResult> chatStreaming(List<ConvoState.Msg> messages,
                                                       Collection<? extends IToolSpec> tools,
                                                       String systemPrompt,
                                                       String sessionId,
                                                       CancelToken cancel,
                                                       Consumer<Delta> onDelta) {
        // -- 1. Build wire-format messages and tool list via provider. The recorded history goes
        //        through ProtocolView first — this is the only place a history becomes a request,
        //        so tool-call pairing, cut-off turns and adjacent user messages are settled here once.
        List<ConvoState.Msg> sendable = ProtocolView.forWire(messages);
        List<JsonObject> wire = new ArrayList<>(sendable.size());
        for (ConvoState.Msg m : sendable) {
            switch (m) {
                case ConvoState.Msg.User u -> wire.add(provider.buildUserMessage(u.content()));
                // 上一家的私货(思考签名、reasoning_content)只有产它的那家认:换过模型就脱掉再发。
                // 原样递过去轻则浪费,重则被 400 拒,而换绑模型是面板上一个按钮的事。
                case ConvoState.Msg.Assistant a -> wire.addAll(provider.assistantToRequestItems(
                        a.turn().sameOrigin(endpoint.origin()) ? a.turn() : a.turn().withoutProviderPrivateFields()));
                case ConvoState.Msg.Tool t -> wire.add(provider.buildToolResultMessage(t.toolCallId(), t.content()));
                case ConvoState.Msg.Halt h -> throw new IllegalStateException(
                        "ProtocolView.forWire never emits Halt: " + h.reason());
            }
        }
        JsonArray toolList = provider.buildToolList(tools);
        JsonObject body = provider.buildRequestBody(endpoint.model(), systemPrompt, wire, toolList,
                endpoint.options(), sessionId);

        if (AiLog.LOG.isDebugEnabled()) {
            AiLog.LOG.debug("[numen-llm] chat start: provider={}, model={}, msgs={}, tools={}, system_prompt_chars={}",
                    endpoint.providerId(), endpoint.model().id(), wire.size(), toolList.size(),
                    systemPrompt == null ? 0 : systemPrompt.length());
        }

        // -- 3. Stream the response into an accumulator.
        long t0 = System.nanoTime();
        StreamAccumulator acc = new StreamAccumulator();
        return transport.postSse(fullUrl, endpoint.apiKey(), body, chunk -> {
            try {
                int contentFrom = acc.content.length();
                int reasoningFrom = acc.reasoning.length();
                provider.accumulateChunk(chunk, acc);
                if (onDelta != null) {
                    String content = acc.content.substring(contentFrom);
                    String reasoning = acc.reasoning.substring(reasoningFrom);
                    if (!content.isEmpty() || !reasoning.isEmpty()) {
                        onDelta.accept(new Delta(content, reasoning));
                    }
                }
            } catch (RuntimeException ex) {
                AiLog.LOG.warn("[numen-llm] accumulator failed on chunk: {}", ex.getMessage());
            }
        }, cancel).thenApply(v -> {
            AssistantTurn turn = provider.finalizeStream(acc).withOrigin(endpoint.origin());
            Usage usage = provider.usage(acc.usage);
            logCallSummary(t0, acc, turn, usage);
            return new ChatResult(turn, usage);
        });
    }

    private void logCallSummary(long t0, StreamAccumulator acc, AssistantTurn turn, Usage usage) {
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        // 各协议的用量字段不同,统一读归一后的四元
        String tokens = acc.usage == null ? "?"
                : usage.promptTokens() + "/" + usage.output() + " (total " + usage.total() + ")";
        StringBuilder toolSummary = new StringBuilder();
        for (LlmToolCall tc : turn.toolCalls()) {
            if (toolSummary.length() > 0) toolSummary.append(", ");
            toolSummary.append(tc.name());
        }
        AiLog.LOG.info(
                "[numen-llm] chat done in {}ms, chunks={}, tokens={}, finish={}, tool_calls=[{}], content={} chars",
                elapsedMs, acc.chunkCount, tokens,
                acc.finishReason == null ? "?" : acc.finishReason,
                toolSummary, turn.content().length());
    }

    public LlmProvider provider() { return provider; }
    public LlmEndpoint endpoint() { return endpoint; }
}
