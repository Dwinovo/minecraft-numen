package com.dwinovo.numen.client.voice;

import com.dwinovo.numen.client.bridge.BridgeDiscovery;
import com.google.gson.JsonObject;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Local Bridge.app adapter for OpenAI-compatible WAV speech output. */
public final class BridgeTts implements TtsBackend {
    public static final String BACKEND = "bridge";
    public static final String DEFAULT_MODEL = "qwen3-tts-flash-realtime";
    public static final String DEFAULT_VOICE = "Cherry";

    /**
     * Bridge 专用 HTTP/1.1 client。不能复用 {@code VoiceHttp.CLIENT}：JDK HttpClient
     * 默认尝试 HTTP/2（明文 h2c upgrade），而 Bridge 的 Hummingbird 服务不支持，
     * 直接断开连接，游戏侧表现为 {@code EOFException: EOF reached while reading}。
     */
    private static final HttpClient HTTP1_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final BridgeDiscovery.Info discovery;
    private final String model;
    private final String voice;
    private final HttpClient client;
    private final Duration timeout;

    public BridgeTts(BridgeDiscovery.Info discovery, String model, String voice) {
        this(discovery, model, voice, HTTP1_CLIENT, VoiceHttp.REQUEST_TIMEOUT);
    }

    BridgeTts(BridgeDiscovery.Info discovery, String model, String voice,
              HttpClient client, Duration timeout) {
        this.discovery = discovery;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        this.voice = voice == null || voice.isBlank() ? DEFAULT_VOICE : voice;
        this.client = client;
        this.timeout = timeout;
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text) {
        if (text == null || text.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("TTS text is empty"));
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("voice", voice);
        payload.addProperty("input", text);
        payload.addProperty("response_format", "wav");

        HttpRequest request = HttpRequest.newBuilder(discovery.speechUri())
                .timeout(timeout)
                .header("Authorization", "Bearer " + discovery.token())
                .header("Content-Type", "application/json")
                .header("Accept", "audio/wav")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .thenCompose(response -> {
                    byte[] body = response.body();
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                VoiceHttp.humanHttpError("Numen Bridge", response.statusCode(),
                                        body == null ? "" : new String(body))));
                    }
                    String contentType = response.headers().firstValue("Content-Type").orElse("");
                    if (!contentType.toLowerCase().startsWith("audio/wav")) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "Numen Bridge returned non-WAV audio"));
                    }
                    if (body == null || body.length == 0) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "Numen Bridge returned empty audio"));
                    }
                    return CompletableFuture.completedFuture(body);
                });
    }

    @Override
    public String describe() {
        return "numen-bridge(" + model + ", voice=" + voice + ")";
    }
}
