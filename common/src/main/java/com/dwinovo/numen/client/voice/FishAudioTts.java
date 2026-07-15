package com.dwinovo.numen.client.voice;

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Fish Audio 语音合成:{@code POST {base}/v1/tts},Bearer 鉴权,
 * JSON body {@code {text, reference_id, format:"wav"}},响应即裸音频字节
 * (请求 wav,直接进 {@link WavCodec})。
 *
 * <p>{@code reference_id} 是 Fish Audio 声线模型的 id(声线库里的一串 hex,
 * 或自己克隆的模型);合成模型经 <b>{@code model} 请求头</b>选择
 * (如 {@code s1}、{@code s2-pro}),留空则用服务端默认,不发该头。
 */
public final class FishAudioTts implements TtsBackend {

    private static final String TTS_SUFFIX = "/v1/tts";

    private final String url;
    private final String apiKey;
    private final String referenceId;
    private final String model;

    public FishAudioTts(String baseUrl, String apiKey, String referenceId, String model) {
        this.url = composeUrl(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.referenceId = referenceId == null ? "" : referenceId.strip();
        this.model = model == null ? "" : model.strip();
    }

    /** 宽容拼 URL:去尾斜杠,没带 /tts 就补 /v1/tts。 */
    static String composeUrl(String base) {
        String b = base == null ? "" : base.strip();
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/tts")) return b;
        return b + TTS_SUFFIX;
    }

    /** 请求 body(纯函数,可测):text + reference_id(空则省略,用账号默认声线)+ wav。 */
    static JsonObject buildBody(String text, String referenceId) {
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        if (referenceId != null && !referenceId.isBlank()) {
            body.addProperty("reference_id", referenceId.strip());
        }
        body.addProperty("format", "wav");
        return body;
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(VoiceHttp.REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey);
        if (!model.isEmpty()) {
            b.header("model", model);   // 合成模型经请求头选择,留空用服务端默认
        }
        HttpRequest request = b
                .POST(HttpRequest.BodyPublishers.ofString(
                        buildBody(text, referenceId).toString(), StandardCharsets.UTF_8))
                .build();

        return VoiceHttp.CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        String snippet = new String(resp.body(), StandardCharsets.UTF_8);
                        if (snippet.length() > 300) snippet = snippet.substring(0, 300) + "...";
                        throw new IllegalStateException(
                                "Fish Audio HTTP " + resp.statusCode() + ": " + snippet);
                    }
                    return resp.body();
                });
    }

    @Override
    public String describe() {
        return "fish-audio(" + url + ", reference=" + referenceId
                + (model.isEmpty() ? "" : ", model=" + model) + ")";
    }
}
