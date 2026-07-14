package com.dwinovo.numen.agent.http;

import com.dwinovo.numen.Constants;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * HTTPS transport for OpenAI-protocol chat completions. Built on the JDK
 * {@code java.net.http.HttpClient} (Java 11+, daemon-threaded executor) so
 * we ship zero third-party HTTP dependencies.
 *
 * <h2>Two modes</h2>
 * <ul>
 *   <li>{@link #post}    — buffered: response JSON returned whole.
 *       Used historically; useful for non-streaming backends or debug.</li>
 *   <li>{@link #postSse} — streamed: caller passes a chunk-handler invoked
 *       once per parsed SSE event. Backend must respond with
 *       {@code text/event-stream}.</li>
 * </ul>
 *
 * <h2>Request id tagging</h2>
 * Every call gets a short sequential id ({@code lr-N}) logged on send, on
 * response status, and on each streamed chunk — makes it possible to follow
 * one specific request through the log when the agent loop has interleaved
 * activity from multiple Numenes or back-to-back retries.
 *
 * <h2>Error model</h2>
 * <ul>
 *   <li>2xx + streaming → caller's chunkHandler invoked, future completes
 *       when stream terminates (graceful {@code [DONE]} or stream close)</li>
 *   <li>non-2xx → future fails with {@link LlmHttpException} carrying the
 *       status code and full response body</li>
 *   <li>network / DNS / timeout → future fails with the wrapped IOException</li>
 * </ul>
 */
public final class HttpLlmTransport {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    // Retry policy: the exact constants openai-python / openai-node / anthropic-sdk
    // ship (Stainless family): 2 retries, min(0.5s × 2ⁿ, 8s) backoff, ≤25% jitter.
    private static final int MAX_RETRIES = 2;
    private static final long INITIAL_RETRY_DELAY_MS = 500;
    private static final long MAX_RETRY_DELAY_MS = 8_000;
    /** Inter-chunk idle threshold. The JDK request timeout only covers up to response
     *  headers — a wedged SSE body otherwise hangs forever (openai-node has the same
     *  hole; openai-python's effective per-read ceiling is 600s). A healthy stream
     *  emits deltas/keepalives every few seconds; 90s is an order of magnitude above
     *  any legitimate gap. */
    private static final long SSE_IDLE_TIMEOUT_MS = 90_000;
    private static final java.util.concurrent.ScheduledExecutorService IDLE_WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "numen-sse-watchdog");
                t.setDaemon(true);
                return t;
            });

    private static final AtomicInteger REQUEST_ID_SOURCE = new AtomicInteger();

    private final HttpClient client;
    private final java.util.Map<String, String> extraHeaders;

    public HttpLlmTransport(String proxy, java.util.Map<String, String> extraHeaders) {
        this.extraHeaders = extraHeaders == null ? java.util.Map.of() : extraHeaders;
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL);
        java.net.ProxySelector ps = proxySelector(proxy);
        if (ps != null) b.proxy(ps);
        this.client = b.build();
    }

    /** Parse a {@code host:port} (scheme optional) proxy into a selector, or null if blank/invalid. */
    private static java.net.ProxySelector proxySelector(String proxy) {
        if (proxy == null || proxy.isBlank()) return null;
        try {
            String s = proxy.trim();
            int scheme = s.indexOf("://");
            if (scheme >= 0) s = s.substring(scheme + 3);
            s = s.replaceAll("/.*$", "");
            int colon = s.lastIndexOf(':');
            if (colon < 0) return null;
            String host = s.substring(0, colon);
            int port = Integer.parseInt(s.substring(colon + 1));
            Constants.LOG.info("[numen-http] routing LLM calls through proxy {}:{}", host, port);
            return java.net.ProxySelector.of(new java.net.InetSocketAddress(host, port));
        } catch (Exception e) {
            Constants.LOG.warn("[numen-http] invalid proxy '{}' (expected host:port) — going direct", proxy);
            return null;
        }
    }

    /**
     * POST a JSON body, get the whole JSON response back. Used for
     * non-streaming requests.
     */
    public CompletableFuture<JsonObject> post(String url, String apiKey, JsonObject body) {
        String requestId = nextRequestId();
        String bodyStr = body.toString();
        long t0 = System.nanoTime();
        Constants.LOG.debug("[numen-http][{}] POST {} ({} bytes, buffered)",
                requestId, url, bodyStr.length());

        HttpRequest request = baseRequest(url, apiKey, "application/json", bodyStr);
        return client.sendAsync(request, BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(resp -> interpretBuffered(requestId, t0, resp));
    }

    /**
     * POST a JSON body, stream the response as SSE events into the
     * {@code chunkHandler}. The returned future completes when the stream
     * terminates normally; it fails with {@link LlmHttpException} if the
     * server replied non-2xx (in which case the chunk handler is never
     * invoked).
     */
    public CompletableFuture<Void> postSse(String url, String apiKey, JsonObject body,
                                            Consumer<JsonObject> chunkHandler) {
        return postSseAttempt(url, apiKey, body, chunkHandler, 0);
    }

    /**
     * One attempt of the SSE POST, retrying itself for PRE-STREAM failures only —
     * the scope every major SDK uses: connect errors / timeouts before headers, and
     * retryable statuses (408/409/429/5xx, {@code x-should-retry} override), with
     * {@code min(0.5s × 2ⁿ, 8s) × jitter} backoff and {@code Retry-After ≤ 60s}
     * honored. Once the first stream event reached the caller, a failure is NEVER
     * retried here (tokens were consumed) — it surfaces for the turn layer to
     * discard the partial and re-run the whole turn. An inter-chunk idle watchdog
     * ({@value #SSE_IDLE_TIMEOUT_MS}ms) kills a wedged stream — the JDK request
     * timeout only covers up to response HEADERS, so without this a half-dead
     * connection hangs the agent loop forever.
     */
    private CompletableFuture<Void> postSseAttempt(String url, String apiKey, JsonObject body,
                                                   Consumer<JsonObject> chunkHandler, int attempt) {
        String requestId = nextRequestId() + (attempt > 0 ? "r" + attempt : "");
        String bodyStr = body.toString();
        long t0 = System.nanoTime();
        AtomicLong chunkCount = new AtomicLong();
        AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());
        java.util.concurrent.atomic.AtomicBoolean idleKilled = new java.util.concurrent.atomic.AtomicBoolean();
        Constants.LOG.debug("[numen-http][{}] POST {} ({} bytes, streaming)",
                requestId, url, bodyStr.length());

        HttpRequest request = baseRequest(url, apiKey, "text/event-stream", bodyStr);

        // Branch on status: 2xx → SSE subscriber; non-2xx → buffer to string so
        // we can surface the (typically JSON) error body in LlmHttpException.
        BodyHandler<String> handler = ri -> {
            if (ri.statusCode() / 100 == 2) {
                SseSubscriber sub = new SseSubscriber(requestId, chunkHandler, chunkCount, lastActivityNanos);
                return BodySubscribers.fromLineSubscriber(sub, s -> "", StandardCharsets.UTF_8, "\n");
            }
            return BodySubscribers.ofString(StandardCharsets.UTF_8);
        };

        CompletableFuture<HttpResponse<String>> sendFuture = client.sendAsync(request, handler);
        // Idle watchdog: ANY received line (data, keepalive comment, blank) counts as
        // activity. A stream silent past the threshold is a half-dead connection —
        // cancel the exchange; the failure surfaces below tagged as idle.
        java.util.concurrent.ScheduledFuture<?> watchdog = IDLE_WATCHDOG.scheduleWithFixedDelay(() -> {
            if ((System.nanoTime() - lastActivityNanos.get()) / 1_000_000 > SSE_IDLE_TIMEOUT_MS) {
                idleKilled.set(true);
                sendFuture.cancel(true);
            }
        }, 15, 15, java.util.concurrent.TimeUnit.SECONDS);

        return sendFuture.handle((resp, ex) -> {
            watchdog.cancel(false);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            if (ex != null) {
                Throwable cause = ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null
                        ? ex.getCause() : ex;
                if (idleKilled.get()) {
                    Constants.LOG.warn("[numen-http][{}] ✗ SSE idle >{}ms after {} chunks — killed",
                            requestId, SSE_IDLE_TIMEOUT_MS, chunkCount.get());
                    cause = new java.io.IOException("SSE stream went silent for over "
                            + (SSE_IDLE_TIMEOUT_MS / 1000) + "s (half-dead connection)");
                }
                // Pre-stream connection failures retry; anything after the first
                // delivered chunk is the turn layer's decision.
                if (attempt < MAX_RETRIES && chunkCount.get() == 0) {
                    return retryAfterDelay(url, apiKey, body, chunkHandler, attempt,
                            computeBackoffMs(attempt), requestId, String.valueOf(cause));
                }
                Constants.LOG.warn("[numen-http][{}] ✗ {} in {}ms ({} chunks)",
                        requestId, cause, elapsedMs, chunkCount.get());
                return CompletableFuture.<Void>failedFuture(cause);
            }
            int status = resp.statusCode();
            if (status / 100 == 2) {
                Constants.LOG.debug("[numen-http][{}] ✓ {} in {}ms, {} chunks",
                        requestId, status, elapsedMs, chunkCount.get());
                return CompletableFuture.completedFuture((Void) null);
            }
            String body2 = resp.body() == null ? "" : resp.body();
            Constants.LOG.warn("[numen-http][{}] ✗ {} in {}ms — body: {}",
                    requestId, status, elapsedMs, truncate(body2, 500));
            if (attempt < MAX_RETRIES && shouldRetryStatus(status, resp.headers())) {
                long delay = retryAfterMs(resp.headers())
                        .filter(v -> v > 0 && v <= 60_000)
                        .orElse(computeBackoffMs(attempt));
                return retryAfterDelay(url, apiKey, body, chunkHandler, attempt,
                        delay, requestId, "HTTP " + status);
            }
            return CompletableFuture.<Void>failedFuture(new LlmHttpException(status, body2));
        }).thenCompose(f -> f);
    }

    private CompletableFuture<Void> retryAfterDelay(String url, String apiKey, JsonObject body,
                                                    Consumer<JsonObject> chunkHandler, int attempt,
                                                    long delayMs, String requestId, String reason) {
        Constants.LOG.warn("[numen-http][{}] retrying in {}ms (attempt {}/{}) — {}",
                requestId, delayMs, attempt + 1, MAX_RETRIES, reason);
        return CompletableFuture.supplyAsync(
                        () -> postSseAttempt(url, apiKey, body, chunkHandler, attempt + 1),
                        CompletableFuture.delayedExecutor(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS))
                .thenCompose(f -> f);
    }

    /** SDK-consensus retryable statuses: {@code x-should-retry} override first, then
     *  408 (request timeout), 409 (lock timeout), 429 (rate limit), all 5xx. */
    private static boolean shouldRetryStatus(int status, java.net.http.HttpHeaders headers) {
        var override = headers.firstValue("x-should-retry");
        if (override.isPresent()) {
            if ("true".equalsIgnoreCase(override.get())) return true;
            if ("false".equalsIgnoreCase(override.get())) return false;
        }
        return status == 408 || status == 409 || status == 429 || status >= 500;
    }

    /** Server-directed delay: {@code retry-after-ms} (float ms) first, then
     *  {@code retry-after} as float seconds; HTTP-date form is rare from LLM
     *  providers and deliberately unsupported. */
    private static java.util.Optional<Long> retryAfterMs(java.net.http.HttpHeaders headers) {
        try {
            var ms = headers.firstValue("retry-after-ms");
            if (ms.isPresent()) return java.util.Optional.of((long) Double.parseDouble(ms.get()));
            var s = headers.firstValue("retry-after");
            if (s.isPresent()) return java.util.Optional.of((long) (Double.parseDouble(s.get()) * 1000));
        } catch (NumberFormatException ignored) {
            // fall through to computed backoff
        }
        return java.util.Optional.empty();
    }

    /** {@code min(0.5s × 2ⁿ, 8s)} shaved by up to 25% jitter — the exact Stainless-SDK curve. */
    private static long computeBackoffMs(int attempt) {
        double sleepMs = Math.min(INITIAL_RETRY_DELAY_MS * Math.pow(2.0, attempt), MAX_RETRY_DELAY_MS);
        return (long) (sleepMs * (1.0 - 0.25 * java.util.concurrent.ThreadLocalRandom.current().nextDouble()));
    }

    // ---- internals ----

    private HttpRequest baseRequest(String url, String apiKey, String accept, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT);
        extraHeaders.forEach(b::header);   // per-site headers (e.g. OpenRouter Referer / Title)
        return b.header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private static CompletableFuture<JsonObject> interpretBuffered(String requestId, long t0,
                                                                    HttpResponse<String> resp) {
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        int status = resp.statusCode();
        String body = resp.body() == null ? "" : resp.body();
        if (status / 100 != 2) {
            Constants.LOG.warn("[numen-http][{}] ✗ {} in {}ms — body: {}",
                    requestId, status, elapsedMs, truncate(body, 500));
            return CompletableFuture.failedFuture(new LlmHttpException(status, body));
        }
        Constants.LOG.debug("[numen-http][{}] ✓ {} in {}ms ({} bytes)",
                requestId, status, elapsedMs, body.length());
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            return CompletableFuture.completedFuture(obj);
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(
                    new LlmHttpException(status, "response is not a JSON object: " + ex.getMessage()
                            + "; body: " + body));
        }
    }

    private static String nextRequestId() {
        return "lr-" + REQUEST_ID_SOURCE.incrementAndGet();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * Server-Sent Events subscriber. Buffers data lines per event (events
     * are blank-line separated, lines start with {@code data: }), parses
     * each completed event as JSON, and feeds it to the chunk handler.
     *
     * <h2>Multiline data handling</h2>
     * SSE spec allows multiple {@code data:} lines per event (concatenated
     * with {@code \n} between them when the event fires). OpenAI / DeepSeek
     * use single-line data exclusively, but we handle multiline for spec
     * compliance.
     *
     * <h2>{@code [DONE]} sentinel</h2>
     * OpenAI's stream terminates with {@code data: [DONE]\n\n}; we
     * specifically skip parsing that as JSON.
     */
    private static final class SseSubscriber implements Flow.Subscriber<String> {

        private final String requestId;
        private final Consumer<JsonObject> handler;
        private final AtomicLong chunkCount;
        /** Stamped on EVERY received line (data, keepalive comment, blank) — the idle
         *  watchdog's liveness signal. */
        private final AtomicLong lastActivityNanos;
        private final StringBuilder buffer = new StringBuilder();
        private Flow.Subscription subscription;

        SseSubscriber(String requestId, Consumer<JsonObject> handler, AtomicLong chunkCount,
                      AtomicLong lastActivityNanos) {
            this.requestId = requestId;
            this.handler = handler;
            this.chunkCount = chunkCount;
            this.lastActivityNanos = lastActivityNanos;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(String line) {
            lastActivityNanos.set(System.nanoTime());
            if (line.isEmpty()) {
                flushEvent();
            } else if (line.startsWith("data: ")) {
                if (buffer.length() > 0) buffer.append('\n');
                buffer.append(line, 6, line.length());
            } else if (line.startsWith("data:")) {
                // Spec-permissive: "data:" without trailing space is valid too.
                if (buffer.length() > 0) buffer.append('\n');
                buffer.append(line, 5, line.length());
            }
            // Other SSE fields (event:, id:, retry:) ignored — we don't need them.
        }

        @Override
        public void onError(Throwable t) {
            Constants.LOG.warn("[numen-http][{}] SSE stream error: {}",
                    requestId, t.getClass().getSimpleName() + ": " + t.getMessage());
            // Future will fail via the wrapping CompletableFuture.
        }

        @Override
        public void onComplete() {
            flushEvent();
        }

        private void flushEvent() {
            if (buffer.length() == 0) return;
            String data = buffer.toString();
            buffer.setLength(0);
            if ("[DONE]".equals(data)) return;
            try {
                JsonObject obj = JsonParser.parseString(data).getAsJsonObject();
                chunkCount.incrementAndGet();
                handler.accept(obj);
            } catch (RuntimeException ex) {
                Constants.LOG.warn("[numen-http][{}] ignoring malformed SSE chunk: {} (data: {})",
                        requestId, ex.getMessage(), truncate(data, 200));
            }
        }
    }
}
