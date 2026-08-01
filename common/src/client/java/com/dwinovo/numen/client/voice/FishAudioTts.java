package com.dwinovo.numen.client.voice;

import com.google.gson.JsonObject;

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
    /** 官方端点——URL 留空即用它,表单选型时也预填它(用户只需要填 key 和声线)。 */
    public static final String DEFAULT_BASE = "https://api.fish.audio";

    private final String url;
    private final String apiKey;
    private final String referenceId;
    private final String model;

    public FishAudioTts(String baseUrl, String apiKey, String referenceId, String model) {
        this.url = composeUrl(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.referenceId = normalizeReferenceId(referenceId);
        this.model = model == null ? "" : model.strip();
    }

    /** 宽容取 reference_id:填纯 ID 或直接粘贴声线页网址(fish.audio/m/&lt;id&gt;)都行——
     *  API 只认 ID(官方文档:reference_id = "Voice model ID from Fish Audio library"),
     *  网址形态就抠出 /m/ 后那一段。 */
    static String normalizeReferenceId(String raw) {
        String r = raw == null ? "" : raw.strip();
        int m = r.indexOf("/m/");
        if (m >= 0) {
            r = r.substring(m + 3);
            for (char cut : new char[]{'/', '?', '#'}) {
                int i = r.indexOf(cut);
                if (i >= 0) r = r.substring(0, i);
            }
        }
        return r;
    }

    /** 宽容拼 URL:留空用官方端点,补默认 scheme,去尾斜杠,没带 /tts 就补 /v1/tts。 */
    static String composeUrl(String base) {
        String b = VoiceHttp.ensureScheme(base);
        if (b.isEmpty()) b = DEFAULT_BASE;
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
        HttpRequest request;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(VoiceHttp.uriOf(url))
                    .timeout(VoiceHttp.REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey);
            if (!model.isEmpty()) {
                b.header("model", model);   // 合成模型经请求头选择,留空用服务端默认
            }
            request = b
                    .POST(HttpRequest.BodyPublishers.ofString(
                            buildBody(text, referenceId).toString(), StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException e) {
            // 配置烂(空/坏 URL、非法头)只能走异步失败通道——同步抛会把调用线程
            // (渲染线程/管线)整个带走,试音按钮曾因此崩游戏。
            return CompletableFuture.failedFuture(e);
        }

        return VoiceHttp.CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        throw new IllegalStateException(VoiceHttp.humanHttpError("Fish Audio",
                                resp.statusCode(), new String(resp.body(), StandardCharsets.UTF_8)));
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
