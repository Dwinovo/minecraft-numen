package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.agent.http.HttpLlmTransport;
import com.dwinovo.numen.agent.model.ModelRegistry;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmProvider;
import com.dwinovo.numen.agent.provider.StreamAccumulator;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Short, side-effect-free endpoint diagnostics used by the in-game settings screen. */
public final class LlmEndpointDiagnostics {

    private static final Duration MODELS_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CHAT_TIMEOUT = Duration.ofSeconds(35);

    public record Settings(String providerId, String apiKey, String model, String baseUrl, String proxy,
                           boolean fullUrl, String reasoningEffort) {
        public Settings(String providerId, String apiKey, String model, String baseUrl, String proxy,
                        boolean fullUrl) {
            this(providerId, apiKey, model, baseUrl, proxy, fullUrl, "auto");
        }
    }
    public record ModelsResult(String endpoint, List<String> models, long elapsedMillis) { }
    public record ConnectionResult(String endpoint, String model, String reply, long elapsedMillis) { }
    public record CapabilityResult(List<String> reasoningEfforts, boolean webReachable, long elapsedMillis) { }

    private LlmEndpointDiagnostics() { }

    public static CompletableFuture<ModelsResult> detectModels(Settings settings) {
        try {
            return detectModelsUnchecked(settings);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private static CompletableFuture<ModelsResult> detectModelsUnchecked(Settings settings) {
        Context context = context(settings);
        String endpoint = context.endpoint("/models");
        long started = System.nanoTime();
        return context.transport().get(endpoint, settings.apiKey(), MODELS_TIMEOUT)
                .thenApply(body -> new ModelsResult(endpoint, parseModels(body), elapsed(started)));
    }

    public static CompletableFuture<ConnectionResult> testConnection(Settings settings) {
        try {
            return testConnectionUnchecked(settings);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private static CompletableFuture<ConnectionResult> testConnectionUnchecked(Settings settings) {
        if (settings == null || settings.model() == null || settings.model().isBlank()) {
            throw new IllegalArgumentException("model id is empty");
        }
        Context context = context(settings);
        String endpoint = context.endpoint("/chat/completions");
        LlmProvider provider = context.provider();
        JsonObject body = provider.buildRequestBody(settings.model().trim(), "",
                List.of(context.provider().buildUserMessage("Reply with OK.")), new JsonArray());
        NumenLlmClient.applyReasoning(body, settings.reasoningEffort());
        long started = System.nanoTime();
        if (provider.supportsStreaming()) {
            body.addProperty("stream", true);
            JsonObject streamOptions = new JsonObject();
            streamOptions.addProperty("include_usage", true);
            body.add("stream_options", streamOptions);
            StreamAccumulator accumulator = new StreamAccumulator();
            return context.transport().postSse(endpoint, settings.apiKey(), body,
                            chunk -> provider.accumulateChunk(chunk, accumulator), CHAT_TIMEOUT)
                    .thenApply(ignored -> connection(endpoint, settings.model(),
                            provider.finalizeStream(accumulator), started));
        }
        body.addProperty("stream", false);
        return context.transport().post(endpoint, settings.apiKey(), body, CHAT_TIMEOUT)
                .thenApply(response -> connection(endpoint, settings.model(),
                        provider.parseResponseBody(response), started));
    }

    public static CompletableFuture<CapabilityResult> detectCapabilities(Settings settings) {
        long started = System.nanoTime();
        List<String> candidates = List.of("minimal", "low", "medium", "high", "xhigh");
        List<String> supported = java.util.Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String effort : candidates) {
            chain = chain.thenCompose(ignored -> testConnection(new Settings(settings.providerId(),
                            settings.apiKey(), settings.model(), settings.baseUrl(), settings.proxy(),
                            settings.fullUrl(), effort))
                    .thenAccept(result -> supported.add(effort))
                    .exceptionally(error -> null));
        }
        CompletableFuture<Boolean> web = new HttpLlmTransport(settings.proxy(), java.util.Map.of())
                .getText("https://www.bing.com/search?format=rss&q=Minecraft", Duration.ofSeconds(20))
                .thenApply(body -> body != null && body.contains("<item>"))
                .exceptionally(error -> false);
        return chain.thenCombine(web, (ignored, reachable) -> new CapabilityResult(
                List.copyOf(supported), reachable, elapsed(started)));
    }

    private static Context context(Settings settings) {
        if (settings == null) throw new IllegalArgumentException("settings are missing");
        if (settings.apiKey() == null || settings.apiKey().isBlank()) {
            throw new IllegalArgumentException("api key is empty");
        }
        String providerId = settings.providerId() == null || settings.providerId().isBlank()
                ? "openai" : settings.providerId();
        LlmProvider provider = NumenLlmClient.pickProvider(providerId);
        String base = settings.fullUrl() && settings.baseUrl() != null
                ? settings.baseUrl().trim()
                : NumenLlmClient.composeBaseUrl(settings.baseUrl(), ModelRegistry.baseUrl(providerId), provider);
        if (base.isBlank()) throw new IllegalArgumentException("base url is empty");
        HttpLlmTransport transport = new HttpLlmTransport(settings.proxy(), ModelRegistry.headers(providerId));
        return new Context(provider, transport, base, settings.fullUrl());
    }

    private static List<String> parseModels(JsonObject body) {
        JsonArray array = null;
        if (body.has("data") && body.get("data").isJsonArray()) array = body.getAsJsonArray("data");
        else if (body.has("models") && body.get("models").isJsonArray()) array = body.getAsJsonArray("models");
        if (array == null) throw new IllegalArgumentException("response has no 'data' or 'models' array");

        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (JsonElement element : array) {
            String id = modelId(element);
            if (id != null && !id.isBlank()) ids.add(id.trim());
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("endpoint returned an empty model list");
        ArrayList<String> sorted = new ArrayList<>(ids);
        sorted.sort(Comparator.comparing(String::toLowerCase));
        return List.copyOf(sorted);
    }

    private static String modelId(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) return element.getAsString();
        if (!element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();
        for (String key : List.of("id", "model", "name")) {
            if (object.has(key) && object.get(key).isJsonPrimitive()) return object.get(key).getAsString();
        }
        return null;
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static ConnectionResult connection(String endpoint, String model, AssistantTurn turn, long started) {
        String reply = turn == null ? "" : turn.content();
        return new ConnectionResult(endpoint, model.trim(), reply == null ? "" : reply.trim(), elapsed(started));
    }

    private record Context(LlmProvider provider, HttpLlmTransport transport, String baseUrl, boolean fullUrl) {
        private String endpoint(String suffix) {
            return fullUrl ? baseUrl : baseUrl + suffix;
        }
    }
}
