package com.dwinovo.numen.client.voice;

import com.google.gson.JsonObject;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * OpenAI 兼容语音合成：{@code POST {base}/v1/audio/speech}，
 * body {@code {"model","voice","input","response_format":"wav"}}，Bearer 鉴权。
 * OpenAI 官方、硅基流动（CosyVoice 系列）等一批服务都是这套协议。
 *
 * <p>URL 组装与 LLM 侧同一套宽容规则：填站点根（https://api.siliconflow.cn）、
 * 填到 /v1、或直接填完整 /v1/audio/speech 都行。
 */
public final class OpenAiCompatibleTts implements TtsBackend {

    private static final String SPEECH_SUFFIX = "/audio/speech";

    private final String url;
    private final String apiKey;
    private final String model;
    private final String voice;

    public OpenAiCompatibleTts(String baseUrl, String apiKey, String model, String voice) {
        this.url = composeUrl(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null ? "" : model;
        this.voice = voice == null ? "" : voice;
    }

    /** 缺省端点(硅基流动,国内直连、有免费 TTS 模型)——"OpenAI 兼容"没有唯一官方,
     *  这里选一个开箱能用的;接别家(OpenAI 本尊等)手动改 URL 即可。 */
    public static final String DEFAULT_BASE = "https://api.siliconflow.cn";

    /** 宽容拼 URL：留空用缺省端点；补默认 scheme；去尾斜杠；已带 /audio/speech 直接用；带 /v1 只补后半;否则补 /v1/audio/speech。 */
    static String composeUrl(String base) {
        String b = VoiceHttp.ensureScheme(base);
        if (b.isEmpty()) b = DEFAULT_BASE;
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith(SPEECH_SUFFIX)) return b;
        if (b.endsWith("/v1")) return b + SPEECH_SUFFIX;
        return b + "/v1" + SPEECH_SUFFIX;
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("input", text);
        body.addProperty("voice", voice);
        body.addProperty("response_format", "wav");

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(VoiceHttp.uriOf(url))
                    .timeout(VoiceHttp.REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);   // 坏配置走异步失败通道,绝不同步炸
        }

        return VoiceHttp.CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        throw new IllegalStateException(VoiceHttp.humanHttpError("TTS",
                                resp.statusCode(), new String(resp.body(), StandardCharsets.UTF_8)));
                    }
                    return resp.body();
                });
    }

    @Override
    public String describe() {
        return "openai-compatible(" + url + ", model=" + model + ", voice=" + voice + ")";
    }
}
