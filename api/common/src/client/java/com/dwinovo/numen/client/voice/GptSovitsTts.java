package com.dwinovo.numen.client.voice;

import com.google.gson.JsonObject;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * GPT-SoVITS api_v2 风格后端：{@code POST {base}/tts}，JSON body 携带
 * {@code text / text_lang / ref_audio_path / prompt_lang / prompt_text /
 * text_split_method / batch_size / media_type / streaming_mode}。
 * 无鉴权（本地推理服务的常态），要求 {@code media_type=wav} 且非流式——
 * 我们按句请求,单句本来就短,拿完整 WAV 最稳。
 *
 * <p>{@code ref_audio_path} 是 <b>TTS 服务那台机器上的</b>参考音频路径,
 * {@code prompt_text} 是那段参考音频说的话——两者是 GPT-SoVITS 零样本克隆的
 * 必备输入,填错服务端会 400,报错原文会进日志。
 */
public final class GptSovitsTts implements TtsBackend {

    private final String url;
    private final String refAudioPath;
    private final String promptText;
    private final String textLang;

    public GptSovitsTts(String baseUrl, String refAudioPath, String promptText, String textLang) {
        this.url = composeUrl(baseUrl);
        this.refAudioPath = refAudioPath == null ? "" : refAudioPath;
        this.promptText = promptText == null ? "" : promptText;
        this.textLang = (textLang == null || textLang.isBlank()) ? "zh" : textLang;
    }

    /** api_v2 服务的默认监听地址——URL 留空即用它,表单选型时也预填它。 */
    public static final String DEFAULT_BASE = "http://127.0.0.1:9880";

    /** 留空用默认本机地址,补默认 scheme(127.x 补 http),去尾斜杠,没带 /tts 就补上。 */
    static String composeUrl(String base) {
        String b = VoiceHttp.ensureScheme(base);
        if (b.isEmpty()) b = DEFAULT_BASE;
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/tts")) return b;
        return b + "/tts";
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text) {
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        body.addProperty("text_lang", textLang);
        body.addProperty("ref_audio_path", refAudioPath);
        body.addProperty("prompt_lang", textLang);
        body.addProperty("prompt_text", promptText);
        body.addProperty("text_split_method", "cut5");
        body.addProperty("batch_size", 1);
        body.addProperty("media_type", "wav");
        body.addProperty("streaming_mode", false);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(VoiceHttp.uriOf(url))
                    .timeout(VoiceHttp.REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);   // 坏配置走异步失败通道,绝不同步炸
        }

        return VoiceHttp.CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        throw new IllegalStateException(VoiceHttp.humanHttpError("GPT-SoVITS",
                                resp.statusCode(), new String(resp.body(), StandardCharsets.UTF_8)));
                    }
                    return resp.body();
                });
    }

    @Override
    public String describe() {
        return "gpt-sovits(" + url + ", ref=" + refAudioPath + ", lang=" + textLang + ")";
    }
}
