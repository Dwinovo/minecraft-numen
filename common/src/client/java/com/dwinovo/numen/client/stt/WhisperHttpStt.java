package com.dwinovo.numen.client.stt;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * OpenAI 兼容的批量语音转写:{@code POST {base}/v1/audio/transcriptions},
 * multipart/form-data 上传整段 WAV(字段 {@code file} + {@code model}),Bearer 鉴权,
 * 响应 {@code {"text":"..."}}。OpenAI 官方、硅基流动、Groq 等一票服务都是这套。
 *
 * <p>非流式:会话缓冲 PCM,{@link SttSession#finish} 时打包上传,结果只走
 * {@link SttListener#onFinal}。URL 组装与 TTS/LLM 同一套宽容规则。
 */
public final class WhisperHttpStt implements SttBackend {

    private static final String SUFFIX = "/audio/transcriptions";
    /** 缺省端点(硅基流动,国内直连、有免费/廉价 whisper 兼容模型)。接别家改 URL 即可。 */
    public static final String DEFAULT_BASE = "https://api.siliconflow.cn";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final String url;
    private final String apiKey;
    private final String model;

    public WhisperHttpStt(String baseUrl, String apiKey, String model) {
        this.url = composeUrl(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null ? "" : model;
    }

    /** 宽容拼 URL:留空用缺省;补 scheme;去尾斜杠;已带 /audio/transcriptions 直接用;带 /v1 只补后半;否则补 /v1/audio/transcriptions。 */
    static String composeUrl(String base) {
        String b = base == null ? "" : base.strip();
        if (b.isEmpty()) {
            b = DEFAULT_BASE;
        }
        if (!b.contains("://")) {
            b = "https://" + b;
        }
        if (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (b.endsWith(SUFFIX)) {
            return b;
        }
        if (b.endsWith("/v1")) {
            return b + SUFFIX;
        }
        return b + "/v1" + SUFFIX;
    }

    @Override
    public SttSession open(SttListener listener) {
        return new BatchSession(listener);
    }

    @Override
    public String describe() {
        return "whisper-http(" + url + ", model=" + model + ")";
    }

    /** 缓冲整段 PCM,finish 时打包为 WAV 一次性上传。 */
    private final class BatchSession implements SttSession {

        private final SttListener listener;
        private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        private volatile boolean cancelled;

        BatchSession(SttListener listener) {
            this.listener = listener;
        }

        @Override
        public void feed(byte[] chunk) {
            if (!cancelled) {
                pcm.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public void finish() {
            if (cancelled) {
                return;
            }
            byte[] wav;
            try {
                wav = SttAudio.pcmToWav(pcm.toByteArray());
            } catch (Exception e) {
                listener.onError(e);
                return;
            }
            String boundary = "----numen" + Long.toHexString(System.identityHashCode(this));
            byte[] body = Multipart.build(boundary, model, "audio.wav", wav);
            HttpRequest request;
            try {
                request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
            } catch (RuntimeException e) {
                listener.onError(e);
                return;
            }
            CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenAccept(resp -> {
                        if (cancelled) {
                            return;
                        }
                        if (resp.statusCode() / 100 != 2) {
                            listener.onError(new IllegalStateException(
                                    "STT HTTP " + resp.statusCode() + ": " + brief(resp.body())));
                            return;
                        }
                        listener.onFinal(parseText(resp.body()));
                    })
                    .exceptionally(t -> {
                        if (!cancelled) {
                            listener.onError(t);
                        }
                        return null;
                    });
        }
    }

    /** 从 {@code {"text":"..."}} 取转写;非 JSON 或缺字段时退回原文剪裁。 */
    private static String parseText(String body) {
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (obj.has("text")) {
                return obj.get("text").getAsString().strip();
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return brief(body);
    }

    private static String brief(String body) {
        String s = body == null ? "" : body.strip();
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
